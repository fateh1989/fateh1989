package com.ym.lite

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tiktok.open.sdk.auth.AuthApi
import com.tiktok.open.sdk.auth.AuthRequest
import com.tiktok.open.sdk.auth.utils.PKCEUtils
import com.ym.lite.net.YmRelayClient
import com.ym.lite.security.SecurePrefs
import com.ym.lite.security.SigningInfo
import java.util.concurrent.Executors

class TikTokConnectActivity : AppCompatActivity() {
    private lateinit var authApi: AuthApi
    private lateinit var status: TextView
    private lateinit var clientKeyInput: EditText
    private lateinit var setupInfo: TextView
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("ym_tiktok_auth", MODE_PRIVATE) }
    private val local by lazy { getSharedPreferences("ym_local", MODE_PRIVATE) }
    private val secure by lazy { SecurePrefs(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authApi = AuthApi(activity = this)
        setContentView(buildScreen())
        handleAuthResponse(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthResponse(intent)
    }

    private fun buildScreen(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(40), dp(22), dp(40))
        }
        scroll.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        body.addView(TextView(this).apply {
            text = "اتصال TikTok"
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, matchWrap())

        body.addView(TextView(this).apply {
            text = "هذه الصفحة تعرض القيم المطلوبة لتسجيل YM كتطبيق Android في TikTok Developers ثم تبدأ Login Kit الحقيقي."
            setTextColor(Color.LTGRAY)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(20))
        }, matchWrap())

        setupInfo = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(Color.rgb(24, 24, 24))
            text = setupBlock()
        }
        body.addView(setupInfo, matchWrap())

        body.addView(Button(this).apply {
            text = "نسخ بيانات Android لـ TikTok Developers"
            isAllCaps = false
            setOnClickListener { copy("YM Android setup", setupBlock()) }
        }, matchWrap())

        clientKeyInput = EditText(this).apply {
            hint = "TikTok Client Key"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setSingleLine(true)
            setText(clientKey())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.rgb(30, 30, 30))
        }
        body.addView(clientKeyInput, matchWrap())

        body.addView(Button(this).apply {
            text = "حفظ Client Key"
            isAllCaps = false
            setOnClickListener {
                val value = clientKeyInput.text.toString().trim()
                prefs.edit().putString(KEY_CLIENT_KEY, value).apply()
                status.text = configurationStatus()
            }
        }, matchWrap())

        body.addView(Button(this).apply {
            text = "اختبار خادم YM"
            isAllCaps = false
            setOnClickListener { testRelay() }
        }, matchWrap())

        body.addView(Button(this).apply {
            text = "تسجيل الدخول عبر تطبيق TikTok"
            isAllCaps = false
            setOnClickListener { beginAuth(AuthApi.AuthMethod.TikTokApp) }
        }, matchWrap())

        body.addView(Button(this).apply {
            text = "تسجيل الدخول عبر Chrome"
            isAllCaps = false
            setOnClickListener { beginAuth(AuthApi.AuthMethod.ChromeTab) }
        }, matchWrap())

        status = TextView(this).apply {
            setTextColor(Color.rgb(37, 244, 238))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(22), 0, 0)
            text = configurationStatus()
        }
        body.addView(status, matchWrap())
        return scroll
    }

    private fun clientKey(): String =
        prefs.getString(KEY_CLIENT_KEY, "").orEmpty().ifBlank { BuildConfig.TIKTOK_CLIENT_KEY }

    private fun setupBlock(): String {
        val fingerprints = try {
            SigningInfo.fingerprints(this)
        } catch (e: Exception) {
            SigningInfo.Fingerprints("تعذر القراءة", "تعذر القراءة")
        }
        return buildString {
            appendLine("Package")
            appendLine(packageName)
            appendLine()
            appendLine("MD5")
            appendLine(fingerprints.md5)
            appendLine()
            appendLine("SHA-256")
            appendLine(fingerprints.sha256)
            appendLine()
            appendLine("Redirect URI")
            append(BuildConfig.TIKTOK_REDIRECT_URL)
        }
    }

    private fun configurationStatus(): String = when {
        clientKey().isBlank() -> "1/3: أدخل Client Key بعد إنشاء تطبيق YM في TikTok Developers."
        secure.workerUrl().isBlank() || secure.token().isBlank() -> "2/3: Client Key محفوظ. ينقص عنوان خادم YM ورمز الاقتران."
        local.getBoolean("tiktok_connected", false) -> "3/3: TikTok متصل بالحساب ${local.getString("selected_account_label", "").orEmpty()}"
        else -> "جاهز لبدء موافقة TikTok."
    }

    private fun testRelay() {
        if (secure.workerUrl().isBlank()) {
            status.text = "لا يوجد عنوان خادم YM بعد."
            return
        }
        status.text = "يفحص خادم YM…"
        executor.execute {
            try {
                val ok = YmRelayClient(secure.workerUrl(), secure.token()).health()
                runOnUiThread { status.text = if (ok) "خادم YM متصل وجاهز." else "الخادم رد لكن health غير سليم." }
            } catch (e: Exception) {
                runOnUiThread { status.text = "فشل اتصال خادم YM: ${e.message ?: e.javaClass.simpleName}" }
            }
        }
    }

    private fun beginAuth(method: AuthApi.AuthMethod) {
        val key = clientKey()
        if (key.isBlank()) {
            status.text = configurationStatus()
            return
        }
        if (secure.workerUrl().isBlank() || secure.token().isBlank()) {
            status.text = configurationStatus()
            return
        }

        val verifier = PKCEUtils.generateCodeVerifier()
        prefs.edit().putString(KEY_CODE_VERIFIER, verifier).apply()
        val request = AuthRequest(
            clientKey = key,
            scope = "user.info.basic",
            redirectUri = BuildConfig.TIKTOK_REDIRECT_URL,
            codeVerifier = verifier,
        )
        status.text = "يفتح TikTok للموافقة…"
        authApi.authorize(request, method)
    }

    private fun handleAuthResponse(intent: Intent) {
        val response = authApi.getAuthResponseFromIntent(intent, BuildConfig.TIKTOK_REDIRECT_URL) ?: return
        val authCode = response.authCode
        if (authCode.isBlank()) {
            if (response.errorCode != 0 || !response.authErrorDescription.isNullOrBlank()) {
                status.text = "فشل تسجيل الدخول: ${response.authErrorDescription ?: response.errorMsg ?: response.errorCode}"
            }
            return
        }

        val verifier = prefs.getString(KEY_CODE_VERIFIER, "").orEmpty()
        if (verifier.isBlank()) {
            status.text = "رمز PKCE مفقود؛ أعد تسجيل الدخول."
            return
        }
        status.text = "تمت موافقة TikTok. يربط YM الحساب الآن…"

        executor.execute {
            try {
                val api = YmRelayClient(secure.workerUrl(), secure.token())
                val account = api.exchangeTikTokAuthCode(
                    authCode = authCode,
                    codeVerifier = verifier,
                    grantedScopes = response.grantedPermissions,
                    redirectUri = BuildConfig.TIKTOK_REDIRECT_URL,
                )
                local.edit()
                    .putString("selected_account_id", account.id)
                    .putString("selected_account_label", account.label)
                    .putString("selected_account_avatar", account.avatarUrl.orEmpty())
                    .putBoolean("tiktok_connected", true)
                    .apply()
                prefs.edit().remove(KEY_CODE_VERIFIER).apply()
                runOnUiThread { status.text = "تم ربط TikTok بنجاح: ${account.label}" }
            } catch (e: Exception) {
                runOnUiThread { status.text = "تمت موافقة TikTok لكن فشل خادم YM: ${e.message ?: e.javaClass.simpleName}" }
            }
        }
    }

    private fun copy(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        status.text = "تم النسخ."
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(8)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) status.text = configurationStatus()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val KEY_CODE_VERIFIER = "tiktok_code_verifier"
        private const val KEY_CLIENT_KEY = "tiktok_client_key"
    }
}
