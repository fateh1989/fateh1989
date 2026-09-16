package com.ym.lite

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tiktok.open.sdk.auth.AuthApi
import com.tiktok.open.sdk.auth.AuthRequest
import com.tiktok.open.sdk.auth.utils.PKCEUtils
import com.ym.lite.net.YmRelayClient
import com.ym.lite.security.SecurePrefs
import java.util.concurrent.Executors

class TikTokConnectActivity : AppCompatActivity() {
    private lateinit var authApi: AuthApi
    private lateinit var status: TextView
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

    private fun buildScreen(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(48), dp(24), dp(24))
        setBackgroundColor(Color.BLACK)

        addView(TextView(this@TikTokConnectActivity).apply {
            text = "ربط TikTok الحقيقي"
            setTextColor(Color.WHITE)
            textSize = 26f
            gravity = Gravity.CENTER
        }, matchWrap())

        addView(TextView(this@TikTokConnectActivity).apply {
            text = "يستخدم YM مكتبة TikTok OpenSDK الرسمية. تسجيل الدخول يحصل داخل TikTok أو Chrome، ثم يرسل YM رمز التفويض إلى خادم YM الآمن لتبديله بالتوكن."
            setTextColor(Color.LTGRAY)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(22))
        }, matchWrap())

        addView(Button(this@TikTokConnectActivity).apply {
            text = "تسجيل الدخول عبر تطبيق TikTok"
            isAllCaps = false
            setOnClickListener { beginAuth(AuthApi.AuthMethod.TikTokApp) }
        }, matchWrap())

        addView(Button(this@TikTokConnectActivity).apply {
            text = "تسجيل الدخول عبر Chrome"
            isAllCaps = false
            setOnClickListener { beginAuth(AuthApi.AuthMethod.ChromeTab) }
        }, matchWrap())

        status = TextView(this@TikTokConnectActivity).apply {
            setTextColor(Color.rgb(37, 244, 238))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
            text = configurationStatus()
        }
        addView(status, matchWrap())
    }

    private fun configurationStatus(): String = when {
        BuildConfig.TIKTOK_CLIENT_KEY.isBlank() -> "ينقص Client Key الخاص بتطبيق YM في TikTok Developers."
        BuildConfig.TIKTOK_REDIRECT_URL.contains("ym.invalid") -> "ينقص Redirect Host الحقيقي المسجل في TikTok Developers."
        secure.workerUrl().isBlank() || secure.token().isBlank() -> "ينقص عنوان خادم YM ورمز الاقتران من مركز YM المتقدم."
        else -> "جاهز لربط حساب TikTok."
    }

    private fun beginAuth(method: AuthApi.AuthMethod) {
        if (BuildConfig.TIKTOK_CLIENT_KEY.isBlank() || BuildConfig.TIKTOK_REDIRECT_URL.contains("ym.invalid")) {
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
            clientKey = BuildConfig.TIKTOK_CLIENT_KEY,
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
                runOnUiThread { status.text = "تمت الموافقة لكن فشل خادم YM: ${e.message ?: e.javaClass.simpleName}" }
            }
        }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val KEY_CODE_VERIFIER = "tiktok_code_verifier"
    }
}
