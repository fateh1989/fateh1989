package com.ym.lite

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.ym.lite.core.LibraryStore
import com.ym.lite.ui.YmNav

class LibraryActivity : AppCompatActivity() {
    private lateinit var store: LibraryStore
    private lateinit var list: LinearLayout
    private lateinit var count: TextView

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        store.appendUris(uris)
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LibraryStore(this)
        setContentView(buildScreen())
        render()
    }

    private fun buildScreen(): FrameLayout {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(110))
        }
        scroll.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val nav = YmNav.bottomBar(this, YmNav.Tab.LIBRARY) { picker.launch(arrayOf("video/*")) }
        root.addView(nav, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72), Gravity.BOTTOM))
        return root
    }

    private fun render() {
        list.removeAllViews()
        list.addView(TextView(this).apply {
            text = "مكتبة YM"
            setTextColor(Color.WHITE)
            textSize = 28f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        list.addView(TextView(this).apply {
            text = "هذه هي خزانة المحتوى التي يستخدمها الـFeed ودولاب النشر."
            setTextColor(Color.LTGRAY)
            textSize = 14f
            setPadding(0, dp(6), 0, dp(14))
        })

        count = TextView(this).apply {
            setTextColor(Color.rgb(37, 244, 238))
            textSize = 16f
        }
        list.addView(count)

        val add = Button(this).apply {
            text = "＋ إضافة فيديوهات"
            isAllCaps = false
            setOnClickListener { picker.launch(arrayOf("video/*")) }
        }
        list.addView(add, matchWrap().apply { setMargins(0, dp(10), 0, dp(18)) })

        val uris = store.readUris()
        count.text = "${uris.size} فيديو"
        if (uris.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "المكتبة فارغة\nأضف أول فيديو وسيظهر مباشرة في Feed YM"
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                textSize = 18f
                setPadding(0, dp(80), 0, dp(80))
            })
            return
        }

        uris.forEachIndexed { index, uri ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                setBackgroundColor(Color.rgb(20, 20, 20))
            }
            card.addView(TextView(this).apply {
                text = "فيديو ${index + 1}"
                setTextColor(Color.WHITE)
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            card.addView(TextView(this).apply {
                text = displayName(uri)
                setTextColor(Color.LTGRAY)
                maxLines = 1
                textSize = 13f
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Button(this).apply {
                text = "مشاهدة"
                isAllCaps = false
                setOnClickListener {
                    store.setFeedIndex(index)
                    startActivity(Intent(this@LibraryActivity, FeedActivity::class.java))
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "حذف"
                isAllCaps = false
                setOnClickListener {
                    store.removeAt(index)
                    render()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(row)
            list.addView(card, matchWrap().apply { setMargins(0, 0, 0, dp(10)) })
        }
    }

    private fun displayName(uri: Uri): String {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty().ifBlank { "فيديو" }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) render()
    }
}
