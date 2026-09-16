package com.ym.lite

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.ym.lite.core.PlanEngine
import com.ym.lite.net.YmRelayClient
import com.ym.lite.security.SecurePrefs
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SecurePrefs
    private val localPrefs by lazy { getSharedPreferences("ym_local", MODE_PRIVATE) }
    private val executor = Executors.newSingleThreadExecutor()
    private val selected = mutableListOf<Uri>()
    private var accounts = emptyList<YmRelayClient.Account>()
    private val busy = AtomicBoolean(false)

    private lateinit var workerUrl: EditText
    private lateinit var pairToken: EditText
    private lateinit var accountSpinner: Spinner
    private lateinit var videoCount: TextView
    private lateinit var caption: EditText
    private lateinit var dailyCount: EditText
    private lateinit var windowStart: EditText
    private lateinit var windowEnd: EditText
    private lateinit var horizonDays: EditText
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    private lateinit var feedVideo: VideoView
    private lateinit var feedPlaceholder: TextView
    private lateinit var feedAuthor: TextView
    private lateinit var feedCaption: TextView
    private lateinit var bubbleMenu: View
    private lateinit var controlSheet: View
    private lateinit var sheetTitle: TextView
    private lateinit var likeButton: TextView
    private lateinit var saveButton: TextView

    private var currentFeedIndex = 0
    private var touchDownY = 0f

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        selected.clear()
        for (uri in uris) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            selected += uri
        }
        videoCount.text = "${selected.size} فيديو"
        if (selected.isNotEmpty()) {
            currentFeedIndex = 0
            playCurrentVideo()
            closeSheet()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        prefs = SecurePrefs(this)
        bindViews()
        bindFeedControls()
        bindAutomationControls()

        workerUrl.setText(prefs.workerUrl())
        pairToken.setText(if (prefs.token().isBlank()) "" else "••••••••")
        progress.visibility = View.GONE
    }

    private fun bindViews() {
        workerUrl = findViewById(R.id.workerUrl)
        pairToken = findViewById(R.id.pairToken)
        accountSpinner = findViewById(R.id.accountSpinner)
        videoCount = findViewById(R.id.videoCount)
        caption = findViewById(R.id.caption)
        dailyCount = findViewById(R.id.dailyCount)
        windowStart = findViewById(R.id.windowStart)
        windowEnd = findViewById(R.id.windowEnd)
        horizonDays = findViewById(R.id.horizonDays)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)

        feedVideo = findViewById(R.id.feedVideo)
        feedPlaceholder = findViewById(R.id.feedPlaceholder)
        feedAuthor = findViewById(R.id.feedAuthor)
        feedCaption = findViewById(R.id.feedCaption)
        bubbleMenu = findViewById(R.id.bubbleMenu)
        controlSheet = findViewById(R.id.controlSheet)
        sheetTitle = findViewById(R.id.sheetTitle)
        likeButton = findViewById(R.id.likeButton)
        saveButton = findViewById(R.id.saveButton)
    }

    private fun bindFeedControls() {
        feedVideo.setOnPreparedListener { mediaPlayer ->
            mediaPlayer.isLooping = true
            feedVideo.start()
        }
        feedVideo.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownY = event.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val delta = event.y - touchDownY
                    if (abs(delta) > 100f) {
                        if (delta < 0) nextVideo() else previousVideo()
                    } else {
                        if (feedVideo.isPlaying) feedVideo.pause() else feedVideo.start()
                    }
                    true
                }
                else -> true
            }
        }

        findViewById<TextView>(R.id.plusButton).setOnClickListener {
            if (controlSheet.visibility == View.VISIBLE) {
                closeSheet()
            }
            bubbleMenu.visibility = if (bubbleMenu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        findViewById<TextView>(R.id.bubbleWheel).setOnClickListener { openPanel(R.id.panelWheel, "الدولاب") }
        findViewById<TextView>(R.id.bubbleSchedule).setOnClickListener { openPanel(R.id.panelSchedule, "الجدولة") }
        findViewById<TextView>(R.id.bubbleAccounts).setOnClickListener { openPanel(R.id.panelAccounts, "الحسابات") }
        findViewById<TextView>(R.id.bubbleProof).setOnClickListener { openPanel(R.id.panelProof, "اختبار النشر") }
        findViewById<TextView>(R.id.bubbleHistory).setOnClickListener {
            openPanel(R.id.panelHistory, "السجل")
            checkLastPost()
        }
        findViewById<TextView>(R.id.bubbleSettings).setOnClickListener { openPanel(R.id.panelConnection, "الإعدادات") }
        findViewById<TextView>(R.id.closeSheet).setOnClickListener { closeSheet() }

        findViewById<TextView>(R.id.homeNav).setOnClickListener { closeSheet() }
        findViewById<TextView>(R.id.historyNav).setOnClickListener {
            openPanel(R.id.panelHistory, "السجل")
            checkLastPost()
        }
        findViewById<TextView>(R.id.profileNav).setOnClickListener { openPanel(R.id.panelAccounts, "أنا / الحسابات") }
        findViewById<TextView>(R.id.discoverNav).setOnClickListener {
            Toast.makeText(this, "اكتشف ستكون شاشة المحتوى المقترح", Toast.LENGTH_SHORT).show()
        }

        findViewById<TextView>(R.id.profileBubble).setOnClickListener { openPanel(R.id.panelAccounts, "أنا / الحسابات") }
        likeButton.setOnClickListener { toggleLike() }
        saveButton.setOnClickListener { toggleSave() }
        findViewById<TextView>(R.id.commentButton).setOnClickListener {
            Toast.makeText(this, "التعليقات ستظهر فوق الفيديو في طبقة مستقلة", Toast.LENGTH_SHORT).show()
        }
        findViewById<TextView>(R.id.shareButton).setOnClickListener { shareCurrentVideo() }
        findViewById<TextView>(R.id.forYouTab).setOnClickListener { closeSheet() }
        findViewById<TextView>(R.id.followingTab).setOnClickListener {
            Toast.makeText(this, "يتابع", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindAutomationControls() {
        findViewById<Button>(R.id.saveSettings).setOnClickListener {
            val oldToken = prefs.token()
            val typed = pairToken.text.toString()
            val actual = if (typed == "••••••••") oldToken else typed
            prefs.save(workerUrl.text.toString(), actual)
            pairToken.setText("••••••••")
            setStatus("تم حفظ الاتصال")
        }
        findViewById<Button>(R.id.connectTikTok).setOnClickListener { connectTikTok() }
        findViewById<Button>(R.id.refreshAccounts).setOnClickListener { refreshAccounts() }
        findViewById<Button>(R.id.pickVideos).setOnClickListener { picker.launch(arrayOf("video/*")) }
        findViewById<Button>(R.id.testOnePost).setOnClickListener { scheduleProofPost() }
        findViewById<Button>(R.id.checkLastPost).setOnClickListener { checkLastPost() }
        findViewById<Button>(R.id.historyRefresh).setOnClickListener { checkLastPost() }
        findViewById<Button>(R.id.createPlan).setOnClickListener { createCloudPlan() }
    }

    private fun openPanel(panelId: Int, title: String) {
        intArrayOf(
            R.id.panelWheel,
            R.id.panelSchedule,
            R.id.panelAccounts,
            R.id.panelProof,
            R.id.panelHistory,
            R.id.panelConnection,
        ).forEach { id -> findViewById<View>(id).visibility = if (id == panelId) View.VISIBLE else View.GONE }
        sheetTitle.text = title
        bubbleMenu.visibility = View.GONE
        controlSheet.visibility = View.VISIBLE
        if (feedVideo.isPlaying) feedVideo.pause()
    }

    private fun closeSheet() {
        controlSheet.visibility = View.GONE
        bubbleMenu.visibility = View.GONE
        if (selected.isNotEmpty()) feedVideo.start()
    }

    private fun playCurrentVideo() {
        if (selected.isEmpty()) {
            feedPlaceholder.visibility = View.VISIBLE
            return
        }
        currentFeedIndex = currentFeedIndex.coerceIn(0, selected.lastIndex)
        feedPlaceholder.visibility = View.GONE
        feedVideo.setVideoURI(selected[currentFeedIndex])
        feedVideo.start()
        updateFeedText()
        updateLocalActions()
    }

    private fun nextVideo() {
        if (selected.isEmpty()) return
        currentFeedIndex = (currentFeedIndex + 1) % selected.size
        playCurrentVideo()
    }

    private fun previousVideo() {
        if (selected.isEmpty()) return
        currentFeedIndex = if (currentFeedIndex == 0) selected.lastIndex else currentFeedIndex - 1
        playCurrentVideo()
    }

    private fun updateFeedText() {
        val account = accounts.getOrNull(accountSpinner.selectedItemPosition.coerceAtLeast(0))
        feedAuthor.text = account?.label?.let { "@$it" } ?: "@YM"
        val text = caption.text.toString().trim()
        feedCaption.text = if (text.isBlank()) "فيديو ${currentFeedIndex + 1} من ${selected.size} • دولاب YM" else text
    }

    private fun toggleLike() {
        if (selected.isEmpty()) return
        val key = "liked_$currentFeedIndex"
        localPrefs.edit().putBoolean(key, !localPrefs.getBoolean(key, false)).apply()
        updateLocalActions()
    }

    private fun toggleSave() {
        if (selected.isEmpty()) return
        val key = "saved_$currentFeedIndex"
        localPrefs.edit().putBoolean(key, !localPrefs.getBoolean(key, false)).apply()
        updateLocalActions()
    }

    private fun updateLocalActions() {
        val liked = localPrefs.getBoolean("liked_$currentFeedIndex", false)
        val saved = localPrefs.getBoolean("saved_$currentFeedIndex", false)
        likeButton.text = if (liked) "♥\n1" else "♡\n0"
        saveButton.text = if (saved) "★\nحفظ" else "☆\nحفظ"
    }

    private fun shareCurrentVideo() {
        val uri = selected.getOrNull(currentFeedIndex)
        if (uri == null) {
            Toast.makeText(this, "اختر فيديو أولًا", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = contentResolver.getType(uri) ?: "video/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "مشاركة الفيديو"))
    }

    private fun client(): YmRelayClient {
        val url = prefs.workerUrl()
        val token = prefs.token()
        require(url.startsWith("https://")) { "احفظ Worker URL أولًا" }
        require(token.isNotBlank()) { "احفظ رمز الاقتران أولًا" }
        return YmRelayClient(url, token)
    }

    private fun runTask(block: () -> Unit) {
        if (!busy.compareAndSet(false, true)) {
            setStatus("هناك عملية جارية الآن")
            return
        }
        progress.visibility = View.VISIBLE
        executor.execute {
            try {
                block()
            } catch (e: Exception) {
                runOnUiThread { setStatus("خطأ: ${e.message}") }
            } finally {
                busy.set(false)
                runOnUiThread { progress.visibility = View.GONE }
            }
        }
    }

    private fun connectTikTok() = runTask {
        val url = client().createTikTokAuthUrl()
        runOnUiThread {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            setStatus("بعد إنهاء الربط ارجع إلى YM واضغط تحديث الحسابات")
        }
    }

    private fun refreshAccounts() = runTask {
        val loaded = client().listTikTokAccounts()
        runOnUiThread {
            accounts = loaded
            accountSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, loaded.map { it.label })
            updateFeedText()
            setStatus(if (loaded.isEmpty()) "لم يظهر حساب متصل بعد" else "تم العثور على ${loaded.size} حساب")
        }
    }

    private fun currentAccount(): YmRelayClient.Account {
        require(accounts.isNotEmpty()) { "حدّث الحسابات أولًا" }
        return accounts[accountSpinner.selectedItemPosition.coerceIn(0, accounts.lastIndex)]
    }

    private fun scheduleProofPost() {
        try {
            val account = currentAccount()
            require(selected.isNotEmpty()) { "اختر فيديوًا واحدًا على الأقل" }
            val uri = selected.first()
            val text = caption.text.toString()
            runTask {
                val api = client()
                val upload = api.createUploadTarget()
                api.uploadSigned(contentResolver, uri, upload.uploadUrl, contentResolver.getType(uri))
                val scheduled = Instant.now().plusSeconds(10 * 60).toString()
                val postId = api.createScheduledPost(account.id, text, upload.mediaUrl, scheduled)
                localPrefs.edit().putString("last_post_id", postId).apply()
                runOnUiThread {
                    setStatus("تمت جدولة منشور الاختبار بعد 10 دقائق. رقم العملية: $postId")
                }
            }
        } catch (e: Exception) {
            setStatus("خطأ: ${e.message}")
        }
    }

    private fun checkLastPost() {
        val postId = localPrefs.getString("last_post_id", "").orEmpty()
        if (postId.isBlank()) {
            setStatus("لا يوجد منشور اختبار محفوظ بعد")
            return
        }
        runTask {
            val results = client().getPostResults(postId)
            runOnUiThread {
                if (results.isEmpty()) {
                    setStatus("لم تظهر نتيجة نهائية بعد للمنشور $postId")
                } else {
                    val success = results.firstOrNull { it.success }
                    if (success != null) {
                        localPrefs.edit().putBoolean("provider_proof_passed", true).apply()
                        val link = success.url ?: "الرابط غير متاح بعد"
                        setStatus("نجح اختبار النشر لدى المزود. $link\nتم فتح جدولة الشهر في YM.")
                    } else {
                        val error = results.firstNotNullOfOrNull { it.error } ?: "فشل النشر بدون رسالة واضحة"
                        setStatus("فشل اختبار النشر: $error")
                    }
                }
            }
        }
    }

    private fun createCloudPlan() {
        try {
            require(localPrefs.getBoolean("provider_proof_passed", false)) {
                "نفّذ اختبار منشور واحد أولًا وتحقق من نجاحه قبل إنشاء خطة شهرية"
            }
            val account = currentAccount()
            require(selected.isNotEmpty()) { "اختر فيديوهات الدولاب" }
            val days = horizonDays.text.toString().toInt().coerceIn(1, 30)
            val perDay = dailyCount.text.toString().toInt().coerceIn(1, 10)
            val start = PlanEngine.parseClock(windowStart.text.toString())
            val end = PlanEngine.parseClock(windowEnd.text.toString())
            val plan = PlanEngine.generate(
                ZoneId.systemDefault(),
                LocalDate.now().plusDays(1),
                days,
                perDay,
                start,
                end,
                selected.size,
                account.id.hashCode().toLong(),
            )
            val text = caption.text.toString()
            progress.max = plan.size
            progress.progress = 0
            setStatus("سيتم إنشاء ${plan.size} منشورًا سحابيًا. لا تغلق التطبيق أثناء رفع الخطة.")
            runTask {
                val api = client()
                plan.forEachIndexed { index, slot ->
                    val uri = selected[slot.mediaIndex]
                    val upload = api.createUploadTarget()
                    api.uploadSigned(contentResolver, uri, upload.uploadUrl, contentResolver.getType(uri))
                    api.createScheduledPost(account.id, text, upload.mediaUrl, slot.instant.toString())
                    runOnUiThread {
                        progress.progress = index + 1
                        status.text = "${index + 1}/${plan.size} — ${slot.instant}"
                    }
                }
                runOnUiThread {
                    setStatus("تمت جدولة ${plan.size} منشورًا في السحابة. يمكنك الآن إطفاء الهاتف.")
                }
            }
        } catch (e: Exception) {
            setStatus("خطأ: ${e.message}")
        }
    }

    private fun setStatus(text: String) {
        status.text = text
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            controlSheet.visibility == View.VISIBLE -> closeSheet()
            bubbleMenu.visibility == View.VISIBLE -> bubbleMenu.visibility = View.GONE
            else -> super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        if (feedVideo.isPlaying) feedVideo.pause()
    }

    override fun onResume() {
        super.onResume()
        if (selected.isNotEmpty() && controlSheet.visibility != View.VISIBLE) feedVideo.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }
}
