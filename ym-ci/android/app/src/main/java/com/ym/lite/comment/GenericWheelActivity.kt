package com.ym.lite.comment

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class GenericWheelActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_WHEEL_ID = "wheel_id"
    }

    private val wheelId: Int by lazy { intent.getIntExtra(EXTRA_WHEEL_ID, 1).coerceIn(1, 2) }
    private val prefs by lazy { getSharedPreferences("ym_wheel_$wheelId", MODE_PRIVATE) }

    private lateinit var pool: EditText
    private lateinit var wheel: CommentWheelView
    private lateinit var speedLabel: TextView
    private lateinit var speedSeek: SeekBar
    private lateinit var toggle: Button
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
        loadValues()
        render()
    }

    private fun buildScreen(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = LinearLayout.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(24), dp(18), dp(34))
        }
        scroll.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        body.addView(TextView(this).apply {
            text = "زر $wheelId — الدولاب"
            setTextColor(Color.WHITE)
            textSize = 27f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        })

        body.addView(TextView(this).apply {
            text = "🎡 دولاب مستقل كامل لزر $wheelId. المحتوى فارغ الآن ويمكن تعبئته لاحقًا."
            setTextColor(Color.LTGRAY)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(12))
        })

        status = cardText()
        body.addView(status, matchWrap().apply { bottomMargin = dp(14) })

        wheel = CommentWheelView(this)
        body.addView(wheel, LinearLayout.LayoutParams(dp(340), dp(340)).apply { bottomMargin = dp(12) })

        body.addView(actionButton("تدوير للدور التالي") {
            wheel.selectNext()
        }, matchWrap())

        speedLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        body.addView(speedLabel, matchWrap())

        speedSeek = SeekBar(this).apply {
            max = 6
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    speedLabel.text = "السرعة: ${progress + 2} ثوانٍ"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = saveValues(false)
            })
        }
        body.addView(speedSeek, matchWrap())

        body.addView(TextView(this).apply {
            text = "كل سطر = عنصر مستقل داخل الدولاب"
            setTextColor(Color.LTGRAY)
            textSize = 15f
            gravity = Gravity.START
            setPadding(0, dp(10), 0, dp(6))
        })

        pool = EditText(this).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(Color.DKGRAY)
            hint = "المحتوى فارغ الآن"
            textSize = 16f
            minLines = 8
            gravity = Gravity.TOP or Gravity.START
            background = roundedBox()
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        body.addView(pool, matchWrap().apply { bottomMargin = dp(12) })

        body.addView(actionButton("حفظ دولاب زر $wheelId") {
            saveValues(true)
        }, matchWrap())

        toggle = actionButton("تشغيل دولاب زر $wheelId") {
            saveValues(false)
            prefs.edit().putBoolean("enabled", !prefs.getBoolean("enabled", false)).apply()
            render()
        }
        body.addView(toggle, matchWrap())

        return scroll
    }

    private fun loadValues() {
        val text = prefs.getString("pool", "").orEmpty()
        val items = parseItems(text)
        pool.setText(text)
        wheel.setComments(items, prefs.getInt("index", 0))

        val seconds = prefs.getInt("interval_seconds", 3).coerceIn(2, 8)
        speedSeek.progress = seconds - 2
        speedLabel.text = "السرعة: $seconds ثوانٍ"

        wheel.onSelectionChanged = { index, _ ->
            prefs.edit().putInt("index", index).apply()
        }
    }

    private fun saveValues(showToast: Boolean) {
        val text = pool.text.toString()
        prefs.edit()
            .putString("pool", text)
            .putInt("interval_seconds", speedSeek.progress + 2)
            .apply()
        wheel.setComments(parseItems(text), prefs.getInt("index", 0))
        if (showToast) Toast.makeText(this, "تم حفظ دولاب زر $wheelId", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun render() {
        if (!::status.isInitialized) return
        val enabled = prefs.getBoolean("enabled", false)
        val count = parseItems(pool.text.toString()).size
        status.text = buildString {
            append(if (enabled) "● الدولاب يعمل" else "○ الدولاب متوقف")
            append("\nعدد العناصر: $count")
            if (count == 0) append("\nالمحتوى فارغ الآن")
        }
        status.setTextColor(if (enabled) Color.rgb(37, 244, 238) else Color.WHITE)
        toggle.text = if (enabled) "إيقاف دولاب زر $wheelId" else "تشغيل دولاب زر $wheelId"
    }

    private fun parseItems(text: String): List<String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toList()

    private fun cardText() = TextView(this).apply {
        setTextColor(Color.WHITE)
        textSize = 16f
        gravity = Gravity.CENTER
        background = roundedBox()
        setPadding(dp(14), dp(14), dp(14), dp(14))
    }

    private fun actionButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        setOnClickListener { onClick() }
    }

    private fun roundedBox() = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(Color.rgb(18, 18, 18))
        setStroke(dp(1), Color.rgb(55, 55, 55))
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = dp(8) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
