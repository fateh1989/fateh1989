package com.ym.lite

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.ym.lite.core.LibraryStore
import com.ym.lite.ui.YmNav
import kotlin.math.abs

class FeedActivity : AppCompatActivity() {
    private lateinit var store: LibraryStore
    private val background by lazy { getSharedPreferences("ym_background", MODE_PRIVATE) }
    private val videos = mutableListOf<Uri>()
    private var comments = emptyList<String>()
    private var index = 0
    private var preloader: MediaPlayer? = null

    private lateinit var video: VideoView
    private lateinit var emptyLayer: LinearLayout
    private lateinit var infoLayer: LinearLayout
    private lateinit var counter: TextView
    private lateinit var caption: TextView
    private lateinit var commentHint: TextView
    private lateinit var accountLabel: TextView
    private lateinit var autoBadge: TextView
    private lateinit var likeButton: TextView
    private lateinit var saveButton: TextView
    private var liked = false
    private var saved = false

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        store.appendUris(uris)
        reloadData()
        renderFeed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LibraryStore(this)
        setContentView(buildScreen())
        reloadData()
        renderFeed()
    }

    private fun reloadData() {
        videos.clear()
        videos += store.readUris()
        comments = store.comments()
        index = store.feedIndex()
        if (videos.isNotEmpty()) index %= videos.size else index = 0
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

        emptyLayer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(60), dp(28), dp(110))
            addView(TextView(this@FeedActivity).apply {
                text = "YM"
                setTextColor(Color.WHITE)
                textSize = 58f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@FeedActivity).apply {
                text = "TikTok خارق بطريقتنا\nابدأ بإضافة فيديوهات إلى مكتبة YM"
                setTextColor(Color.LTGRAY)
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(22))
            })
            addView(Button(this@FeedActivity).apply {
                text = "＋ إضافة أول فيديو"
                isAllCaps = false
                setOnClickListener { picker.launch(arrayOf("video/*")) }
            })
        }
        root.addView(emptyLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(topTab("يتابع", false))
            addView(topTab("لك", true))
        }
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(54), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(16)
        })

        autoBadge = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setOnClickListener { startActivity(Intent(this@FeedActivity, AutoActivity::class.java)) }
        }
        root.addView(autoBadge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(24)
            marginStart = dp(12)
        })

        counter = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(8), dp(5), dp(8), dp(5))
            setBackgroundColor(Color.argb(120, 0, 0, 0))
        }
        root.addView(counter, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(26)
            marginEnd = dp(12)
        })

        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(railButton("YM") { startActivity(Intent(this@FeedActivity, ProfileActivity::class.java)) })
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
            addView(railButton("⚙\nYM") { startActivity(Intent(this@FeedActivity, MainActivity::class.java)) })
        }
        root.addView(rail, FrameLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = dp(4)
        })

        infoLayer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(92), dp(88))
        }
        accountLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        }
        infoLayer.addView(accountLabel)
        caption = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 3
            setPadding(0, dp(6), 0, dp(6))
        }
        infoLayer.addView(caption)
        commentHint = TextView(this).apply {
            setTextColor(Color.rgb(37, 244, 238))
            textSize = 14f
            maxLines = 2
        }
        infoLayer.addView(commentHint)
        root.addView(infoLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        val nav = YmNav.bottomBar(this, YmNav.Tab.HOME) { picker.launch(arrayOf("video/*")) }
        root.addView(nav, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72), Gravity.BOTTOM))

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (videos.isEmpty()) return false
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

    private fun renderFeed() {
        val running = background.getBoolean("running", false)
        autoBadge.text = if (running) "● AUTO" else "○ AUTO"
        autoBadge.setTextColor(if (running) Color.rgb(37, 244, 238) else Color.WHITE)

        if (videos.isEmpty()) {
            preloader?.release()
            preloader = null
            if (::video.isInitialized) video.stopPlayback()
            video.visibility = View.GONE
            infoLayer.visibility = View.GONE
            counter.visibility = View.GONE
            emptyLayer.visibility = View.VISIBLE
            return
        }
        emptyLayer.visibility = View.GONE
        video.visibility = View.VISIBLE
        infoLayer.visibility = View.VISIBLE
        counter.visibility = View.VISIBLE
        showCurrent()
    }

    private fun showCurrent() {
        if (videos.isEmpty()) return
        index = ((index % videos.size) + videos.size) % videos.size
        store.setFeedIndex(index)
        liked = false
        saved = false
        likeButton.text = "♡\nإعجاب"
        saveButton.text = "☆\nحفظ"
        counter.text = "${index + 1}/${videos.size}"
        accountLabel.text = store.accountLabel()
        caption.text = store.caption().ifBlank { "من مكتبة YM" }
        commentHint.text = if (comments.isEmpty()) {
            "أضف مكتبة تعليقات من مركز YM"
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
        preloadNext()
    }

    private fun preloadNext() {
        preloader?.release()
        preloader = null
        if (videos.size < 2) return
        val nextUri = videos[(index + 1) % videos.size]
        runCatching {
            val player = MediaPlayer()
            preloader = player
            player.setDataSource(this, nextUri)
            player.setOnPreparedListener { prepared ->
                if (preloader === prepared) preloader = null
                prepared.release()
            }
            player.setOnErrorListener { failed, _, _ ->
                if (preloader === failed) preloader = null
                failed.release()
                true
            }
            player.prepareAsync()
        }.onFailure {
            preloader?.release()
            preloader = null
        }
    }

    private fun next() {
        if (videos.isEmpty()) return
        index = (index + 1) % videos.size
        showCurrent()
    }

    private fun previous() {
        if (videos.isEmpty()) return
        index = if (index == 0) videos.lastIndex else index - 1
        showCurrent()
    }

    private fun copySuggestedComment() {
        if (comments.isEmpty()) {
            Toast.makeText(this, "أضف تعليقات إلى دولاب YM أولًا", Toast.LENGTH_SHORT).show()
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
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(9), dp(4), dp(9))
        setOnClickListener { action() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onPause() {
        if (::video.isInitialized && video.isPlaying) video.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::video.isInitialized) {
            reloadData()
            renderFeed()
        }
    }

    override fun onDestroy() {
        preloader?.release()
        preloader = null
        if (::video.isInitialized) video.stopPlayback()
        super.onDestroy()
    }
}
