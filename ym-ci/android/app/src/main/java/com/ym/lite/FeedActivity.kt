package com.ym.lite

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import kotlin.math.abs

class FeedActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("ym_local", MODE_PRIVATE) }
    private val videos = mutableListOf<Uri>()
    private var comments = emptyList<String>()
    private var index = 0

    private lateinit var video: VideoView
    private lateinit var counter: TextView
    private lateinit var caption: TextView
    private lateinit var commentHint: TextView
    private lateinit var likeButton: TextView
    private lateinit var saveButton: TextView
    private var liked = false
    private var saved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreData()
        if (videos.isEmpty()) {
            Toast.makeText(this, "مكتبة YM فارغة", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContentView(buildScreen())
        showCurrent()
    }

    private fun restoreData() {
        val raw = prefs.getString("wheel_uris", "[]").orEmpty()
        runCatching {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                a.optString(i).takeIf { it.isNotBlank() }?.let { videos += Uri.parse(it) }
            }
        }
        comments = prefs.getString("comment_pool", "").orEmpty()
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        index = prefs.getInt("feed_index", 0).coerceAtLeast(0)
        if (videos.isNotEmpty()) index %= videos.size
    }

    private fun buildScreen(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        video = VideoView(this).apply {
            setBackgroundColor(Color.BLACK)
            setOnPreparedListener { mp ->
                mp.isLooping = true
                start()
            }
        }
        root.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(topTab("يتابع", false))
            addView(topTab("لك", true))
        }
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(54), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(18)
        })

        counter = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(Color.argb(120, 0, 0, 0))
        }
        root.addView(counter, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(26)
            marginStart = dp(14)
        })

        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(railButton("YM") { finish() })
            likeButton = railButton("♡\nإعجاب") {
                liked = !liked
                likeButton.text = if (liked) "♥\nإعجاب" else "♡\nإعجاب"
            }
            addView(likeButton)
            addView(railButton("●\nتعليق") { copySuggestedComment() })
            saveButton = railButton("☆\nحفظ") {
                saved = !saved
                saveButton.text = if (saved) "★\nمحفوظ" else "☆\nحفظ"
            }
            addView(saveButton)
            addView(railButton("↗\nمشاركة") { shareCurrent() })
        }
        root.addView(rail, FrameLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = dp(6)
        })

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(88), dp(92))
            addView(TextView(this@FeedActivity).apply {
                text = prefs.getString("selected_account_label", "@YM").orEmpty().ifBlank { "@YM" }
                setTextColor(Color.WHITE)
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
            })
            caption = TextView(this@FeedActivity).apply {
                setTextColor(Color.WHITE)
                textSize = 15f
                maxLines = 3
                setPadding(0, dp(6), 0, dp(6))
            }
            addView(caption)
            commentHint = TextView(this@FeedActivity).apply {
                setTextColor(Color.rgb(37, 244, 238))
                textSize = 14f
                maxLines = 2
            }
            addView(commentHint)
        }
        root.addView(info, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(210, 0, 0, 0))
            addView(bottomItem("⌂\nالرئيسية"), weight())
            addView(bottomItem("⌕\nاكتشف"), weight())
            addView(bottomItem("＋\nالمكتبة").apply { setOnClickListener { finish() } }, weight())
            addView(bottomItem("◷\nالسجل"), weight())
            addView(bottomItem("◉\nأنا"), weight())
        }
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72), Gravity.BOTTOM))

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val start = e1 ?: return false
                val dy = e2.y - start.y
                if (abs(dy) < dp(70)) return false
                if (dy < 0) next() else previous()
                return true
            }
        })
        val touch = View.OnTouchListener { _, event -> detector.onTouchEvent(event) }
        root.setOnTouchListener(touch)
        video.setOnTouchListener(touch)
        return root
    }

    private fun showCurrent() {
        if (videos.isEmpty()) return
        index = ((index % videos.size) + videos.size) % videos.size
        prefs.edit().putInt("feed_index", index).apply()
        liked = false
        saved = false
        likeButton.text = "♡\nإعجاب"
        saveButton.text = "☆\nحفظ"
        counter.text = "${index + 1}/${videos.size}"
        caption.text = prefs.getString("caption", "").orEmpty().ifBlank { "من مكتبة YM" }
        commentHint.text = if (comments.isEmpty()) {
            "دولاب التعليقات فارغ"
        } else {
            "التعليق المقترح: ${comments[index % comments.size]}"
        }
        runCatching {
            video.stopPlayback()
            video.setVideoURI(videos[index])
            video.start()
        }.onFailure {
            Toast.makeText(this, "تعذر تشغيل هذا الفيديو", Toast.LENGTH_SHORT).show()
        }
    }

    private fun next() {
        index = (index + 1) % videos.size
        showCurrent()
    }

    private fun previous() {
        index = if (index == 0) videos.lastIndex else index - 1
        showCurrent()
    }

    private fun copySuggestedComment() {
        if (comments.isEmpty()) {
            Toast.makeText(this, "أضف تعليقات إلى الدولاب أولًا", Toast.LENGTH_SHORT).show()
            return
        }
        val text = comments[index % comments.size]
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("YM comment", text))
        Toast.makeText(this, "تم نسخ التعليق المقترح", Toast.LENGTH_SHORT).show()
    }

    private fun shareCurrent() {
        val uri = videos.getOrNull(index) ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = contentResolver.getType(uri) ?: "video/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "مشاركة الفيديو"))
    }

    private fun topTab(textValue: String, selected: Boolean) = TextView(this).apply {
        text = textValue
        setTextColor(if (selected) Color.WHITE else Color.LTGRAY)
        textSize = if (selected) 17f else 16f
        if (selected) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(14), 0, dp(14), 0)
    }

    private fun railButton(textValue: String, action: () -> Unit) = TextView(this).apply {
        text = textValue
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(10), dp(4), dp(10))
        setOnClickListener { action() }
    }

    private fun bottomItem(textValue: String) = TextView(this).apply {
        text = textValue
        setTextColor(Color.WHITE)
        textSize = 12f
        gravity = Gravity.CENTER
    }

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onPause() {
        if (::video.isInitialized && video.isPlaying) video.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::video.isInitialized && videos.isNotEmpty()) runCatching { video.start() }
    }

    override fun onDestroy() {
        if (::video.isInitialized) video.stopPlayback()
        super.onDestroy()
    }
}
