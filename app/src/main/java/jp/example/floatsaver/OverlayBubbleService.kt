package jp.example.floatsaver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs

/** The bubble and the full control panel are real Android overlay windows. */
class OverlayBubbleService : Service() {
    companion object { @Volatile var running = false }
    private var bubble: TextView? = null
    private var panel: View? = null
    private var manager: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var panelRequest = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate() {
        super.onCreate()
        val channelId = "float_saver_bubble"
        val notifications = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(NotificationChannel(channelId, "フローティングボタン",
            NotificationManager.IMPORTANCE_LOW))
        val pending = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("FloatSaver")
            .setContentText("フローティング操作が有効です")
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
        startForeground(102, notification)
        if (Settings.canDrawOverlays(this)) showBubble() else stopSelf()
    }

    @Suppress("ClickableViewAccessibility")
    private fun showBubble() {
        if (bubble != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        manager = wm
        val size = dp(56)
        val view = TextView(this).apply {
            text = "↓"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            elevation = dp(10).toFloat()
            background = rounded(Color.rgb(36, 96, 227), 100)
            contentDescription = "FloatSaverの操作パネルを開く"
        }
        val params = WindowManager.LayoutParams(size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - dp(14)
            y = resources.displayMetrics.heightPixels / 3
        }
        var lastX = 0f
        var lastY = 0f
        var distance = 0f
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    distance = 0f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    distance += abs(dx) + abs(dy)
                    params.x = (params.x + dx.toInt()).coerceIn(0, resources.displayMetrics.widthPixels - size)
                    params.y = (params.y + dy.toInt()).coerceIn(0, resources.displayMetrics.heightPixels - size)
                    runCatching { wm.updateViewLayout(view, params) }
                    lastX = event.rawX
                    lastY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (distance < dp(14)) {
                        if (panel == null) showPanel() else closePanel()
                    }
                    true
                }
                else -> false
            }
        }
        // Android can revoke the overlay permission while the app is running.
        // WindowManager.addView then throws: stop cleanly instead of crashing.
        if (runCatching { wm.addView(view, params) }.isFailure) {
            stopSelf()
            return
        }
        bubble = view
        running = true
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radius).toFloat()
        setColor(color)
    }

    private fun addText(root: LinearLayout, value: String, size: Float = 14f): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.rgb(31, 50, 82))
            setPadding(dp(2), dp(5), dp(2), dp(5))
            root.addView(this)
        }
    }

    private fun addButton(root: LinearLayout, value: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = value
            setOnClickListener { action() }
            root.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun showPanel() {
        val wm = manager ?: return
        if (panel != null) return
        ++panelRequest
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(Color.WHITE, 18)
        }
        addText(root, "FloatSaver　かんたん保存", 19f)
        addText(root, "1　ページのURLを指定", 15f)
        val input = EditText(this).apply {
            hint = "https://...（Braveから共有も可能）"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            minHeight = dp(48)
        }
        root.addView(input)
        val status = addText(root, "URLを貼り付けて「動画を調べる」を押してください。", 12f)
        var info: MediaDetails? = null
        var choices = emptyList<QualityChoice>()
        var analyzedUrl = ""
        var pendingUrl = ""
        val advanced = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        addText(advanced, "2　画質・保存形式を選択", 15f)
        val title = addText(advanced, "動画情報", 14f)
        val duration = addText(advanced, "再生時間: ー", 12f)
        addText(advanced, "容量の目安（任意）", 13f)
        val limit = Spinner(this).apply {
            adapter = ArrayAdapter(this@OverlayBubbleService,
                android.R.layout.simple_spinner_dropdown_item, MediaEngine.SIZE_LIMIT_LABELS)
            minimumHeight = dp(48)
        }
        advanced.addView(limit)
        addText(advanced, "画質", 13f)
        val quality = Spinner(this).apply { minimumHeight = dp(48) }
        advanced.addView(quality)
        addText(advanced, "保存形式", 13f)
        val container = Spinner(this).apply { minimumHeight = dp(48) }
        advanced.addView(container)
        val sizeNote = addText(advanced, "サイズは推定値の場合があります。", 12f)
        root.addView(advanced)
        lateinit var save: Button
        fun updateOptions() {
            val oldSelector = choices.getOrNull(quality.selectedItemPosition)?.formatSelector
            val maxBytes = MediaEngine.SIZE_LIMITS_MB.getOrNull(limit.selectedItemPosition)?.times(1_000_000L)
            choices = info?.qualities?.filter {
                maxBytes == null || it.estimatedBytes == null || it.estimatedBytes <= maxBytes
            }.orEmpty()
            quality.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                if (choices.isEmpty()) listOf("該当する画質なし") else choices)
            val restored = choices.indexOfFirst { it.formatSelector == oldSelector }
            if (restored >= 0) quality.setSelection(restored)
            container.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                if (info?.isGif == true) listOf("GIF（元の形式）") else MediaEngine.VIDEO_CONTAINERS)
            save.isEnabled = choices.isNotEmpty()
            val item = choices.getOrNull(quality.selectedItemPosition)
            sizeNote.text = if (item == null) "容量条件を緩めるか「制限なし」を選択してください。" else
                "元データ: ${sizeLabel(item.estimatedBytes, item.isApproximate)}。容量不明の場合は上限を確認できません。"
        }
        val firstRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val paste = Button(this).apply {
            text = "URLを貼り付け"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val content = runCatching {
                    clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)?.coerceToText(this@OverlayBubbleService)?.toString().orEmpty()
                }.getOrDefault("")
                val url = findHttpUrl(content)
                if (url == null) {
                    status.text = "コピーを読めません。Braveの「共有 → FloatSaver」をお試しください。"
                } else input.setText(url)
            }
        }
        firstRow.addView(paste, LinearLayout.LayoutParams(0, dp(48), 1f))
        val analyze = Button(this).apply { text = "動画を調べる" }
        firstRow.addView(analyze, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(firstRow, root.indexOfChild(status))
        addText(root, "3　保存する", 15f)
        save = Button(this).apply {
            text = "ダウンロードを開始"
            isEnabled = false
            setOnClickListener {
                val data = info
                val chosen = choices.getOrNull(quality.selectedItemPosition)
                when {
                    data == null || chosen == null -> status.text = "先に動画を調べてください。"
                    input.text.toString().trim() != analyzedUrl -> status.text = "URLが変わりました。再解析してください。"
                    else -> {
                        val output = if (data.isGif) MediaEngine.CONTAINER_GIF else
                            MediaEngine.CONTAINER_KEYS.getOrElse(container.selectedItemPosition) {
                                MediaEngine.CONTAINER_ORIGINAL
                            }
                        runCatching {
                            DownloadService.start(this@OverlayBubbleService, analyzedUrl,
                                chosen.formatSelector, data.isGif, output, data.title)
                        }.onSuccess {
                            status.text = "保存を受け付けました。進捗は通知と履歴で確認できます。"
                            isEnabled = false // prevent double-tap
                        }.onFailure { status.text = ErrorHints.describe(it) }
                    }
                }
            }
        }
        root.addView(save, LinearLayout.LayoutParams(-1, dp(54)))
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val changed = s.toString() != analyzedUrl && s.toString() != pendingUrl
                if (changed && (info != null || analyzedUrl.isNotBlank() || pendingUrl.isNotBlank())) {
                    ++panelRequest // discard old result if URL changes mid-request
                    info = null
                    analyzedUrl = ""
                    pendingUrl = ""
                    advanced.visibility = View.GONE
                    save.isEnabled = false
                    analyze.isEnabled = true
                    status.text = "URLを変更しました。「動画を調べる」を押してください。"
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        analyze.setOnClickListener {
            val url = input.text.toString().trim()
            if (runCatching { MediaEngine.validatedUrl(url) }.isFailure) {
                status.text = "http/https のURLを入力してください。"
                return@setOnClickListener
            }
            info = null
            advanced.visibility = View.GONE
            save.isEnabled = false
            analyzedUrl = ""
            pendingUrl = url
            status.text = "動画・GIFを確認しています…"
            analyze.isEnabled = false
            val job = ++panelRequest
            Thread {
                val outcome = runCatching { MediaEngine.analyze(applicationContext, url) }
                handler.post {
                    if (panel != null && panelRequest == job) {
                        analyze.isEnabled = true
                        pendingUrl = ""
                        outcome.onSuccess { data ->
                            info = data
                            analyzedUrl = url
                            title.text = data.title
                            duration.text = data.durationSeconds?.let { t ->
                                val sec = t.toLong()
                                "再生時間: ${"%02d:%02d:%02d".format(Locale.JAPAN,
                                    sec / 3600, sec % 3600 / 60, sec % 60)}"
                            } ?: "再生時間: 不明（GIFは取得不可の場合あり）"
                            advanced.visibility = View.VISIBLE
                            updateOptions()
                            status.text = "見つかりました。画質を選んで保存してください。"
                        }.onFailure { error -> status.text = ErrorHints.describe(error) }
                    }
                }
            }.start()
        }
        limit.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateOptions()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        quality.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val item = choices.getOrNull(position)
                if (item != null) sizeNote.text =
                    "元データ: ${sizeLabel(item.estimatedBytes, item.isApproximate)}（変換後は変わる場合があります）"
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        addButton(root, "内蔵ブラウザで動画を自動検出") {
            runCatching {
                startActivity(Intent(this, BrowserActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    input.text.toString().takeIf { it.startsWith("http://") || it.startsWith("https://") }
                        ?.let { putExtra(BrowserActivity.KEY_PAGE, it) }
                })
                closePanel()
            }.onFailure { status.text = "アプリ本体からブラウザを開いてください。" }
        }
        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val history = Button(this).apply {
            text = "保存履歴"
            setOnClickListener {
                runCatching {
                    startActivity(Intent(this@OverlayBubbleService, HistoryActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                    closePanel()
                }.onFailure { status.text = "アプリ本体から履歴を開いてください。" }
            }
        }
        footer.addView(history, LinearLayout.LayoutParams(0, dp(48), 1f))
        val close = Button(this).apply {
            text = "閉じる"
            setOnClickListener { closePanel() }
        }
        footer.addView(close, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(footer)
        val scroll = ScrollView(this).apply { addView(root) }
        val params = WindowManager.LayoutParams(
            resources.displayMetrics.widthPixels - dp(24),
            (resources.displayMetrics.heightPixels * 0.75f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        if (runCatching { wm.addView(scroll, params) }.isFailure) {
            panelRequest++
            return
        }
        panel = scroll
    }

    private fun closePanel() {
        panelRequest++
        panel?.let { view -> runCatching { manager?.removeView(view) } }
        panel = null
    }

    override fun onDestroy() {
        closePanel()
        bubble?.let { runCatching { manager?.removeView(it) } }
        bubble = null
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
