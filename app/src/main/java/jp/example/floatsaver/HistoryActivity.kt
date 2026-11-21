package jp.example.floatsaver

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : Activity() {
    private lateinit var list: LinearLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DownloadService.active) {
            HistoryStore(this).use { it.markInterrupted() }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.WHITE)
        }
        val title = TextView(this).apply {
            text = "ダウンロード履歴"
            textSize = 23f
            setTextColor(Color.rgb(21, 35, 57))
        }
        root.addView(title)
        root.addView(Button(this).apply {
            text = "最新の状態に更新"
            setOnClickListener { refresh() }
        })
        root.addView(Button(this).apply {
            text = "履歴を削除（保存したファイルは残ります）"
            setOnClickListener {
                AlertDialog.Builder(this@HistoryActivity)
                    .setTitle("履歴を削除しますか？")
                    .setMessage("保存した動画・GIF自体は削除されません。")
                    .setNegativeButton("キャンセル", null)
                    .setPositiveButton("削除") { _, _ ->
                        HistoryStore(this@HistoryActivity).use { it.clearHistory() }
                        refresh()
                    }.show()
            }
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            addView(list)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        val entries = HistoryStore(this).use { it.all() }
        if (entries.isEmpty()) {
            list.addView(TextView(this).apply { text = "履歴はまだありません。保存後にここへ表示されます。" })
        } else {
            val complete = entries.count { it.state == "完了" }
            list.addView(TextView(this).apply {
                text = "最近の履歴 ${entries.size}件（保存完了 $complete 件）\n完了はタップで開く／失敗・中断はタップで再解析"
                textSize = 13f
                setPadding(4, 12, 4, 12)
                setTextColor(Color.rgb(65, 80, 103))
            })
        }
        val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN)
        entries.forEach { item ->
            list.addView(TextView(this).apply {
                val size = item.bytes?.let { sizeLabel(it) } ?: "容量不明"
                text = "${item.title}\n${item.state} ・ ${item.format.uppercase()} ・ $size\n" +
                    "${dateFormat.format(Date(item.createdAt))}" +
                    (item.error?.let { "\n${it.take(150)}" } ?: "")
                setPadding(12, 17, 12, 17)
                setTextColor(Color.rgb(20, 43, 80))
                setBackgroundColor(Color.rgb(244, 247, 251))
                setOnClickListener {
                    val output = item.outputUri
                    if (output != null) {
                        val uri = Uri.parse(output)
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            })
                        } catch (_: Exception) {
                            Toast.makeText(this@HistoryActivity, "ファイルが見つからないか、開けません", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        startActivity(Intent(this@HistoryActivity, MainActivity::class.java).apply {
                            putExtra("sourceUrl", item.source)
                        })
                    }
                }
            })
            list.addView(TextView(this).apply { text = " " })
        }
    }
}
