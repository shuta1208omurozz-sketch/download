package jp.example.floatsaver

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.io.IOException
import java.net.URL
import java.util.Locale
import kotlin.math.roundToLong

data class QualityChoice(
    val label: String,
    val formatSelector: String,
    val estimatedBytes: Long?,
    val isApproximate: Boolean = false
) {
    override fun toString(): String = "$label ・ ${sizeLabel(estimatedBytes, isApproximate)}"
}

fun sizeLabel(bytes: Long?, approximate: Boolean = false): String = when (bytes) {
    null -> "容量不明"
    else -> "${if (approximate) "約" else ""}${"%.1f".format(Locale.JAPAN, bytes / 1_000_000.0)} MB"
}

data class MediaDetails(
    val title: String,
    val durationSeconds: Double?,
    val qualities: List<QualityChoice>,
    val isGif: Boolean
)

data class SaveResult(val uri: Uri, val bytes: Long, val fileName: String)

object MediaEngine {
    const val CONTAINER_ORIGINAL = "original"
    const val CONTAINER_GIF = "gif"
    val VIDEO_CONTAINERS = listOf("元の形式（自動）", "MP4", "WebM", "MKV")
    val CONTAINER_KEYS = listOf(CONTAINER_ORIGINAL, "mp4", "webm", "mkv")
    val SIZE_LIMITS_MB = listOf(null, 25L, 50L, 100L, 250L, 500L, 1000L)
    val SIZE_LIMIT_LABELS = listOf("制限なし", "25 MB以下の目安", "50 MB以下の目安",
        "100 MB以下の目安", "250 MB以下の目安", "500 MB以下の目安", "1000 MB以下の目安")

    @Volatile private var ready = false
    private val updaterLock = Any()
    private fun updateExtractorIfNeeded(context: Context): Boolean {
        val prefs = context.getSharedPreferences("floatsaver_extractor", Context.MODE_PRIVATE)
        synchronized(updaterLock) {
            val now = System.currentTimeMillis()
            if (now - prefs.getLong("lastUpdateAttempt", 0L) < 86_400_000L) return false
            prefs.edit().putLong("lastUpdateAttempt", now).apply()
            return runCatching {
                YoutubeDL.getInstance().updateYoutubeDL(
                    context.applicationContext, YoutubeDL.UpdateChannel.STABLE)
                true
            }.getOrDefault(false)
        }
    }

    private fun analyzeWithRetry(context: Context, request: YoutubeDLRequest): JSONObject {
        fun read(): JSONObject = JSONObject(YoutubeDL.getInstance().execute(request).out.trim())
        return try { read() } catch (first: Exception) {
            val msg = first.message.orEmpty().lowercase(Locale.ROOT)
            val mayBeOutdated = listOf("signature", "nsig", "unable to extract",
                "unable to download", "player response", "youtube").any { it in msg }
            if (!mayBeOutdated || !updateExtractorIfNeeded(context)) throw first
            read()
        }
    }

    fun initialize(context: Context) {
        if (!ready) synchronized(this) {
            if (!ready) {
                YoutubeDL.getInstance().init(context.applicationContext)
                FFmpeg.getInstance().init(context.applicationContext)
                ready = true
            }
        }
    }

    /** Remove orphaned staging files left by a crash; never remove recent in-progress files. */
    fun cleanupOldStaging(context: Context) {
        val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "staging")
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        root.listFiles()?.take(100)?.forEach { child ->
            if (child.isDirectory && child.lastModified() < cutoff) {
                runCatching { child.deleteRecursively() }
            }
        }
    }

    fun validatedUrl(source: String): String {
        val value = source.trim()
        val uri = Uri.parse(value)
        require(uri.scheme?.lowercase(Locale.ROOT) in listOf("https", "http") && !uri.host.isNullOrBlank()) {
            "http/https のURLを入力してください"
        }
        return value
    }

    fun isDirectGif(url: String): Boolean = DetectionRules.kind(url) == "GIF"

    private data class GifProbe(val bytes: Long?)

    // Some sites serve GIFs from extensionless endpoints. HEAD is advisory only:
    // servers that reject HEAD still work through the standard extractor.
    private fun probeGif(url: String, referer: String?, userAgent: String?): GifProbe? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "HEAD"
        connection.connectTimeout = 4000
        connection.readTimeout = 4000
        applyHeaders(connection, referer, userAgent)
        try {
            if (connection.responseCode in 200..299 &&
                connection.contentType.orEmpty().substringBefore(';').trim().equals("image/gif", true)) {
                GifProbe(connection.contentLengthLong.takeIf { it > 0 })
            } else null
        } finally { connection.disconnect() }
    }.getOrNull()

    fun analyze(context: Context, source: String, referer: String? = null, userAgent: String? = null): MediaDetails {
        val url = validatedUrl(source)
        // Avoid an unnecessary HEAD request for URLs already known to be video/stream.
        // Some servers stall or reject HEAD, delaying every analysis by several seconds.
        val hint = DetectionRules.kind(url)
        val gifProbe = if (hint == "動画" || hint == "配信") null
            else probeGif(url, referer, userAgent)
        if (isDirectGif(url) || gifProbe != null) {
            val rawName = Uri.parse(url).lastPathSegment?.take(100).orEmpty()
            val name = rawName.takeIf { it.endsWith(".gif", true) } ?: "animation.gif"
            return MediaDetails(name, null,
                listOf(QualityChoice("GIF（元の画質）", "gif", gifProbe?.bytes)), true)
        }
        initialize(context)
        val request = YoutubeDLRequest(url)
        request.addOption("--dump-single-json")
        request.addOption("--skip-download")
        request.addOption("--no-playlist")
        request.addOption("--socket-timeout", "20")
        request.addOption("--retries", "2")
        addHeaders(request, referer, userAgent)
        val json = analyzeWithRetry(context, request)
        if (!json.has("url") && (json.optJSONArray("formats")?.length() ?: 0) == 0) {
            error("no video formats")
        }
        val title = json.optString("title").ifBlank { "動画" }.take(180)
        val duration = json.optDouble("duration").takeIf { it.isFinite() && it > 0 }
        val formats = json.optJSONArray("formats")
        // Group by resolution AND container. A single entry per height hid useful
        // 720p MP4/WebM alternatives and their different estimated sizes.
        val candidates = mutableMapOf<Pair<Int, String>, Pair<Double, QualityChoice>>()
        if (formats != null) {
            // Best audio is usually NOT the smallest audio stream; minimum caused
            // systematic underestimates for video-only formats.
            val audio = (0 until formats.length()).mapNotNull { formats.optJSONObject(it) }
                .filter { it.optString("vcodec", "none") == "none" &&
                    it.optString("acodec", "none") != "none" }
                .maxByOrNull {
                    it.optDouble("abr", 0.0).takeIf { n -> n.isFinite() && n > 0 }
                        ?: it.optDouble("tbr", 0.0).takeIf { n -> n.isFinite() && n > 0 }
                        ?: 0.0
                }
            val likelyAudio = audio?.let { estimateSize(it, duration)?.first }
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                val height = f.optInt("height").takeIf { it > 0 }
                    ?: (f.optString("resolution").substringAfterLast('x').toIntOrNull() ?: 0)
                val id = f.optString("format_id")
                if (height <= 0 || id.isBlank() || f.optString("vcodec", "none") == "none") continue
                val hasAudio = f.optString("acodec", "none") != "none"
                val ext = f.optString("ext")
                val (videoBytes, approx) = estimateSize(f, duration) ?: (null to true)
                val total = when {
                    hasAudio -> videoBytes
                    videoBytes != null && likelyAudio != null -> videoBytes + likelyAudio
                    else -> null // Never label video-only bytes as the whole output size.
                }
                val estimate = approx || !hasAudio
                val selector = if (hasAudio) id else "$id+bestaudio/$id"
                val extension = ext.lowercase(Locale.ROOT).ifBlank { "その他" }
                val choice = QualityChoice("${height}p (${extension})", selector, total, estimate)
                val bitrate = f.optDouble("tbr", 0.0).takeIf { it.isFinite() && it > 0 } ?: 0.0
                val score = bitrate + (if (hasAudio) 0.01 else 0.0)
                val key = height to extension
                if ((candidates[key]?.first ?: -1.0) < score) candidates[key] = score to choice
            }
        }
        val choices = candidates.entries
            .sortedWith(compareByDescending<Map.Entry<Pair<Int, String>, Pair<Double, QualityChoice>>> { it.key.first }
                .thenBy { if (it.key.second == "mp4") 0 else 1 }
                .thenBy { it.key.second })
            .take(24)
            .map { it.value.second }.toMutableList()
        if (choices.isNotEmpty()) {
            val bestSize = estimateSize(json, duration)
            choices.add(0, QualityChoice("最高画質（自動）", "bv*+ba/b", bestSize?.first, true))
        }
        if (choices.isEmpty()) {
            val guess = estimateSize(json, duration)
            choices.add(QualityChoice("最高画質（自動）", "bv*+ba/b", guess?.first, guess?.second ?: true))
        }
        return MediaDetails(title, duration, choices, false)
    }

    private fun estimateSize(obj: JSONObject, duration: Double?): Pair<Long?, Boolean>? {
        obj.optLong("filesize", -1).takeIf { it > 0 }?.let { return it to false }
        obj.optLong("filesize_approx", -1).takeIf { it > 0 }?.let { return it to true }
        val tbr = obj.optDouble("tbr", Double.NaN)
        if (duration != null && duration.isFinite() && tbr.isFinite() && tbr > 0) {
            return (duration * tbr * 1000.0 / 8.0).roundToLong() to true
        }
        return null
    }

    private fun addHeaders(request: YoutubeDLRequest, referer: String?, userAgent: String?) {
        if (referer != null && runCatching { validatedUrl(referer) }.isSuccess) {
            request.addOption("--add-header", "Referer:$referer")
        }
        if (!userAgent.isNullOrBlank()) {
            request.addOption("--add-header", "User-Agent:${userAgent.take(250)}")
        }
    }

    private fun applyHeaders(connection: HttpURLConnection, referer: String?, userAgent: String?) {
        if (referer != null && runCatching { validatedUrl(referer) }.isSuccess) {
            connection.setRequestProperty("Referer", referer)
        }
        if (!userAgent.isNullOrBlank()) connection.setRequestProperty("User-Agent", userAgent.take(250))
    }

    fun download(
        context: Context,
        source: String,
        selector: String,
        gif: Boolean,
        container: String,
        referer: String?,
        userAgent: String?,
        processId: String,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit
    ): SaveResult {
        val url = validatedUrl(source)
        val staging = File(context.getExternalFilesDir(null) ?: context.filesDir,
            "staging/${System.currentTimeMillis()}_${Thread.currentThread().id}")
        require(staging.mkdirs() || staging.isDirectory) { "作業フォルダを作成できません" }
        try {
            val file: File
            if (gif) {
                require(container == CONTAINER_GIF) { "GIFはオリジナル形式のみ対応です" }
                file = File(staging, "animation.gif")
                var lastFailure: IOException? = null
                for (attempt in 1..3) {
                    check(!isCancelled()) { "保存を中断しました" }
                    try {
                        val connection = URL(url).openConnection() as HttpURLConnection
                        connection.connectTimeout = 15000
                        connection.readTimeout = 30000
                        applyHeaders(connection, referer, userAgent)
                        try {
                            val code = connection.responseCode
                            if (code !in 200..299) {
                                // 429 / 403 / 404 should not be hammered with retries.
                                if (code in listOf(403, 404, 429)) error("HTTP $code")
                                throw IOException("HTTP $code")
                            }
                            val type = connection.contentType.orEmpty().substringBefore(';').trim()
                            require(type.equals("image/gif", true) || isDirectGif(url)) { "GIFではないURLです" }
                            val total = connection.contentLengthLong
                            connection.inputStream.use { input ->
                                file.outputStream().use { output ->
                                    val buffer = ByteArray(32 * 1024)
                                    var bytes = 0L
                                    while (true) {
                                        check(!isCancelled()) { "保存を中断しました" }
                                        val count = input.read(buffer)
                                        if (count == -1) break
                                        output.write(buffer, 0, count)
                                        bytes += count
                                        if (total > 0) onProgress((bytes * 100 / total).toInt().coerceIn(0, 99))
                                    }
                                }
                            }
                        } finally { connection.disconnect() }
                        lastFailure = null
                        break
                    } catch (e: IOException) {
                        lastFailure = e
                        if (attempt == 3) break
                        Thread.sleep(300L * attempt)
                    }
                }
                if (lastFailure != null) throw lastFailure
                // A .gif-looking URL must not silently save an HTML error page.
                val magic = ByteArray(6)
                require(file.inputStream().use { it.read(magic) } == 6 &&
                    String(magic, Charsets.US_ASCII) in listOf("GIF87a", "GIF89a")) {
                    "GIFファイルではありません"
                }
            } else {
                require(container in CONTAINER_KEYS) { "不明な保存形式です" }
                initialize(context)
                val request = YoutubeDLRequest(url)
                request.addOption("--no-playlist")
                request.addOption("--no-mtime")
                request.addOption("--socket-timeout", "20")
                request.addOption("--retries", "3")
                request.addOption("--fragment-retries", "3")
                request.addOption("-f", selector)
                addHeaders(request, referer, userAgent)
                if (container != CONTAINER_ORIGINAL) {
                    request.addOption("--merge-output-format", container)
                    request.addOption("--recode-video", container)
                }
                request.addOption("-o", File(staging, "%(title).80B_%(id)s.%(ext)s").absolutePath)
                YoutubeDL.getInstance().execute(request, processId, { progress, _, _ ->
                    onProgress(progress.toInt().coerceIn(0, 99))
                })
                val playable = setOf("mp4", "webm", "mkv", "mov", "m4v", "avi", "flv", "ts", "3gp")
                val outputs = staging.walkTopDown()
                    .filter { it.isFile && it.length() > 0 &&
                        it.extension.lowercase(Locale.ROOT) in playable }
                    .toList()
                // Don't silently publish an old intermediate MP4 as 'WebM/MKV'
                // when ffmpeg's recoding failed or produced an unexpected extension.
                file = outputs.filter {
                    container == CONTAINER_ORIGINAL || it.extension.equals(container, true)
                }.maxByOrNull { it.length() }
                    ?: error(if (container == CONTAINER_ORIGINAL) "保存された動画が見つかりません"
                        else "指定した保存形式への変換が完了しませんでした")
            }
            check(!isCancelled()) { "保存を中断しました" }
            require(file.length() > 0) { "空のファイルです" }
            val uri = publish(context, file)
            return SaveResult(uri, file.length(), file.name)
        } finally { staging.deleteRecursively() }
    }

    private fun publish(context: Context, file: File): Uri {
        val mime = when (file.extension.lowercase(Locale.ROOT)) {
            "gif" -> "image/gif"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            else -> "application/octet-stream"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name.take(180))
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/FloatSaver")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("保存先を作れません")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: error("保存先を開けません")
            val updated = resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            check(updated > 0) { "保存ファイルを公開できません" }
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
