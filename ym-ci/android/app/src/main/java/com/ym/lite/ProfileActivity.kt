package com.ym.lite

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ym.lite.core.LibraryStore
import com.ym.lite.ui.YmNav

class ProfileActivity : AppCompatActivity() {
    private lateinit var store: LibraryStore
    private val local by lazy { getSharedPreferences("ym_local", MODE_PRIVATE) }
    private val background by lazy { getSharedPreferences("ym_background", MODE_PRIVATE) }
    private lateinit var account: TextView
    private lateinit var stats: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LibraryStore(this)
        setContentView(buildScreen())
        render()
    }

    private fun buildScreen(): FrameLayout {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(42), dp(20), dp(110))
        }
        root.addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val avatar = TextView(this).apply {
            text = "YM"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 30f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(25, 25, 25))
                setStroke(dp(3), Color.WHITE)
            }
        }
        body.addView(avatar, LinearLayout.LayoutParams(dp(110), dp(110)))

        account = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(6))
        }
        body.addView(account, matchWrap())

        stats = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(22))
        }
        body.addView(stats, matchWrap())

        body.addView(Button(this).apply {
            text = "ربط / تحديث حساب TikTok"
            isAllCaps = false
            setOnClickListener { startActivity(Intent(this@ProfileActivity, TikTokConnectActivity::class.java)) }
        }, matchWrap())

        body.addView(Button(this).apply {
            text = "مركز YM المتقدم"
            isAllCaps = false
            setOnClickListener { startActivity(Intent(this@ProfileActivity, MainActivity::class.java)) }
        }, matchWrap())

        body.addView(TextView(this).apply {
            text = "YM = Feed أولًا، والحساب الحقيقي + المكتبة + AUTO خلفه."
            setTextColor(Color.GRAY)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(30), 0, 0)
        }, matchWrap())

        val nav = YmNav.bottomBar(this, YmNav.Tab.PROFILE) {
            startActivity(Intent(this, LibraryActivity::class.java))
        }
        root.addView(nav, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72), Gravity.BOTTOM))
        return root
    }

    private fun render() {
        val connected = local.getBoolean("tiktok_connected", false)
        account.text = local.getString("selected_account_label", "@YM").orEmpty().ifBlank { "@YM" }
        val libraryCount = store.readUris().size
        val running = background.getBoolean("running", false)
        stats.text = "TikTok: ${if (connected) "متصل" else "غير متصل"}   •   $libraryCount فيديو   •   AUTO ${if (running) "يعمل" else "متوقف"}"
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        if (::account.isInitialized) render()
    }
}
