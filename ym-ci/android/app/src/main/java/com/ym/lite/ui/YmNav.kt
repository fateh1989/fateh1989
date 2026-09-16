package com.ym.lite.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.ym.lite.AutoActivity
import com.ym.lite.FeedActivity
import com.ym.lite.LibraryActivity
import com.ym.lite.ProfileActivity

object YmNav {
    enum class Tab { HOME, LIBRARY, AUTO, PROFILE }

    fun bottomBar(activity: Activity, current: Tab, onAdd: (() -> Unit)? = null): LinearLayout {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun item(title: String, tab: Tab?, action: (() -> Unit)? = null): TextView = TextView(activity).apply {
            text = title
            gravity = Gravity.CENTER
            textSize = if (tab == current) 13f else 12f
            setTextColor(if (tab == current) Color.WHITE else Color.LTGRAY)
            if (tab == current) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setOnClickListener {
                when {
                    action != null -> action()
                    tab == null || tab == current -> Unit
                    tab == Tab.HOME -> activity.startActivity(Intent(activity, FeedActivity::class.java))
                    tab == Tab.LIBRARY -> activity.startActivity(Intent(activity, LibraryActivity::class.java))
                    tab == Tab.AUTO -> activity.startActivity(Intent(activity, AutoActivity::class.java))
                    tab == Tab.PROFILE -> activity.startActivity(Intent(activity, ProfileActivity::class.java))
                }
            }
        }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(235, 0, 0, 0))
            addView(item("⌂\nالرئيسية", Tab.HOME), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(item("▦\nالمكتبة", Tab.LIBRARY), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(item("＋", null, onAdd).apply {
                textSize = 28f
                setTextColor(Color.BLACK)
                setBackgroundColor(Color.WHITE)
                setPadding(dp(8), 0, dp(8), 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
            addView(item("◎\nAUTO", Tab.AUTO), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(item("◉\nأنا", Tab.PROFILE), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }
}
