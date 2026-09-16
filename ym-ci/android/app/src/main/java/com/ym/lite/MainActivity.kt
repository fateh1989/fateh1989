package com.ym.lite

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        selected.clear()
        for (uri in uris) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            selected += uri
        }
        videoCount.text = "${selected.size} فيديو"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = SecurePrefs(this)
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
        progress.visibility = View.GONE

        workerUrl.setText(prefs.workerUrl())
        pairToken.setText(if (prefs.token().isBlank()) "" else "••••••••")

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
        findViewById<Button>(R.id.createPlan).setOnClickListener { createCloudPlan() }
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
            try { block() }
            catch (e: Exception) { runOnUiThread { setStatus("خطأ: ${e.message}") } }
            finally { busy.set(false); runOnUiThread { progress.visibility = View.GONE } }
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
                    setStatus("تمت جدولة منشور الاختبار بعد 10 دقائق. رقم العملية: $postId. يمكنك الآن إطفاء الهاتف، ثم العودة لاحقًا والضغط على تحقق من آخر نتيجة.")
                }
            }
        } catch (e: Exception) { setStatus("خطأ: ${e.message}") }
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
                    setStatus("تمت جدولة ${plan.size} منشورًا في السحابة. يمكنك الآن إطفاء الهاتف؛ هذه الخطة لا تعتمد عليه.")
                }
            }
        } catch (e: Exception) { setStatus("خطأ: ${e.message}") }
    }

    private fun setStatus(text: String) { status.text = text }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }
}
