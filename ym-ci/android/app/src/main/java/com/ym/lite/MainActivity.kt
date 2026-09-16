package com.ym.lite

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ym.lite.background.YmPlanWorker
import com.ym.lite.net.YmRelayClient
import com.ym.lite.security.SecurePrefs
import org.json.JSONArray
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var securePrefs: SecurePrefs
    private val localPrefs by lazy { getSharedPreferences("ym_local", MODE_PRIVATE) }
    private val backgroundPrefs by lazy { getSharedPreferences("ym_background", MODE_PRIVATE) }
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    private val selected = mutableListOf<Uri>()
    private var accounts = emptyList<YmRelayClient.Account>()
    private var previewIndex = 0

    private lateinit var accountSpinner: Spinner
    private lateinit var videoView: VideoView
    private lateinit var videoPlaceholder: TextView
    private lateinit var videoCount: TextView
    private lateinit var caption: EditText
    private lateinit var dailyCount: EditText
    private lateinit var windowStart: EditText
    private lateinit var windowEnd: EditText
    private lateinit var horizonDays: EditText
    private lateinit var commentPool: EditText
    private lateinit var commentPreview: TextView
    private lateinit var workerUrl: EditText
    private lateinit var pairToken: EditText
    private lateinit var status: TextView
    private lateinit var autoState: TextView
    private lateinit var progress: ProgressBar

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        selected.clear()
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            selected += uri
        }
        previewIndex = 0
        persistLibrary()
        renderLibrary()
        setStatus("تمت إضافة ${selected.size} فيديو إلى مكتبة YM")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        securePrefs = SecurePrefs(this)
        setContentView(buildScreen())
        restoreState()
        observeBackgroundPlan()
    }

    private fun buildScreen(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(14), dp(18), dp(14), dp(30))
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(TextView(this).apply {
            text = "YM"
            setTextColor(Color.WHITE)
            textSize = 34f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "مدير TikTok مستقل • مكتبة • دولاب نشر • AUTO خلفي"
            setTextColor(Color.rgb(190, 190, 190))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(14))
        })

        val accountCard = card("الحساب")
        accountSpinner = Spinner(this)
        accountCard.addView(accountSpinner, matchWrap())
        accountCard.addView(row(
            actionButton("ربط حساب TikTok") { connectTikTok() },
            actionButton("تحديث الحسابات") { refreshAccounts() },
        ))
        root.addView(accountCard)

        val libraryCard = card("مكتبة الفيديوهات")
        videoView = VideoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(460))
            setBackgroundColor(Color.rgb(8, 8, 8))
            setOnPreparedListener { mp -> mp.isLooping = true; start() }
        }
        libraryCard.addView(videoView)
        videoPlaceholder = TextView(this).apply {
            text = "المكتبة فارغة\nأضف فيديوهاتك هنا"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 18f
            setPadding(dp(10), dp(18), dp(10), dp(18))
        }
        libraryCard.addView(videoPlaceholder, matchWrap())
        videoCount = label("0 فيديو")
        libraryCard.addView(videoCount)
        libraryCard.addView(row(
            actionButton("السابق") { previousPreview() },
            actionButton("إضافة فيديوهات") { picker.launch(arrayOf("video/*")) },
            actionButton("التالي") { nextPreview() },
        ))
        caption = field("النص الافتراضي للمنشور", false)
        libraryCard.addView(caption)
        root.addView(libraryCard)

        val publishCard = card("دولاب النشر")
        publishCard.addView(label("YM يختار من المكتبة فقط، يوزع المقاطع على الأيام والأوقات، ويتجنب التكرار المباشر."))
        dailyCount = numberField("عدد المنشورات يوميًا", "1")
        windowStart = field("بداية نافذة النشر HH:mm", true).apply { setText("18:00") }
        windowEnd = field("نهاية نافذة النشر HH:mm", true).apply { setText("23:00") }
        horizonDays = numberField("عدد أيام الخطة", "30")
        publishCard.addView(dailyCount)
        publishCard.addView(windowStart)
        publishCard.addView(windowEnd)
        publishCard.addView(horizonDays)
        publishCard.addView(row(
            actionButton("اختبار منشور +10 دقائق") { scheduleProofPost() },
            actionButton("تحقق من الاختبار") { checkLastPost() },
        ))
        root.addView(publishCard)

        val commentCard = card("دولاب التعليقات")
        commentCard.addView(label("كل سطر تعليق مستقل. الدولاب يدور بينها داخل YM ويمكنك مراجعة التعليق التالي قبل استخدامه."))
        commentPool = field("اكتب التعليقات، كل تعليق في سطر", false).apply {
            minLines = 5
            gravity = Gravity.TOP or Gravity.START
        }
        commentCard.addView(commentPool)
        commentPreview = label("التعليق التالي: —").apply {
            setTextColor(Color.rgb(37, 244, 238))
        }
        commentCard.addView(commentPreview)
        commentCard.addView(actionButton("لف دولاب التعليقات") { rotateComment() }, matchWrap())
        root.addView(commentCard)

        val autoCard = card("AUTO — يعمل بعد إغلاق YM")
        autoCard.addView(label("عند التشغيل، YM يجهز ويرسل خطة النشر في الخلفية. بعد تسليم الخطة يمكنك إغلاق التطبيق؛ المنشورات المجدولة لا تعتمد على بقاء الشاشة مفتوحة."))
        autoState = label("AUTO متوقف").apply {
            setTextColor(Color.rgb(37, 244, 238))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        autoCard.addView(autoState)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        autoCard.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16)).apply {
            setMargins(0, dp(8), 0, dp(8))
        })
        autoCard.addView(row(
            actionButton("تشغيل AUTO") { startBackgroundPlan() },
            actionButton("إيقاف") { stopBackgroundPlan() },
        ))
        root.addView(autoCard)

        val historyCard = card("السجل")
        status = label("جاهز")
        historyCard.addView(status)
        historyCard.addView(actionButton("تحديث آخر نتيجة") { checkLastPost() }, matchWrap())
        root.addView(historyCard)

        val settingsCard = card("الاتصال")
        workerUrl = field("Worker URL — https://...workers.dev", true)
        pairToken = field("رمز الاقتران", true)
        settingsCard.addView(workerUrl)
        settingsCard.addView(pairToken)
        settingsCard.addView(actionButton("حفظ الاتصال") { saveConnection() }, matchWrap())
        root.addView(settingsCard)

        root.addView(TextView(this).apply {
            text = "YM v0.13 • التطبيق مستقل عن واجهة TikTok الأصلية"
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        })

        return scroll
    }

    private fun restoreState() {
        workerUrl.setText(securePrefs.workerUrl())
        pairToken.setText(if (securePrefs.token().isBlank()) "" else "••••••••")
        caption.setText(localPrefs.getString("caption", ""))
        dailyCount.setText(localPrefs.getString("daily_count", "1"))
        windowStart.setText(localPrefs.getString("window_start", "18:00"))
        windowEnd.setText(localPrefs.getString("window_end", "23:00"))
        horizonDays.setText(localPrefs.getString("horizon_days", "30"))
        commentPool.setText(localPrefs.getString("comment_pool", ""))
        restoreLibrary()
        renderLibrary()
        renderAutoState()
        val last = backgroundPrefs.getString("last_status", "").orEmpty()
        if (last.isNotBlank()) status.text = last
    }

    private fun restoreLibrary() {
        selected.clear()
        val raw = localPrefs.getString("wheel_uris", "[]").orEmpty()
        runCatching {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val value = a.optString(i)
                if (value.isNotBlank()) selected += Uri.parse(value)
            }
        }
        previewIndex = localPrefs.getInt("preview_index", 0).coerceAtLeast(0)
        if (selected.isNotEmpty()) previewIndex %= selected.size
    }

    private fun persistLibrary() {
        val a = JSONArray()
        selected.forEach { a.put(it.toString()) }
        localPrefs.edit()
            .putString("wheel_uris", a.toString())
            .putInt("preview_index", previewIndex)
            .apply()
    }

    private fun renderLibrary() {
        videoCount.text = "${selected.size} فيديو في مكتبة YM"
        if (selected.isEmpty()) {
            videoView.stopPlayback()
            videoView.visibility = View.GONE
            videoPlaceholder.visibility = View.VISIBLE
            return
        }
        previewIndex = previewIndex.coerceIn(0, selected.lastIndex)
        videoPlaceholder.visibility = View.GONE
        videoView.visibility = View.VISIBLE
        runCatching {
            videoView.setVideoURI(selected[previewIndex])
            videoView.start()
        }.onFailure {
            videoPlaceholder.visibility = View.VISIBLE
            videoPlaceholder.text = "تعذر تشغيل معاينة هذا الفيديو"
        }
        localPrefs.edit().putInt("preview_index", previewIndex).apply()
    }

    private fun nextPreview() {
        if (selected.isEmpty()) return
        previewIndex = (previewIndex + 1) % selected.size
        renderLibrary()
    }

    private fun previousPreview() {
        if (selected.isEmpty()) return
        previewIndex = if (previewIndex == 0) selected.lastIndex else previewIndex - 1
        renderLibrary()
    }

    private fun saveConnection() {
        val old = securePrefs.token()
        val typed = pairToken.text.toString()
        val token = if (typed == "••••••••") old else typed
        securePrefs.save(workerUrl.text.toString().trim(), token.trim())
        pairToken.setText(if (token.isBlank()) "" else "••••••••")
        setStatus("تم حفظ اتصال YM")
    }

    private fun saveProductState() {
        persistLibrary()
        localPrefs.edit()
            .putString("caption", caption.text.toString())
            .putString("daily_count", dailyCount.text.toString())
            .putString("window_start", windowStart.text.toString())
            .putString("window_end", windowEnd.text.toString())
            .putString("horizon_days", horizonDays.text.toString())
            .putString("comment_pool", commentPool.text.toString())
            .apply()
    }

    private fun client(): YmRelayClient {
        val url = securePrefs.workerUrl()
        val token = securePrefs.token()
        require(url.startsWith("https://")) { "احفظ Worker URL أولًا" }
        require(token.isNotBlank()) { "احفظ رمز الاقتران أولًا" }
        return YmRelayClient(url, token)
    }

    private fun connectTikTok() = runTask {
        val url = client().createTikTokAuthUrl()
        runOnUiThread {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            setStatus("بعد إنهاء ربط الحساب ارجع إلى YM واضغط تحديث الحسابات")
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
            val savedId = localPrefs.getString("selected_account_id", "").orEmpty()
            val index = loaded.indexOfFirst { it.id == savedId }.takeIf { it >= 0 } ?: 0
            if (loaded.isNotEmpty()) {
                accountSpinner.setSelection(index)
                saveSelectedAccount(loaded[index])
            }
            accountSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    loaded.getOrNull(position)?.let(::saveSelectedAccount)
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
            setStatus(if (loaded.isEmpty()) "لم يظهر حساب متصل" else "تم العثور على ${loaded.size} حساب")
        }
    }

    private fun saveSelectedAccount(account: YmRelayClient.Account) {
        localPrefs.edit()
            .putString("selected_account_id", account.id)
            .putString("selected_account_label", account.label)
            .apply()
    }

    private fun scheduleProofPost() {
        saveProductState()
        if (selected.isEmpty()) {
            setStatus("أضف فيديوًا واحدًا على الأقل إلى المكتبة")
            return
        }
        val accountId = localPrefs.getString("selected_account_id", "").orEmpty()
        if (accountId.isBlank()) {
            setStatus("اربط الحساب ثم اضغط تحديث الحسابات")
            return
        }
        val uri = selected.first()
        val text = caption.text.toString()
        runTask {
            val api = client()
            val upload = api.createUploadTarget()
            api.uploadSigned(contentResolver, uri, upload.uploadUrl, contentResolver.getType(uri))
            val postId = api.createScheduledPost(
                accountId,
                text,
                upload.mediaUrl,
                Instant.now().plusSeconds(10 * 60).toString(),
            )
            localPrefs.edit().putString("last_post_id", postId).apply()
            runOnUiThread { setStatus("تمت جدولة منشور الاختبار بعد 10 دقائق • $postId") }
        }
    }

    private fun checkLastPost() {
        val postId = localPrefs.getString("last_post_id", "").orEmpty()
        if (postId.isBlank()) {
            setStatus("لا يوجد منشور اختبار محفوظ")
            return
        }
        runTask {
            val rows = client().getPostResults(postId)
            runOnUiThread {
                if (rows.isEmpty()) {
                    setStatus("لا توجد نتيجة نهائية بعد")
                } else {
                    val success = rows.firstOrNull { it.success }
                    if (success != null) {
                        localPrefs.edit().putBoolean("provider_proof_passed", true).apply()
                        setStatus("نجح اختبار النشر • ${success.url ?: "الرابط لم يظهر بعد"}")
                    } else {
                        setStatus("فشل الاختبار: ${rows.firstNotNullOfOrNull { it.error } ?: "سبب غير معروف"}")
                    }
                }
            }
        }
    }

    private fun startBackgroundPlan() {
        saveProductState()
        if (!localPrefs.getBoolean("provider_proof_passed", false)) {
            setStatus("نفّذ اختبار منشور واحد وتحقق من نجاحه قبل تشغيل AUTO")
            return
        }
        if (selected.isEmpty()) {
            setStatus("مكتبة الفيديوهات فارغة")
            return
        }
        if (localPrefs.getString("selected_account_id", "").isNullOrBlank()) {
            setStatus("اختر حسابًا متصلًا أولًا")
            return
        }
        runCatching {
            dailyCount.text.toString().toInt().coerceIn(1, 10)
            horizonDays.text.toString().toInt().coerceIn(1, 30)
        }.onFailure {
            setStatus("تحقق من عدد المنشورات وعدد الأيام")
            return
        }
        backgroundPrefs.edit()
            .putBoolean("running", true)
            .putString("last_status", "AUTO قيد التجهيز في الخلفية")
            .apply()
        YmPlanWorker.enqueue(this)
        renderAutoState()
        setStatus("بدأ AUTO في الخلفية. يمكنك إغلاق واجهة YM؛ سيستمر WorkManager في تجهيز الخطة.")
    }

    private fun stopBackgroundPlan() {
        WorkManager.getInstance(this).cancelUniqueWork(YmPlanWorker.UNIQUE_WORK)
        backgroundPrefs.edit()
            .putBoolean("running", false)
            .putString("last_status", "تم إيقاف AUTO يدويًا")
            .apply()
        renderAutoState()
        setStatus("تم إيقاف AUTO")
    }

    private fun observeBackgroundPlan() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(YmPlanWorker.UNIQUE_WORK)
            .observe(this) { infos ->
                val info = infos.lastOrNull() ?: return@observe
                val current = info.progress.getInt(YmPlanWorker.KEY_CURRENT, 0)
                val total = info.progress.getInt(YmPlanWorker.KEY_TOTAL, 0)
                if (total > 0) progress.progress = ((current * 100) / total).coerceIn(0, 100)
                when (info.state) {
                    WorkInfo.State.ENQUEUED -> {
                        autoState.text = "AUTO ينتظر الشبكة"
                    }
                    WorkInfo.State.RUNNING -> {
                        autoState.text = "AUTO يعمل في الخلفية • $current/$total"
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        progress.progress = 100
                        autoState.text = "AUTO سلّم الخطة بنجاح"
                    }
                    WorkInfo.State.FAILED -> {
                        autoState.text = "AUTO توقف بسبب خطأ"
                    }
                    WorkInfo.State.CANCELLED -> {
                        autoState.text = "AUTO متوقف"
                    }
                    else -> Unit
                }
                val last = backgroundPrefs.getString("last_status", "").orEmpty()
                if (last.isNotBlank()) status.text = last
            }
    }

    private fun renderAutoState() {
        autoState.text = if (backgroundPrefs.getBoolean("running", false)) {
            "AUTO يعمل في الخلفية"
        } else {
            "AUTO متوقف"
        }
    }

    private fun rotateComment() {
        saveProductState()
        val comments = commentPool.text.toString()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (comments.isEmpty()) {
            commentPreview.text = "التعليق التالي: —"
            setStatus("أضف تعليقات إلى دولاب التعليقات")
            return
        }
        val index = localPrefs.getInt("comment_index", 0).coerceAtLeast(0) % comments.size
        commentPreview.text = "التعليق التالي: ${comments[index]}"
        localPrefs.edit().putInt("comment_index", (index + 1) % comments.size).apply()
    }

    private fun runTask(block: () -> Unit) {
        if (!busy.compareAndSet(false, true)) {
            setStatus("هناك عملية جارية الآن")
            return
        }
        executor.execute {
            try {
                block()
            } catch (e: Exception) {
                runOnUiThread { setStatus("خطأ: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                busy.set(false)
            }
        }
    }

    private fun setStatus(text: String) {
        status.text = text
    }

    private fun card(title: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.rgb(18, 18, 18))
                setStroke(dp(1), Color.rgb(55, 55, 55))
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(12))
            }
            addView(TextView(this@MainActivity).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 20f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, dp(10))
            })
        }
    }

    private fun label(value: String): TextView = TextView(this).apply {
        text = value
        setTextColor(Color.LTGRAY)
        textSize = 15f
        setPadding(dp(4), dp(6), dp(4), dp(6))
    }

    private fun field(hintText: String, singleLine: Boolean): EditText = EditText(this).apply {
        hint = hintText
        setHintTextColor(Color.GRAY)
        setTextColor(Color.WHITE)
        setSingleLine(singleLine)
        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(80, 80, 80))
        setPadding(dp(8), dp(10), dp(8), dp(10))
        layoutParams = matchWrap()
    }

    private fun numberField(hintText: String, defaultValue: String): EditText = field(hintText, true).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(defaultValue)
    }

    private fun actionButton(textValue: String, action: () -> Unit): Button = Button(this).apply {
        text = textValue
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun actionButton(textValue: String, action: () -> Unit, params: LinearLayout.LayoutParams): Button =
        actionButton(textValue, action).apply { layoutParams = params }

    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        views.forEach { view ->
            addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onPause() {
        saveProductState()
        if (::videoView.isInitialized && videoView.isPlaying) videoView.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::videoView.isInitialized && selected.isNotEmpty()) runCatching { videoView.start() }
        renderAutoState()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
