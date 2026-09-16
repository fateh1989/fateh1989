package com.ym.lite

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.ym.lite.core.LibraryStore
import com.ym.lite.ui.YmNav
import kotlin.math.abs

class FeedActivity : AppCompatActivity() {
    private lateinit var store: LibraryStore
    private val background by lazy { getSharedPreferences("ym_background", MODE_PRIVATE) }
    private val engagement by lazy { getSharedPreferences("ym_feed_engagement", MODE_PRIVATE) }
    private val videos = mutableListOf<Uri>()
    private var comments = emptyList<String>()
    private var index = 0
    private var playlistSignature = ""
    private var player: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var playerView: PlayerView
    private lateinit var emptyLayer: LinearLayout
    private lateinit var infoLayer: LinearLayout
    private lateinit var counter: TextView
    private lateinit var caption: TextView
    private lateinit var commentHint: TextView
    private lateinit var accountLabel: TextView
    private lateinit var autoBadge: TextView
    private lateinit var likeButton: TextView
    private lateinit var saveButton: TextView
    private lateinit var playbackState: TextView

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        store.appendUris(uris)
        reloadData()
        renderFeed(forcePlaylist = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LibraryStore(this)
        setContentView(buildScreen())
        reloadData()
        renderFeed(forcePlaylist = true)
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

        playerView = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            keepScreenOn = true
        }
        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

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
                text = "＋ إضافة فيديوهات"
                isAllCaps = false
                setOnClickListener { picker.launch(arrayOf("video/*")) }
            })
        }
        root.addView(emptyLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Full-screen gesture catcher above the player and below all controls.
        var downY = 0f
        var downX = 0f
        val gestureLayer = View(this).apply {
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downY = event.y
                        downX = event.x
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val dy = event.y - downY
                        val dx = event.x - downX
                        if (abs(dy) > dp(52) && abs(dy) > abs(dx) * 1.15f) {
                            if (dy < 0) moveBy(1) else moveBy(-1)
                        } else if (abs(dy) < dp(18) && abs(dx) < dp(18)) {
                            togglePlayback()
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> true
                }
            }
        }
        root.addView(gestureLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(topTab("يتابع", false).apply {
                setOnClickListener {
                    Toast.makeText(this@FeedActivity, "Feed المتابعة من TikTok لم يُربط بعد؛ هذه الشاشة تعرض مكتبة YM.", Toast.LENGTH_SHORT).show()
                }
            })
            addView(topTab("لك", true).apply {
                setOnClickListener { Toast.makeText(this@FeedActivity, "يعرض الآن مكتبة YM", Toast.LENGTH_SHORT).show() }
            })
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

        playbackState = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            visibility = View.GONE
        }
        root.addView(playbackState, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(railButton("YM") { startActivity(Intent(this@FeedActivity, ProfileActivity::class.java)) })
            likeButton = railButton("♡\nإعجاب") { toggleLike() }
            addView(likeButton)
            addView(railButton("●\nتعليق") { showCommentPanel() })
            saveButton = railButton("☆\nحفظ") { toggleSave() }
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
        return root
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        return ExoPlayer.Builder(this).build().also { exo ->
            player = exo
            playerView.player = exo
            exo.repeatMode = Player.REPEAT_MODE_ONE
            exo.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val current = exo.currentMediaItemIndex
                    if (current in videos.indices) {
                        index = current
                        store.setFeedIndex(index)
                        updateOverlay()
                        showPlaybackStatus("يحمّل الفيديو…", temporary = false)
                        scheduleVideoFrameCheck()
                    }
                }

                override fun onRenderedFirstFrame() {
                    playbackState.visibility = View.GONE
                    mainHandler.removeCallbacksAndMessages(VIDEO_CHECK_TOKEN)
                }

                override fun onPlayerError(error: PlaybackException) {
                    showPlaybackStatus("تعذر تشغيل الفيديو: ${error.errorCodeName}", temporary = false)
                }
            })
        }
    }

    private fun configurePlaylist(force: Boolean) {
        if (videos.isEmpty()) return
        val signature = videos.joinToString("|") { it.toString() }
        val exo = ensurePlayer()
        if (!force && signature == playlistSignature && exo.mediaItemCount == videos.size) return
        playlistSignature = signature
        val startIndex = index.coerceIn(videos.indices)
        exo.setMediaItems(videos.map { MediaItem.fromUri(it) }, startIndex, 0L)
        exo.prepare()
        exo.playWhenReady = true
        showPlaybackStatus("يحمّل الفيديو…", temporary = false)
        scheduleVideoFrameCheck()
    }

    private fun renderFeed(forcePlaylist: Boolean = false) {
        val running = background.getBoolean("running", false)
        autoBadge.text = if (running) "● AUTO" else "○ AUTO"
        autoBadge.setTextColor(if (running) Color.rgb(37, 244, 238) else Color.WHITE)

        if (videos.isEmpty()) {
            player?.stop()
            playerView.visibility = View.GONE
            infoLayer.visibility = View.GONE
            counter.visibility = View.GONE
            playbackState.visibility = View.GONE
            emptyLayer.visibility = View.VISIBLE
            return
        }

        emptyLayer.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        infoLayer.visibility = View.VISIBLE
        counter.visibility = View.VISIBLE
        updateOverlay()
        configurePlaylist(forcePlaylist)
        player?.play()
    }

    private fun updateOverlay() {
        if (videos.isEmpty()) return
        index = ((index % videos.size) + videos.size) % videos.size
        store.setFeedIndex(index)
        counter.text = if (videos.size == 1) "1/1 • أضف المزيد" else "${index + 1}/${videos.size}"
        accountLabel.text = store.accountLabel()
        caption.text = store.caption().ifBlank { "من مكتبة YM" }
        commentHint.text = if (comments.isEmpty()) {
            "اضغط تعليق لكتابة أو إدارة تعليق YM"
        } else {
            "التعليق المقترح: ${comments[index % comments.size]}"
        }
        refreshEngagementButtons()
    }

    private fun moveBy(delta: Int) {
        if (videos.size <= 1) {
            Toast.makeText(this, "يوجد فيديو واحد فقط في المكتبة. أضف فيديوهات أخرى من زر +", Toast.LENGTH_SHORT).show()
            return
        }
        val newIndex = ((index + delta) % videos.size + videos.size) % videos.size
        index = newIndex
        store.setFeedIndex(index)
        updateOverlay()
        val exo = ensurePlayer()
        exo.seekToDefaultPosition(index)
        exo.play()
        showPlaybackStatus("يحمّل الفيديو…", temporary = false)
        scheduleVideoFrameCheck()
    }

    private fun togglePlayback() {
        val exo = player ?: return
        if (exo.isPlaying) {
            exo.pause()
            showPlaybackStatus("إيقاف مؤقت", temporary = true)
        } else {
            exo.play()
            showPlaybackStatus("تشغيل", temporary = true)
        }
    }

    private fun scheduleVideoFrameCheck() {
        mainHandler.removeCallbacksAndMessages(VIDEO_CHECK_TOKEN)
        mainHandler.postAtTime({
            val exo = player ?: return@postAtTime
            if (videos.isNotEmpty() && exo.videoSize.width == 0 && exo.playbackState == Player.STATE_READY) {
                showPlaybackStatus("الصوت يعمل لكن لم تظهر صورة — جرّب فيديو MP4/H.264 أو أرسل لي نوع الملف", temporary = false)
            }
        }, VIDEO_CHECK_TOKEN, android.os.SystemClock.uptimeMillis() + 1800L)
    }

    private fun showPlaybackStatus(text: String, temporary: Boolean) {
        playbackState.text = text
        playbackState.visibility = View.VISIBLE
        if (temporary) {
            mainHandler.postDelayed({ playbackState.visibility = View.GONE }, 850L)
        }
    }

    private fun itemKey(prefix: String): String {
        val uri = videos.getOrNull(index)?.toString().orEmpty()
        return "$prefix:${uri.hashCode().toUInt().toString(16)}"
    }

    private fun refreshEngagementButtons() {
        if (videos.isEmpty()) return
        val liked = engagement.getBoolean(itemKey("like"), false)
        val saved = engagement.getBoolean(itemKey("save"), false)
        likeButton.text = if (liked) "♥\nإعجاب" else "♡\nإعجاب"
        saveButton.text = if (saved) "★\nمحفوظ" else "☆\nحفظ"
    }

    private fun toggleLike() {
        if (videos.isEmpty()) return
        val key = itemKey("like")
        engagement.edit().putBoolean(key, !engagement.getBoolean(key, false)).apply()
        refreshEngagementButtons()
    }

    private fun toggleSave() {
        if (videos.isEmpty()) return
        val key = itemKey("save")
        engagement.edit().putBoolean(key, !engagement.getBoolean(key, false)).apply()
        refreshEngagementButtons()
    }

    private fun showCommentPanel() {
        if (videos.isEmpty()) return
        val suggestion = comments.getOrNull(index % maxOf(1, comments.size)).orEmpty()
        val input = EditText(this).apply {
            hint = "اكتب تعليقًا"
            setText(suggestion)
            setSelection(text.length)
            minLines = 2
        }
        AlertDialog.Builder(this)
            .setTitle("تعليق YM")
            .setMessage("هذا الزر يدير نص التعليق داخل YM؛ الإرسال إلى TikTok ليس مفعّلًا من هذا الزر بعد.")
            .setView(input)
            .setPositiveButton("نسخ") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("YM comment", text))
                    Toast.makeText(this, "تم نسخ التعليق", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("مركز YM") { _, _ -> startActivity(Intent(this, MainActivity::class.java)) }
            .setNegativeButton("إغلاق", null)
            .show()
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
        player?.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::playerView.isInitialized) {
            val oldSignature = videos.joinToString("|") { it.toString() }
            reloadData()
            val newSignature = videos.joinToString("|") { it.toString() }
            renderFeed(forcePlaylist = oldSignature != newSignature)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private val VIDEO_CHECK_TOKEN = Any()
    }
}
