package jp.example.floatsaver

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicInteger

/** Observes the in-app WebView only. It does not read other apps' traffic. */
class BrowserActivity : Activity() {
    companion object { const val KEY_PAGE = "page" }

    private data class Candidate(
        val url: String, val kind: String, val origin: String, val priority: Int,
        val durationSeconds: Double? = null, val playbackSeconds: Double? = null,
        val evidence: AdClassification.Evidence = AdClassification.Evidence(),
        val lastPlayingAt: Long = 0L,
        val lastObservedAt: Long = 0L
    )
    private lateinit var web: WebView
    private lateinit var address: EditText
    private lateinit var results: LinearLayout
    private lateinit var status: TextView
    private val found = LinkedHashMap<String, Candidate>()
    private val handler = Handler(Looper.getMainLooper())
    private var currentPage = ""
    private var loadSerial = 0
    // shouldInterceptRequest runs off the UI thread: use a thread-safe generation.
    private val pageGeneration = AtomicInteger(0)
    private var scanEnabled = false
    private var renderPending = false

    // Polling is bounded to a visible activity and does not require a JS bridge.
    private val scanLoop = object : Runnable {
        override fun run() {
            if (!scanEnabled || isFinishing || isDestroyed) return
            val url = web.url.orEmpty()
            if (url.startsWith("http")) {
                if (url.substringBefore('#') != currentPage.substringBefore('#')) {
                    currentPage = url
                    loadSerial++
                    pageGeneration.incrementAndGet()
                    found.clear()
                    address.setText(url)
                    scheduleRepaint()
                }
                scanDom()
                scheduleRepaint() // Refresh stale playing labels even when URLs stay unchanged.
            }
            handler.postDelayed(this, 4000L)
        }
    }
    private val repaint = Runnable {
        renderPending = false
        renderCandidates()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)
        address = findViewById(R.id.addressInput)
        status = findViewById(R.id.detectStatus)
        results = findViewById(R.id.detectedResults)
        web = findViewById(R.id.browserWebView)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.javaScriptCanOpenWindowsAutomatically = false
        web.settings.setSupportMultipleWindows(false)
        web.settings.mediaPlaybackRequiresUserGesture = true
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                currentPage = url.orEmpty()
                loadSerial++
                pageGeneration.incrementAndGet()
                found.clear()
                scheduleRepaint()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // A previous navigation can finish after a newer one starts.
                if (url?.substringBefore('#') != web.url?.substringBefore('#')) return
                currentPage = url.orEmpty()
                address.setText(currentPage)
                scanDom()
                val serial = loadSerial
                handler.postDelayed({
                    if (!isFinishing && serial == loadSerial) scanDom()
                }, 1100L)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                val accept = request.requestHeaders.entries.firstOrNull {
                    it.key.equals("Accept", ignoreCase = true)
                }?.value
                val kind = DetectionRules.networkHint(url, accept) ?: return null
                val page = pageGeneration.get()
                // Never proxy resource bytes; just observe URLs for likely media.
                runOnUiThread {
                    if (page == pageGeneration.get()) {
                        addCandidate(url, kind,
                            if (DetectionRules.kind(url) != null) "通信から検出" else "動画の通信候補（要確認）",
                            if (DetectionRules.kind(url) != null) 2 else 1)
                    }
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val scheme = request?.url?.scheme?.lowercase()
                return scheme != "https" && scheme != "http"
            }
        }
        web.setDownloadListener { url, _, _, mime, _ ->
            val kind = when {
                mime?.startsWith("video/", true) == true -> "動画"
                mime?.equals("image/gif", true) == true -> "GIF"
                else -> DetectionRules.kind(url)
            }
            if (kind != null) addCandidate(url, kind, "ページのダウンロード操作", 3)
        }
        findViewById<Button>(R.id.goButton).setOnClickListener { navigate() }
        findViewById<Button>(R.id.scanButton).setOnClickListener {
            scanDom()
            status.text = "再検出しています。本編を再生してから候補を確認してください。"
        }
        findViewById<Button>(R.id.pageAnalyzeButton).setOnClickListener {
            openAnalyzer(web.url.orEmpty(), null)
        }
        findViewById<Button>(R.id.browserBackButton).setOnClickListener {
            if (web.canGoBack()) web.goBack() else finish()
        }
        intent.getStringExtra(KEY_PAGE)?.takeIf { it.isNotBlank() }?.let {
            address.setText(it)
            navigate()
        } ?: run {
            status.text = "① ページを開く → ② 広告終了後に本編を再生 → ③ 候補を確認"
        }
    }

    private fun navigate() {
        val raw = address.text.toString().trim()
        val url = if (raw.contains("://")) raw else "https://$raw"
        val valid = runCatching { MediaEngine.validatedUrl(url) }.getOrNull()
        if (valid == null) { status.text = "URLを確認してください（http/httpsのみ）"; return }
        web.loadUrl(valid)
    }

    private fun scanDom() {
        if (!::web.isInitialized || isFinishing) return
        // Only video/source and video metadata can include extensionless URLs.
        // GIF images and regular links require positive URL format hints.
        val script = BrowserMediaScanner.script
        val serial = loadSerial
        web.evaluateJavascript(script) { raw ->
            if (isFinishing || isDestroyed || serial != loadSerial) return@evaluateJavascript
            runCatching {
                val values = JSONArray(raw)
                for (i in 0 until values.length()) {
                    val item = values.optJSONObject(i) ?: continue
                    val url = item.optString("url")
                    val source = item.optString("kind")
                    val derived = DetectionRules.kind(url)
                    val kind = if (source == "iframe") "プレイヤー" else
                        derived ?: if (source == "video" || source == "playing-video") "動画" else null
                    val playing = source == "playing-video" && item.optBoolean("playing", false)
                    val duration = if (item.isNull("duration")) null else item.optDouble("duration")
                        .takeIf { it.isFinite() && it > 0 }
                    val elapsed = if (item.isNull("elapsed")) null else item.optDouble("elapsed")
                        .takeIf { it.isFinite() && it >= 0 }
                    val priority = when (source) {
                        "playing-video" -> 7
                        "video", "source" -> 4
                        "meta" -> 3
                        "resource" -> 2
                        "iframe" -> 1
                        "jsonld" -> 3
                        "source-data" -> 3
                        "image" -> 3
                        "link" -> 1
                        else -> 2
                    }
                    if (kind != null) addCandidate(url, kind,
                        when (source) {
                            "playing-video" -> "再生中の動画"
                            "resource" -> "通信から検出"
                            else -> if (priority >= 3) "ページから検出" else "リンクから検出"
                        }, priority, playing, duration, elapsed,
                        item.optBoolean("adMarked", false), source in setOf("video", "playing-video"))
                }
            }
        }
    }

    private fun addCandidate(
        raw: String, kind: String, origin: String, priority: Int,
        playing: Boolean = false, duration: Double? = null, elapsed: Double? = null,
        adMarked: Boolean = false, fromVideoElement: Boolean = false
    ) {
        if (isFinishing || isDestroyed) return
        val url = runCatching { MediaEngine.validatedUrl(raw) }.getOrNull() ?: return
        val key = url.substringBefore('#') // Preserve signed query strings.
        val old = found[key]
        // Video element observations must be processed even when paused: otherwise
        // a stale ad marker cannot be reconsidered after content switches.
        if (old != null && old.priority >= priority && !playing && !adMarked && !fromVideoElement) return
        if (old == null && found.size >= 100) {
            // Playing content may displace an older low-priority network or ad URL.
            val weakest = found.entries.minWithOrNull(compareBy<Map.Entry<String, Candidate>>
                { it.value.priority }.thenByDescending { verdict(it.value).order })
            if (weakest == null || weakest.value.priority >= priority) return
            found.remove(weakest.key)
        }
        val now = SystemClock.elapsedRealtime()
        val evidence = AdClassification.observe(
            old?.evidence ?: AdClassification.Evidence(),
            fromVideoElement, playing, elapsed, adMarked
        )
        found[key] = Candidate(
            url, kind, if (old != null && old.priority > priority) old.origin else origin,
            maxOf(old?.priority ?: 0, priority),
            duration ?: old?.durationSeconds,
            elapsed ?: old?.playbackSeconds,
            evidence,
            if (playing) now else old?.lastPlayingAt ?: 0L,
            now
        )
        scheduleRepaint()
    }

    private fun verdict(candidate: Candidate): AdClassification.Verdict {
        val recentlyPlaying = candidate.lastPlayingAt != 0L &&
            SystemClock.elapsedRealtime() - candidate.lastPlayingAt < 10_000L
        return AdClassification.classify(AdClassification.Signals(
            candidate.url, recentlyPlaying, candidate.durationSeconds,
            candidate.playbackSeconds, candidate.evidence.adMarked,
            candidate.evidence.samples
        ))
    }

    private fun scheduleRepaint() {
        if (renderPending) return
        renderPending = true
        handler.postDelayed(repaint, 220L) // debounce bursty network observations
    }

    private fun renderCandidates() {
        if (!::results.isInitialized) return
        // Drop media that was seen only on a long-gone network request or old ad.
        val now = SystemClock.elapsedRealtime()
        found.entries.removeAll { (_, candidate) ->
            candidate.lastObservedAt > 0L && now - candidate.lastObservedAt > 180_000L
        }
        results.removeAllViews()
        val candidates = found.values.sortedWith(compareBy<Candidate> { verdict(it).order }
            .thenByDescending { it.priority })
        val adCount = candidates.count { verdict(it) == AdClassification.Verdict.AD_SUSPECT }
        val mainCount = candidates.count { verdict(it) == AdClassification.Verdict.MAIN_LIKELY }
        status.text = when {
            candidates.isEmpty() -> "まだ候補がありません。広告が終わって本編を再生し、「再生後に検出」を押してください。"
            mainCount > 0 -> "本編候補 ${mainCount}件（未確認）／広告の疑い ${adCount}件。保存前に内容を確認してください。"
            adCount > 0 -> "候補 ${candidates.size}件（広告の疑い ${adCount}件）。広告が終わって本編が始まったら再検出してください。"
            else -> "候補 ${candidates.size}件。下の候補をタップすると画質選択へ進みます。広告と本編は未確定です。"
        }
        candidates.take(35).forEachIndexed { index, item ->
            val uri = android.net.Uri.parse(item.url)
            val host = DetectionRules.safeHost(item.url)
            val filename = uri.lastPathSegment.orEmpty().takeIf { it.isNotBlank() }?.take(45) ?: "メディア"
            val state = verdict(item)
            val seconds = item.durationSeconds?.takeIf { it.isFinite() && it > 0 }
                ?.let { " ・ ${it.toInt() / 60}分${it.toInt() % 60}秒" } ?: ""
            results.addView(TextView(this).apply {
                setTextColor(Color.rgb(22, 47, 86))
                textSize = 15f
                text = "${index + 1}. 【${state.label}】${item.kind}$seconds\n$filename\n$host ・ ${item.origin}"
                setPadding(16, 17, 16, 17)
                setBackgroundColor(if (state == AdClassification.Verdict.AD_SUSPECT)
                    Color.rgb(255, 246, 231)
                else if (index % 2 == 0) Color.rgb(239, 245, 252) else Color.WHITE)
                isClickable = true
                setOnClickListener {
                    if (item.kind == "プレイヤー") {
                        AlertDialog.Builder(this@BrowserActivity)
                            .setTitle("埋め込みプレイヤーを開く")
                            .setMessage("動画そのものではなく埋め込みページです。開いた先で本編を再生し、再検出してください。広告ページの可能性もあります。")
                            .setNegativeButton("戻る", null)
                            .setPositiveButton("開く") { _, _ -> web.loadUrl(item.url) }
                            .show()
                    } else if (state == AdClassification.Verdict.AD_SUSPECT) {
                        AlertDialog.Builder(this@BrowserActivity)
                            .setTitle("広告の可能性があります")
                            .setMessage("この候補は広告の可能性があります。本編の再生が始まってから再検出することをおすすめします。\n\nそれでも解析しますか？")
                            .setNegativeButton("戻る", null)
                            .setPositiveButton("解析する") { _, _ -> openAnalyzer(item.url, currentPage) }
                            .show()
                    } else openAnalyzer(item.url, currentPage)
                }
            })
        }
        if (candidates.size > 35) results.addView(TextView(this).apply {
            text = "候補が多いため優先度の高い35件のみ表示します。"
        })
    }

    private fun openAnalyzer(url: String, referer: String?) {
        val target = runCatching { MediaEngine.validatedUrl(url) }.getOrNull()
        if (target == null) { status.text = "解析するURLがありません"; return }
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("sourceUrl", target)
            if (!referer.isNullOrBlank()) putExtra("sourceReferer", referer)
            putExtra("sourceUserAgent", web.settings.userAgentString)
        })
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.onResume()
        scanEnabled = true
        handler.removeCallbacks(scanLoop)
        handler.postDelayed(scanLoop, 1200L)
    }

    override fun onPause() {
        scanEnabled = false
        handler.removeCallbacks(scanLoop)
        if (::web.isInitialized) web.onPause()
        super.onPause()
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        scanEnabled = false
        handler.removeCallbacks(scanLoop)
        handler.removeCallbacks(repaint)
        web.stopLoading()
        web.destroy()
        super.onDestroy()
    }
}

fun isLikelyMedia(value: String): Boolean = DetectionRules.kind(value) != null
