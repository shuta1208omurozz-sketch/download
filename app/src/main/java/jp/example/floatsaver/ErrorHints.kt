package jp.example.floatsaver

import java.util.Locale

/** No raw exceptions/URLs in history or notifications: signed URLs may contain secrets. */
object ErrorHints {
    fun describe(error: Throwable): String {
        val message = generateSequence(error) { it.cause }.take(3)
            .joinToString(" ") { it.message.orEmpty() }.lowercase(Locale.ROOT)
        return when {
            "403" in message || "forbidden" in message -> "アクセスが拒否されました（403）。期限切れURLやログイン制限の可能性があります。"
            "404" in message || "not found" in message -> "動画が見つかりません（404）。URLを確認してください。"
            "429" in message || "too many requests" in message -> "アクセスが集中しています（429）。時間をおいてやり直してください。"
            "timed out" in message || "timeout" in message -> "通信がタイムアウトしました。回線を確認して再試行してください。"
            "no space" in message || "enospc" in message -> "端末の空き容量が不足しています。"
            "動画ではないレスポンス" in message -> "動画ではないページが返されました。再生画面から候補を探してください。"
            "直リンクは元の形式" in message -> "動画ファイルのURLでは元の形式を選んでください。"
            "unsupported url" in message || "no video formats" in message || "not available" in message ->
                "ページのURLだけでは動画を検出できません。内蔵ブラウザでページを開き、動画を再生してから検出してください。"
            "login" in message || "sign in" in message || "private" in message -> "この動画にはログインや閲覧権限が必要な可能性があります。"
            "drm" in message || "encrypted" in message -> "保護された動画には対応していません。"
            else -> "解析または保存に失敗しました。URL・通信環境・保存形式を確認してください。"
        }
    }
}
