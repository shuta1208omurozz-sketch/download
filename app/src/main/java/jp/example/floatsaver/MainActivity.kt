package jp.example.floatsaver

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.text.Editable
import android.text.TextWatcher
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.FrameLayout
import android.widget.ScrollView
import kotlin.math.min
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var urlInput: EditText
    private lateinit var analyzeButton: Button
    private lateinit var downloadButton: Button
    private lateinit var overlayButton: Button
    private lateinit var titleText: TextView
    private lateinit var durationText: TextView
    private lateinit var statusText: TextView
    private lateinit var sizeNote: TextView
    private lateinit var qualitySpinner: Spinner
    private lateinit var limitSpinner: Spinner
    private lateinit var containerSpinner: Spinner
    private lateinit var workingBar: ProgressBar
    private lateinit var selectionSection: View
    private var details: MediaDetails? = null
    private var activeChoices = emptyList<QualityChoice>()
    private var analyzedUrl = ""
    private var referer: String? = null
    private var userAgent: String? = null
    private var analyzeGeneration = 0
    private var pendingAnalysisUrl = ""

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val backdrop = findViewById<FrameLayout>(R.id.panelBackdrop)
        val compactCard = findViewById<ScrollView>(R.id.panelCard)
        compactCard.layoutParams = (compactCard.layoutParams as FrameLayout.LayoutParams).apply {
            width = min(resources.displayMetrics.widthPixels - dp(32), dp(370))
            height = min((resources.displayMetrics.heightPixels * 0.52f).toInt(), dp(420))
        }
        backdrop.setOnClickListener { finish() }
        compactCard.setOnClickListener { /* Keep taps inside the panel. */ }
        urlInput = findViewById(R.id.urlInput)
        analyzeButton = findViewById(R.id.analyzeButton)
        downloadButton = findViewById(R.id.downloadButton)
        overlayButton = findViewById(R.id.overlayButton)
        titleText = findViewById(R.id.titleText)
        durationText = findViewById(R.id.durationText)
        statusText = findViewById(R.id.statusText)
        sizeNote = findViewById(R.id.sizeNote)
        qualitySpinner = findViewById(R.id.qualitySpinner)
        limitSpinner = findViewById(R.id.limitSpinner)
        containerSpinner = findViewById(R.id.containerSpinner)
        workingBar = findViewById(R.id.workingBar)
        selectionSection = findViewById(R.id.selectionSection)
        urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (details != null || analyzedUrl.isNotBlank() ||
                    (pendingAnalysisUrl.isNotBlank() && s.toString() != pendingAnalysisUrl)) {
                    details = null
                    analyzedUrl = ""
                    pendingAnalysisUrl = ""
                    referer = null
                    userAgent = null
                    ++analyzeGeneration // invalidate any old results
                    analyzeButton.isEnabled = true
                    workingBar.visibility = View.GONE
                    findViewById<Button>(R.id.fallbackBrowserButton).visibility = View.GONE
                    refreshChoices()
                    statusText.text = "URLを変更しました。「動画を調べる」を押してください。"
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        findViewById<Button>(R.id.closeButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.stopFloatingButton).setOnClickListener {
            stopService(Intent(this, OverlayBubbleService::class.java))
            Toast.makeText(this, "フローティングを終了しました", Toast.LENGTH_SHORT).show()
            finish()
        }
        findViewById<Button>(R.id.braveUrlButton).setOnClickListener { getBrowserUrlWithoutRedirect() }
        findViewById<Button>(R.id.fallbackBrowserButton).setOnClickListener { openBrowserFromUrl() }
        findViewById<TextView>(R.id.versionText).text =
            "v" + packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<Button>(R.id.pasteButton).setOnClickListener { pasteFromClipboard(analyzeImmediately = true) }
        findViewById<Button>(R.id.browserButton).setOnClickListener { openBrowserFromUrl() }
        findViewById<Button>(R.id.historyButton).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        analyzeButton.setOnClickListener { analyze() }
        downloadButton.setOnClickListener { download() }
        overlayButton.setOnClickListener { toggleOverlay() }
        limitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            MediaEngine.SIZE_LIMIT_LABELS)
        limitSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                refreshChoices()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        qualitySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateSizeNote()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        containerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateSizeNote()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        refreshChoices()
        handleIncomingIntent(intent)
        updateOverlayButton()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 33)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::overlayButton.isInitialized) updateOverlayButton()
    }

    private fun openBrowserFromUrl() {
        startActivity(Intent(this, BrowserActivity::class.java).apply {
            findHttpUrl(urlInput.text.toString())?.let { putExtra(BrowserActivity.KEY_PAGE, it) }
        })
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        val browserUrl = incoming?.getStringExtra("sourceUrl")
        val shared = if (incoming?.action == Intent.ACTION_SEND) {
            incoming.getStringExtra(Intent.EXTRA_TEXT)
        } else null
        val url = findHttpUrl(browserUrl ?: shared.orEmpty())
        if (url != null) {
            urlInput.setText(url)
            referer = incoming?.getStringExtra("sourceReferer")
            userAgent = incoming?.getStringExtra("sourceUserAgent")
            analyze()
        } else if (incoming?.getBooleanExtra("autoPaste", false) == true) {
            // Don't redirect users to Accessibility Settings. Copy/Share work without it.
            getBraveUrlWithoutRedirect()
        }
    }

    private fun getBrowserUrlWithoutRedirect() {
        val fromBrowser = BravePageUrlService.latestUrl()
        if (fromBrowser != null) {
            urlInput.setText(fromBrowser)
            analyze()
            return
        }
        // The user explicitly pressed this button. Clipboard access is requested only
        // from the foreground Activity, not from an inaccessible background service.
        val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val copied = runCatching {
            findHttpUrl(clip.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty())
        }.getOrNull()
        if (copied != null) {
            urlInput.setText(copied)
            analyze()
        } else {
            statusText.text = "ブラウザで「共有 → FloatSaver」を選ぶか、URLをコピーしてください。自動取得には任意のユーザー補助権限が必要です。"
        }
    }

    private fun pasteFromClipboard(analyzeImmediately: Boolean = false) {
        val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val raw = runCatching {
            clip.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        }.getOrDefault("")
        val url = findHttpUrl(raw)
        if (url == null) {
            statusText.text = "URLを取得できませんでした。ブラウザで「共有 → FloatSaver」を選ぶか、URLをコピーしてください。"
        } else {
            referer = null
            userAgent = null
            urlInput.setText(url)
            statusText.text = "URLを取得しました"
            if (analyzeImmediately) analyze()
        }
    }

    private fun refreshChoices() {
        if (!::qualitySpinner.isInitialized || !::limitSpinner.isInitialized) return
        val maxBytes = MediaEngine.SIZE_LIMITS_MB.getOrNull(limitSpinner.selectedItemPosition)
            ?.times(1_000_000L)
        val previousSelection = activeChoices.getOrNull(qualitySpinner.selectedItemPosition)?.formatSelector
        // An unknown-size format should remain selectable; mark it as unverifiable.
        // Excluding unknown sizes made legitimate live streams disappear entirely.
        activeChoices = details?.qualities?.filter { choice ->
            maxBytes == null || choice.estimatedBytes == null || choice.estimatedBytes <= maxBytes
        }.orEmpty()
        qualitySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            if (activeChoices.isEmpty()) listOf("該当する画質がありません") else activeChoices)
        val selected = activeChoices.indexOfFirst { it.formatSelector == previousSelection }
        if (selected >= 0) qualitySpinner.setSelection(selected)
        val gif = details?.isGif == true
        containerSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            when {
                gif -> listOf("GIF（オリジナル）")
                details?.qualities?.any { it.formatSelector == "direct" } == true ->
                    listOf("元の形式（直リンク）")
                else -> MediaEngine.VIDEO_CONTAINERS
            })
        downloadButton.isEnabled = activeChoices.isNotEmpty()
        selectionSection.visibility = if (details == null) View.GONE else View.VISIBLE
        updateSizeNote()
    }

    private fun updateSizeNote() {
        if (!::sizeNote.isInitialized || !::qualitySpinner.isInitialized || !::containerSpinner.isInitialized) return
        val item = activeChoices.getOrNull(qualitySpinner.selectedItemPosition)
        sizeNote.text = if (item == null) {
            "該当する画質がありません。容量の条件を緩めてください。"
        } else if (containerSpinner.selectedItemPosition > 0 && details?.isGif != true) {
            "元データ: ${sizeLabel(item.estimatedBytes, item.isApproximate)}。形式変換後の容量は変わります。"
        } else {
            "選択した元データ: ${sizeLabel(item.estimatedBytes, item.isApproximate)}。容量不明の候補は上限を確認できません。"
        }
    }

    private fun analyze() {
        val url = urlInput.text.toString().trim()
        if (url.isEmpty()) { statusText.text = "URLを入力してください"; return }
        val generation = ++analyzeGeneration
        pendingAnalysisUrl = url
        details = null
        analyzedUrl = ""
        refreshChoices()
        analyzeButton.isEnabled = false
        workingBar.visibility = View.VISIBLE
        findViewById<Button>(R.id.fallbackBrowserButton).visibility = View.GONE
        statusText.text = "動画・GIFを解析しています…"
        val headerReferer = referer
        val headerAgent = userAgent
        Thread {
            val outcome = runCatching { MediaEngine.analyze(applicationContext, url, headerReferer, headerAgent) }
            runOnUiThread {
                if (generation != analyzeGeneration || isFinishing) return@runOnUiThread
                analyzeButton.isEnabled = true
                pendingAnalysisUrl = ""
                workingBar.visibility = View.GONE
                outcome.onSuccess { data ->
                    details = data
                    analyzedUrl = url
                    titleText.text = data.title
                    durationText.text = if (data.durationSeconds == null) {
                        if (data.isGif) "GIFアニメーション（再生時間不明）" else "再生時間: 不明"
                    } else {
                        val sec = data.durationSeconds.toLong()
                        "再生時間: ${"%02d:%02d:%02d".format(Locale.JAPAN,
                            sec / 3600, (sec % 3600) / 60, sec % 60)}"
                    }
                    refreshChoices()
                    statusText.text = "動画が見つかりました。画質を選んで保存してください。"
                    findViewById<Button>(R.id.fallbackBrowserButton).visibility = View.GONE
                    findViewById<ScrollView>(R.id.panelCard).post {
                        findViewById<ScrollView>(R.id.panelCard)
                            .smoothScrollTo(0, findViewById<View>(R.id.selectionSection).top)
                    }
                }.onFailure { error ->
                    titleText.text = "解析できませんでした"
                    durationText.text = "再生時間: ー"
                    refreshChoices()
                    statusText.text = ErrorHints.describe(error) +
                        " ここから再生画面で動画を探すこともできます。"
                    findViewById<Button>(R.id.fallbackBrowserButton).visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun download() {
        val data = details ?: return
        if (urlInput.text.toString().trim() != analyzedUrl) {
            statusText.text = "URLが変更されています。再解析してください"
            return
        }
        val quality = activeChoices.getOrNull(qualitySpinner.selectedItemPosition) ?: return
        val container = if (data.isGif) MediaEngine.CONTAINER_GIF else
            MediaEngine.CONTAINER_KEYS.getOrElse(containerSpinner.selectedItemPosition) {
                MediaEngine.CONTAINER_ORIGINAL
            }
        runCatching {
            DownloadService.start(this, analyzedUrl, quality.formatSelector,
                data.isGif, container, data.title, referer, userAgent)
        }.onSuccess {
            Toast.makeText(this, "保存を開始しました。進捗は通知・履歴で確認できます。", Toast.LENGTH_LONG).show()
            finish()
        }.onFailure { statusText.text = "保存を開始できませんでした。アプリの通知・電池設定を確認してください。" }
    }

    private fun updateOverlayButton() {
        overlayButton.text = when {
            !Settings.canDrawOverlays(this) -> "画面上への表示を許可"
            OverlayBubbleService.running -> "フローティングボタンを停止"
            else -> "フローティングボタンを表示"
        }
    }

    private fun toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return
        }
        if (OverlayBubbleService.running) {
            stopService(Intent(this, OverlayBubbleService::class.java))
        } else {
            startForegroundService(Intent(this, OverlayBubbleService::class.java))
        }
        overlayButton.postDelayed({ updateOverlayButton() }, 350)
    }
}

fun findHttpUrl(text: String): String? =
    Regex("https?://[^\\s<>\\\"]+", RegexOption.IGNORE_CASE)
        .find(text)?.value?.trimEnd(')', '。', ',', '、', '\'')
