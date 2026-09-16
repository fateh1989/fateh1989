package com.ym.lite

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.ym.lite.automation.YmAccessibilityService
import com.ym.lite.core.FeedWheel
import com.ym.lite.core.PlanEngine
import com.ym.lite.net.YmRelayClient
import com.ym.lite.security.SecurePrefs
import org.json.JSONArray
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SecurePrefs
    private val localPrefs by lazy { getSharedPreferences("ym_local", MODE_PRIVATE) }
    private val autoPrefs by lazy { getSharedPreferences("ym_auto", MODE_PRIVATE) }
    private val executor = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())
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
    private lateinit var autoRailButton: TextView

    private lateinit var autoScrollSwitch: Switch
    private lateinit var autoCommentSwitch: Switch
    private lateinit var scrollInterval: EditText
    private lateinit var commentEvery: EditText
    private lateinit var commentPool: EditText
    private lateinit var autoServiceState: TextView

    private var currentFeedIndex = 0
    private var touchDownY = 0f

    private val feedLoop = object : Runnable {
        override fun run() {
            if (!localPrefs.getBoolean("feed_auto_enabled", false)) return
            if (controlSheet.visibility == View.VISIBLE || selected.isEmpty()) return
            nextVideo(fromAuto = true)
        }
    }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) {
            if (selected.isEmpty()) showEmptyFeed()
            return@registerForActivityResult
        }

        selected.clear()
        for (uri in uris) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            selected += uri
        }

        currentFeedIndex = 0
        persistWheel()
        videoCount.text = "${selected.size} فيديو"
        playCurrentVideo()
        closeSheet()
        scheduleFeedLoop()
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

        loadProductSettings()
        loadAutoSettings()
        restoreWheel()
        updateAutoUi()

        if (selected.isNotEmpty()) playCurrentVideo() else showEmptyFeed()
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
        autoRailButton = findViewById(R.id.autoRailButton)

        autoScrollSwitch = findViewById(R.id.autoScrollSwitch)
        autoCommentSwitch = findViewById(R.id.autoCommentSwitch)
        scrollInterval = findViewById(R.id.scrollInterval)
        commentEvery = findViewById(R.id.commentEvery)
        commentPool = findViewById(R.id.commentPool)
        autoServiceState = findViewById(R.id.autoServiceState)
    }

    private fun bindFeedControls() {
        feedPlaceholder.setOnClickListener {
            picker.launch(arrayOf("video/*"))
        }

        feedVideo.setOnPreparedListener { mediaPlayer ->
            mediaPlayer.isLooping = true
            feedVideo.start()
            scheduleFeedLoop()
        }

        feedVideo.setOnErrorListener { _, _, _ ->
            feedCaption.text = "تعذر تشغيل هذا الفيديو — الانتقال للتالي"
            if (selected.size > 1) {
                uiHandler.postDelayed({ nextVideo(fromAuto = true) }, 700)
            }
            true
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
            if (controlSheet.visibility == View.VISIBLE) closeSheet()
            bubbleMenu.visibility = if (bubbleMenu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        findViewById<TextView>(R.id.bubbleAuto).setOnClickListener {
            openPanel(R.id.panelAuto, "AUTO")
        }
        findViewById<TextView>(R.id.bubbleWheel).setOnClickListener {
            openPanel(R.id.panelWheel, "الدولاب")
        }
        findViewById<TextView>(R.id.bubbleSchedule).setOnClickListener {
            openPanel(R.id.panelSchedule, "الجدولة")
        }
        findViewById<TextView>(R.id.bubbleAccounts).setOnClickListener {
            openPanel(R.id.panelAccounts, "الحسابات")
        }
        findViewById<TextView>(R.id.bubbleHistory).setOnClickListener {
            openPanel(R.id.panelHistory, "السجل")
            checkLastPost()
        }
        findViewById<TextView>(R.id.bubbleSettings).setOnClickListener {
            openPanel(R.id.panelConnection, "الإعدادات")
        }
        findViewById<TextView>(R.id.closeSheet).setOnClickListener { closeSheet() }

        autoRailButton.setOnClickListener { toggleFeedAuto() }
        autoRailButton.setOnLongClickListener {
            openPanel(R.id.panelAuto, "AUTO")
            true
        }

        findViewById<TextView>(R.id.homeNav).setOnClickListener { closeSheet() }
        findViewById<TextView>(R.id.historyNav).setOnClickListener {
            openPanel(R.id.panelHistory, "السجل")
            checkLastPost()
        }
        findViewById<TextView>(R.id.profileNav).setOnClickListener {
            openPanel(R.id.panelAccounts, "أنا / الحسابات")
        }
        findViewById<TextView>(R.id.discoverNav).setOnClickListener {
            if (selected.isEmpty()) picker.launch(arrayOf("video/*"))
            else Toast.makeText(this, "اسحب للأعلى والأسفل بين فيديوهات الدولاب", Toast.LENGTH_SHORT).show()
        }

        findViewById<TextView>(R.id.profileBubble).setOnClickListener {
            openPanel(R.id.panelAccounts, "أنا / الحسابات")
        }
        likeButton.setOnClickListener { toggleLike() }
        saveButton.setOnClickListener { toggleSave() }
        findViewById<TextView>(R.id.commentButton).setOnClickListener {
            openPanel(R.id.panelAuto, "AUTO / التعليقات")
        }
        findViewById<TextView>(R.id.shareButton).setOnClickListener { shareCurrentVideo() }
        findViewById<TextView>(R.id.forYouTab).setOnClickListener { closeSheet() }
        findViewById<TextView>(R.id.followingTab).setOnClickListener {
            Toast.makeText(this, "يتابع", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindAutomationControls() {
        findViewById<Button>(R.id.openAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.saveAutoSettings).setOnClickListener {
            saveAutoSettings(autoPrefs.getBoolean("auto_enabled", false))
            setStatus("تم حفظ إعدادات AUTO")
            scheduleFeedLoop()
        }
        findViewById<Button>(R.id.startAuto).setOnClickListener { startAuto() }
        findViewById<Button>(R.id.stopAuto).setOnClickListener { stopAuto() }

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

    private fun restoreWheel() {
        selected.clear()
        val raw = localPrefs.getString("wheel_uris", "[]").orEmpty()
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val value = array.optString(i)
                if (value.isNotBlank()) selected += Uri.parse(value)
            }
        }
        currentFeedIndex = FeedWheel.clampIndex(
            localPrefs.getInt("wheel_index", 0),
            selected.size,
        )
        videoCount.text = "${selected.size} فيديو"
    }

    private fun persistWheel() {
        val array = JSONArray()
        selected.forEach { array.put(it.toString()) }
        localPrefs.edit()
            .putString("wheel_uris", array.toString())
            .putInt("wheel_index", currentFeedIndex)
            .apply()
    }

    private fun loadProductSettings() {
        caption.setText(localPrefs.getString("caption", ""))
        dailyCount.setText(localPrefs.getString("daily_count", "1"))
        windowStart.setText(localPrefs.getString("window_start", "18:00"))
        windowEnd.setText(localPrefs.getString("window_end", "23:00"))
        horizonDays.setText(localPrefs.getString("horizon_days", "30"))
    }

    private fun saveProductSettings() {
        localPrefs.edit()
            .putString("caption", caption.text.toString())
            .putString("daily_count", dailyCount.text.toString())
            .putString("window_start", windowStart.text.toString())
            .putString("window_end", windowEnd.text.toString())
            .putString("horizon_days", horizonDays.text.toString())
            .putInt("wheel_index", currentFeedIndex)
            .apply()
    }

    private fun loadAutoSettings() {
        autoScrollSwitch.isChecked = autoPrefs.getBoolean("auto_scroll", true)
        autoCommentSwitch.isChecked = autoPrefs.getBoolean("auto_comment", false)
        scrollInterval.setText(autoPrefs.getInt("interval_sec", 8).toString())
        commentEvery.setText(autoPrefs.getInt("comment_every", 3).toString())
        commentPool.setText(autoPrefs.getString("comment_pool", ""))
    }

    private fun saveAutoSettings(masterEnabled: Boolean) {
        val interval = FeedWheel.normalizedIntervalSec(scrollInterval.text.toString().toIntOrNull())
        val every = commentEvery.text.toString().toIntOrNull()?.coerceIn(1, 100) ?: 3
        autoPrefs.edit()
            .putBoolean("auto_enabled", masterEnabled)
            .putBoolean("auto_scroll", autoScrollSwitch.isChecked)
            .putBoolean("auto_comment", autoCommentSwitch.isChecked)
            .putInt("interval_sec", interval)
            .putInt("comment_every", every)
            .putString("comment_pool", commentPool.text.toString())
            .apply()
        scrollInterval.setText(interval.toString())
        YmAccessibilityService.notifyConfigChanged()
        updateAutoUi()
    }

    private fun toggleFeedAuto() {
        if (selected.isEmpty()) {
            localPrefs.edit().putBoolean("feed_auto_enabled", true).apply()
            updateAutoUi()
            picker.launch(arrayOf("video/*"))
            return
        }

        val newValue = !localPrefs.getBoolean("feed_auto_enabled", false)
        localPrefs.edit().putBoolean("feed_auto_enabled", newValue).apply()
        updateAutoUi()

        if (newValue) {
            if (!feedVideo.isPlaying) feedVideo.start()
            scheduleFeedLoop()
            Toast.makeText(this, "AUTO يعمل داخل YM", Toast.LENGTH_SHORT).show()
        } else {
            uiHandler.removeCallbacks(feedLoop)
            Toast.makeText(this, "AUTO متوقف", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scheduleFeedLoop() {
        uiHandler.removeCallbacks(feedLoop)
        if (!localPrefs.getBoolean("feed_auto_enabled", false)) return
        if (selected.isEmpty() || controlSheet.visibility == View.VISIBLE) return
        val seconds = FeedWheel.normalizedIntervalSec(scrollInterval.text.toString().toIntOrNull())
        uiHandler.postDelayed(feedLoop, seconds * 1000L)
    }

    private fun startAuto() {
        localPrefs.edit().putBoolean("feed_auto_enabled", true).apply()
        saveAutoSettings(true)
        scheduleFeedLoop()

        if (!isYmAccessibilityEnabled()) {
            autoPrefs.edit().putBoolean("pending_launch", true).apply()
            setStatus("فعّل خدمة YM AUTO في شاشة إمكانية الوصول. بعد التفعيل سيُفتح TikTok.")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        setStatus("AUTO يعمل — سيتم فتح TikTok الحقيقي")
        launchTikTok()
    }

    private fun stopAuto() {
        localPrefs.edit().putBoolean("feed_auto_enabled", false).apply()
        autoPrefs.edit()
            .putBoolean("auto_enabled", false)
            .putBoolean("pending_launch", false)
            .apply()
        uiHandler.removeCallbacks(feedLoop)
        YmAccessibilityService.notifyConfigChanged()
        updateAutoUi()
        setStatus("تم إيقاف AUTO")
    }

    private fun isYmAccessibilityEnabled(): Boolean {
        val component = ComponentName(this, YmAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { it.equals(component, ignoreCase = true) }
    }

    private fun updateAutoUi() {
        val access = isYmAccessibilityEnabled()
        val tiktokRunning = autoPrefs.getBoolean("auto_enabled", false)
        val feedRunning = localPrefs.getBoolean("feed_auto_enabled", false)

        autoServiceState.text =
            "داخل YM: ${if (feedRunning) "يعمل" else "متوقف"} • " +
                "TikTok: ${if (tiktokRunning) "يعمل" else "متوقف"} • " +
                "خدمة الوصول: ${if (access) "مفعلة" else "غير مفعلة"}"

        autoRailButton.text = if (feedRunning) "AUTO\n●" else "AUTO\n○"
    }

    private fun launchTikTok() {
        val packages = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
        val intent = packages.firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
        if (intent == null) {
            setStatus("لم أجد تطبيق TikTok مثبتًا على الجهاز")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun openPanel(panelId: Int, title: String) {
        intArrayOf(
            R.id.panelAuto,
            R.id.panelWheel,
            R.id.panelSchedule,
            R.id.panelAccounts,
            R.id.panelHistory,
            R.id.panelConnection,
        ).forEach { id ->
            findViewById<View>(id).visibility = if (id == panelId) View.VISIBLE else View.GONE
        }

        sheetTitle.text = title
        bubbleMenu.visibility = View.GONE
        controlSheet.visibility = View.VISIBLE
        uiHandler.removeCallbacks(feedLoop)
        if (feedVideo.isPlaying) feedVideo.pause()
        if (panelId == R.id.panelAuto) updateAutoUi()
    }

    private fun closeSheet() {
        controlSheet.visibility = View.GONE
        bubbleMenu.visibility = View.GONE
        if (selected.isNotEmpty()) {
            feedVideo.start()
            scheduleFeedLoop()
        }
    }

    private fun showEmptyFeed() {
        uiHandler.removeCallbacks(feedLoop)
        feedVideo.stopPlayback()
        feedPlaceholder.visibility = View.VISIBLE
        feedPlaceholder.text = "YM\n\nاضغط هنا لاختيار فيديوهات الدولاب\nثم اضغط AUTO للتمرير التلقائي"
        feedCaption.text = "الدولاب فارغ"
        videoCount.text = "0 فيديو"
    }

    private fun playCurrentVideo() {
        if (selected.isEmpty()) {
            showEmptyFeed()
            return
        }

        currentFeedIndex = FeedWheel.clampIndex(currentFeedIndex, selected.size)
        localPrefs.edit().putInt("wheel_index", currentFeedIndex).apply()

        feedPlaceholder.visibility = View.GONE
        feedVideo.setVideoURI(selected[currentFeedIndex])
        feedVideo.start()
        updateFeedText()
        updateLocalActions()
        scheduleFeedLoop()
    }

    private fun nextVideo(fromAuto: Boolean = false) {
        if (selected.isEmpty()) return
        currentFeedIndex = FeedWheel.nextIndex(currentFeedIndex, selected.size)
        playCurrentVideo()
        if (!fromAuto) scheduleFeedLoop()
    }

    private fun previousVideo() {
        if (selected.isEmpty()) return
        currentFeedIndex = FeedWheel.previousIndex(currentFeedIndex, selected.size)
        playCurrentVideo()
        scheduleFeedLoop()
    }

    private fun updateFeedText() {
        val account = accounts.getOrNull(accountSpinner.selectedItemPosition.coerceAtLeast(0))
        feedAuthor.text = account?.label?.let { "@$it" } ?: "@YM"
        val text = caption.text.toString().trim()
        feedCaption.text =
            if (text.isBlank()) "فيديو ${currentFeedIndex + 1} من ${selected.size} • دولاب YM"
            else text
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
        val uri = selected.getOrNull(currentFeedIndex) ?: run {
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
            accountSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                loaded.map { it.label },
            )
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
                api.uploadSigned(
                    contentResolver,
                    uri,
                    upload.uploadUrl,
                    contentResolver.getType(uri),
                )
                val scheduled = Instant.now().plusSeconds(10 * 60).toString()
                val postId = api.createScheduledPost(
                    account.id,
                    text,
                    upload.mediaUrl,
                    scheduled,
                )
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
                        setStatus(
                            "نجح اختبار النشر لدى المزود. " +
                                "${success.url ?: "الرابط غير متاح بعد"}\n" +
                                "تم فتح جدولة الشهر في YM.",
                        )
                    } else {
                        setStatus(
                            "فشل اختبار النشر: " +
                                (results.firstNotNullOfOrNull { it.error } ?: "بدون رسالة واضحة"),
                        )
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
                    api.uploadSigned(
                        contentResolver,
                        uri,
                        upload.uploadUrl,
                        contentResolver.getType(uri),
                    )
                    api.createScheduledPost(
                        account.id,
                        text,
                        upload.mediaUrl,
                        slot.instant.toString(),
                    )
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
        saveProductSettings()
        uiHandler.removeCallbacks(feedLoop)
        if (feedVideo.isPlaying) feedVideo.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        updateAutoUi()

        if (autoPrefs.getBoolean("pending_launch", false) && isYmAccessibilityEnabled()) {
            autoPrefs.edit().putBoolean("pending_launch", false).apply()
            launchTikTok()
            return
        }

        if (selected.isNotEmpty() && controlSheet.visibility != View.VISIBLE) {
            feedVideo.start()
            scheduleFeedLoop()
        }
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onDestroy()
    }
}
