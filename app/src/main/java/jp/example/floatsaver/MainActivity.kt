package jp.example.floatsaver

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.Gravity
import android.view.WindowManager
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
import android.widget.BaseAdapter
import android.view.ViewGroup
import android.util.TypedValue
import kotlin.math.min
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.abs
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
    private var knownVideoCandidate = false
    private var panelUiScale = 1f

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    /** Spinner rows are created after initial inflation, so scale them too. */
    private fun <T> scaledAdapter(values: List<T>): ArrayAdapter<T> =
        object : ArrayAdapter<T>(this, android.R.layout.simple_spinner_item, values) {
            init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            private fun resizeText(view: View): View {
                (view as? TextView)?.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                    maxOf(10f, 14f * panelUiScale))
                return view
            }
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                resizeText(super.getView(position, convertView, parent))
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                resizeText(super.getDropDownView(position, convertView, parent))
        }

    private fun setupResizablePanel(shell: FrameLayout) {
        val prefs = getSharedPreferences("resizable_window", MODE_PRIVATE)
        val density = resources.displayMetrics.density
        val inset = dp(12)
        val contentScaler = PanelUiScaler(findViewById(R.id.panelCard))

        fun updateContents(width: Int, height: Int) {
            val next = PanelUiScaleMath.factor(width, height, density)
            if (abs(next - panelUiScale) < 0.012f) return
            panelUiScale = next
            contentScaler.apply(next)
            if (::limitSpinner.isInitialized) {
                listOf(limitSpinner, qualitySpinner, containerSpinner).forEach {
                    (it.adapter as? BaseAdapter)?.notifyDataSetChanged()
                }
            }
        }
        // FloatSaver is a compact Activity window, not a screen-sized dark backdrop.
        // FLAG_NOT_TOUCH_MODAL allows touches outside its actual window to go to Brave.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setGravity(Gravity.CENTER)
        window.setBackgroundDrawableResource(android.R.color.transparent)

        fun screenWidth() = resources.displayMetrics.widthPixels
        fun screenHeight() = resources.displayMetrics.heightPixels
        fun minWidth() = minOf(dp(200), (screenWidth() - 2 * inset).coerceAtLeast(1))
        fun minHeight() = minOf(dp(150), (screenHeight() - 2 * inset).coerceAtLeast(1))
        fun maxWidth() = (screenWidth() - 2 * inset).coerceAtLeast(1)
        fun maxHeight() = (screenHeight() - 2 * inset).coerceAtLeast(1)

        fun applySizeAndPosition(geometry: PanelResizeMath.Geometry) {
            shell.layoutParams = (shell.layoutParams as FrameLayout.LayoutParams).apply {
                width = geometry.width
                height = geometry.height
            }
            // Keep the content entirely inside a small, non-modal window.
            // Moving this window (rather than a child translation) avoids clipping
            // its resize handles at the old window boundary.
            window.attributes = window.attributes.apply {
                width = geometry.width + 2 * inset
                height = geometry.height + 2 * inset
                gravity = Gravity.CENTER
                x = geometry.offsetX.roundToInt()
                y = geometry.offsetY.roundToInt()
                dimAmount = 0f
            }
            updateContents(geometry.width, geometry.height)
        }

        fun currentGeometry() = PanelResizeMath.Geometry(
            shell.width.takeIf { it > 0 }
                ?: (shell.layoutParams as FrameLayout.LayoutParams).width,
            shell.height.takeIf { it > 0 }
                ?: (shell.layoutParams as FrameLayout.LayoutParams).height,
            window.attributes.x.toFloat(), window.attributes.y.toFloat())

        fun fitToScreen(geometry: PanelResizeMath.Geometry): PanelResizeMath.Geometry =
            PanelResizeMath.resized(geometry, 0f, 0f, 0, false,
                minWidth(), maxWidth(), minHeight(), maxHeight(),
                screenWidth(), screenHeight(), inset)

        fun saveState() {
            val geom = fitToScreen(currentGeometry())
            prefs.edit()
                .putInt("widthDp", (geom.width / density).roundToInt())
                .putInt("heightDp", (geom.height / density).roundToInt())
                .putInt("offsetXDp", (geom.offsetX / density).roundToInt())
                .putInt("offsetYDp", (geom.offsetY / density).roundToInt())
                .apply()
        }

        fun defaultGeometry() = fitToScreen(PanelResizeMath.Geometry(
            minOf(dp(280), maxWidth()),
            minOf(dp(300), maxOf((screenHeight() * 0.40f).roundToInt(), minHeight())),
            0f, 0f
        ))
        val saved = PanelResizeMath.Geometry(
            prefs.getInt("widthDp", 0).takeIf { it > 0 }?.let { dp(it) }
                ?: defaultGeometry().width,
            prefs.getInt("heightDp", 0).takeIf { it > 0 }?.let { dp(it) }
                ?: defaultGeometry().height,
            dp(prefs.getInt("offsetXDp", 0)).toFloat(),
            dp(prefs.getInt("offsetYDp", 0)).toFloat()
        )
        applySizeAndPosition(fitToScreen(saved))

        findViewById<Button>(R.id.resetPanelSizeButton).setOnClickListener {
            applySizeAndPosition(defaultGeometry())
            shell.post { saveState() }
        }

        // Re-clamp on resume after system insets, rotation or split-screen change.
        shell.post { applySizeAndPosition(fitToScreen(currentGeometry())) }

        @Suppress("ClickableViewAccessibility")
        fun attachResizeHandle(id: Int, direction: Int, vertical: Boolean) {
            val handle = findViewById<View>(id)
            var startX = 0f
            var startY = 0f
            var start = defaultGeometry()
            handle.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        start = currentGeometry()
                        shell.parent.requestDisallowInterceptTouchEvent(true)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val newGeometry = PanelResizeMath.resized(
                            start, event.rawX - startX, event.rawY - startY,
                            direction, vertical, minWidth(), maxWidth(),
                            minHeight(), maxHeight(), screenWidth(), screenHeight(), inset
                        )
                        applySizeAndPosition(newGeometry)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        saveState()
                        shell.parent.requestDisallowInterceptTouchEvent(false)
                        true
                    }
                    else -> true
                }
            }
        }
        attachResizeHandle(R.id.resizeRightHandle, 1, false)
        attachResizeHandle(R.id.resizeBottomHandle, 0, true)
        attachResizeHandle(R.id.resizeCornerHandle, 1, true)
        attachResizeHandle(R.id.resizeBottomLeftHandle, -1, true)

        // Dedicated drag handle to move the panel without changing its size.
        val moveHandle = findViewById<View>(R.id.panelMoveHandle)
        var moveStartX = 0f
        var moveStartY = 0f
        var moveInitial = defaultGeometry()
        moveHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    moveStartX = event.rawX
                    moveStartY = event.rawY
                    moveInitial = currentGeometry()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val moved = PanelResizeMath.moved(moveInitial,
                        event.rawX - moveStartX, event.rawY - moveStartY,
                        screenWidth(), screenHeight(), inset)
                    applySizeAndPosition(moved)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    saveState()
                    true
                }
                else -> true
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            // Outside touches pass through to the other app and also dismiss
            // FloatSaver so the floating bubble remains readily accessible.
            finish()
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val backdrop = findViewById<FrameLayout>(R.id.panelBackdrop)
        val compactCard = findViewById<ScrollView>(R.id.panelCard)
        val shell = findViewById<FrameLayout>(R.id.panelShell)
        setupResizablePanel(shell)
        backdrop.setOnClickListener { finish() }
        shell.setOnClickListener { /* Taps inside the panel must not close it. */ }
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
                    knownVideoCandidate = false
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
        limitSpinner.adapter = scaledAdapter(MediaEngine.SIZE_LIMIT_LABELS.toList())
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
        updateUrlCaptureStatus()
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
            knownVideoCandidate = incoming?.getBooleanExtra("sourceIsVideo", false) == true
            analyze()
        } else if (incoming?.getBooleanExtra("captureRequested", false) == true) {
            getBrowserUrlWithoutRedirect()
        }
        // A launcher can reuse its Intent after returning from Recents.
        // Consume each hand-off once instead of reusing an old page URL.
        incoming?.removeExtra("sourceUrl")
        incoming?.removeExtra("sourceReferer")
        incoming?.removeExtra("sourceUserAgent")
        incoming?.removeExtra("sourceIsVideo")
        incoming?.removeExtra("captureRequested")
        incoming?.removeExtra("autoPaste")
        if (incoming?.action == Intent.ACTION_SEND) {
            incoming.removeExtra(Intent.EXTRA_TEXT)
            incoming.action = null
        }
    }

    private fun getBrowserUrlWithoutRedirect() {
        if (!BravePageUrlService.isEnabled(this)) {
            statusText.text = "自動取得は未許可です。ユーザー補助でFloatSaverを有効にするか、ブラウザから「共有 → FloatSaver」を選んでください。"
            updateUrlCaptureStatus()
            return
        }
        val freshUrl = BravePageUrlService.latestUrl()
        if (freshUrl == null) {
            statusText.text = "現在のブラウザURLが検出されません。URL欄を表示して戻るか、ブラウザから「共有 → FloatSaver」を選んでください。以前コピーしたURLは自動では使いません。"
            return
        }
        urlInput.setText(freshUrl)
        analyze()
    }

    private fun updateUrlCaptureStatus() {
        if (!::urlInput.isInitialized) return
        findViewById<TextView>(R.id.urlCaptureStatus).text =
            if (BravePageUrlService.isEnabled(this)) {
                "自動取得：許可済み。URLを取得できない場合は「共有」を使用"
            } else {
                "自動取得：未許可（任意）。共有・URLコピーなら許可不要"
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
        } else if (url == urlInput.text.toString().trim()) {
            statusText.text = "クリップボードは前回と同じURLです。別のページをコピーするか、「動画を調べる」で再解析できます。"
        } else {
            referer = null
            userAgent = null
            knownVideoCandidate = false
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
        qualitySpinner.adapter = scaledAdapter(
            if (activeChoices.isEmpty()) listOf("該当する画質がありません") else activeChoices)
        val selected = activeChoices.indexOfFirst { it.formatSelector == previousSelection }
        if (selected >= 0) qualitySpinner.setSelection(selected)
        val gif = details?.isGif == true
        containerSpinner.adapter = scaledAdapter(
            when {
                gif -> listOf("GIF（オリジナル）")
                details?.qualities?.any { it.formatSelector == "direct" || it.formatSelector.startsWith("direct:") } == true ->
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
        val videoHint = knownVideoCandidate
        Thread {
            val outcome = runCatching {
                MediaEngine.analyze(applicationContext, url, headerReferer, headerAgent, videoHint)
            }
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
