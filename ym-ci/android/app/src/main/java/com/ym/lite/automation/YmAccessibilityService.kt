package com.ym.lite.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.ym.lite.MainActivity
import kotlin.math.roundToInt

class YmAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: YmAccessibilityService? = null
        fun notifyConfigChanged() { instance?.reloadFromPrefs() }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("ym_auto", MODE_PRIVATE) }
    private var activePackage = ""
    private var overlayRoot: LinearLayout? = null
    private var overlayMenu: LinearLayout? = null
    private var mainBubble: TextView? = null
    private var autoBubble: TextView? = null
    private var commentBubble: TextView? = null
    private var scrollCount = 0
    private var commentIndex = 0

    private var enabled = false
    private var scrollEnabled = true
    private var autoComment = false
    private var intervalMs = 8_000L
    private var commentEvery = 3
    private var comments: List<String> = emptyList()

    private val loop = object : Runnable {
        override fun run() { tick() }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        reloadFromPrefs()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        activePackage = event?.packageName?.toString().orEmpty()
        updateOverlayVisibility()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun reloadFromPrefs() {
        enabled = prefs.getBoolean("auto_enabled", false)
        scrollEnabled = prefs.getBoolean("auto_scroll", true)
        autoComment = prefs.getBoolean("auto_comment", false)
        intervalMs = prefs.getInt("interval_sec", 8).coerceIn(3, 120) * 1000L
        commentEvery = prefs.getInt("comment_every", 3).coerceIn(1, 100)
        comments = prefs.getString("comment_pool", "").orEmpty()
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        handler.removeCallbacks(loop)
        updateOverlayText()
        if (enabled) handler.postDelayed(loop, 800)
    }

    private fun tick() {
        reloadRuntimeOnly()
        if (!enabled) return
        if (!isTikTokActive()) {
            handler.postDelayed(loop, 1_500)
            return
        }

        val shouldComment = autoComment && comments.isNotEmpty() && ((scrollCount + 1) % commentEvery == 0)
        if (shouldComment) {
            attemptComment { handler.postDelayed({ swipeAndContinue() }, 700) }
        } else {
            swipeAndContinue()
        }
    }

    private fun reloadRuntimeOnly() {
        enabled = prefs.getBoolean("auto_enabled", false)
        scrollEnabled = prefs.getBoolean("auto_scroll", true)
        autoComment = prefs.getBoolean("auto_comment", false)
    }

    private fun swipeAndContinue() {
        if (!enabled) return
        if (scrollEnabled) {
            val dm = resources.displayMetrics
            val x = dm.widthPixels / 2f
            val startY = dm.heightPixels * 0.78f
            val endY = dm.heightPixels * 0.22f
            val path = Path().apply {
                moveTo(x, startY)
                lineTo(x, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 360))
                .build()
            dispatchGesture(gesture, null, null)
            scrollCount++
            updateOverlayText()
        }
        handler.postDelayed(loop, intervalMs)
    }

    private fun attemptComment(done: () -> Unit) {
        val root = rootInActiveWindow ?: run { done(); return }
        val commentButton = findNode(root) { node ->
            val label = nodeLabel(node)
            label.contains("comment") || label.contains("تعليق") || label.contains("kommentar")
        }
        if (!clickNode(commentButton)) {
            done()
            return
        }

        handler.postDelayed({
            val newRoot = rootInActiveWindow
            val editor = newRoot?.let {
                findNode(it) { node ->
                    node.isEditable || node.className?.toString()?.contains("EditText") == true
                }
            }
            if (editor == null) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                done()
                return@postDelayed
            }

            val text = comments[commentIndex % comments.size]
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            handler.postDelayed({
                val sendRoot = rootInActiveWindow
                val send = sendRoot?.let {
                    findNode(it) { node ->
                        val label = nodeLabel(node)
                        label == "send" || label.contains("post") || label.contains("إرسال") ||
                            label.contains("نشر") || label.contains("skicka")
                    }
                }
                if (clickNode(send)) {
                    commentIndex++
                    handler.postDelayed({
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        done()
                    }, 450)
                } else {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    done()
                }
            }, 350)
        }, 750)
    }

    private fun findNode(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (predicate(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNode(child, predicate)
            if (found != null) return found
        }
        return null
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String =
        listOfNotNull(node.text, node.contentDescription)
            .joinToString(" ").lowercase().trim()

    private fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        var current = node ?: return false
        repeat(5) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent ?: return false
        }
        return false
    }

    private fun isTikTokPackage(packageName: String?): Boolean =
        packageName == "com.zhiliaoapp.musically" || packageName == "com.ss.android.ugc.trill"

    private fun isTikTokActive(): Boolean {
        val rootPackage = rootInActiveWindow?.packageName?.toString()
        return isTikTokPackage(rootPackage) || isTikTokPackage(activePackage)
    }

    private fun updateOverlayVisibility() {
        if (isTikTokActive()) ensureOverlay() else removeOverlay()
    }

    private fun bubble(label: String, accent: Int, onClick: () -> Unit): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 11f
            minWidth = (54 * density).roundToInt()
            minHeight = (44 * density).roundToInt()
            setPadding((8 * density).roundToInt(), (7 * density).roundToInt(), (8 * density).roundToInt(), (7 * density).roundToInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 22 * density
                setColor(Color.argb(225, 12, 12, 12))
                setStroke((2 * density).roundToInt(), accent)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun ensureOverlay() {
        if (overlayRoot != null) {
            updateOverlayText()
            return
        }

        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            visibility = View.GONE
        }

        autoBubble = bubble("AUTO", Color.rgb(37, 244, 238)) {
            val value = !prefs.getBoolean("auto_enabled", false)
            prefs.edit().putBoolean("auto_enabled", value).apply()
            reloadFromPrefs()
        }.also { menu.addView(it) }

        commentBubble = bubble("تعليق", Color.rgb(254, 44, 85)) {
            val value = !prefs.getBoolean("auto_comment", false)
            prefs.edit().putBoolean("auto_comment", value).apply()
            reloadFromPrefs()
        }.also { menu.addView(it) }

        menu.addView(bubble("ضبط", Color.WHITE) {
            removeOverlay()
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        })

        mainBubble = bubble("YM", Color.rgb(254, 44, 85)) {
            menu.visibility = if (menu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        root.addView(menu)
        root.addView(mainBubble)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = (8 * density).roundToInt()
        }

        (getSystemService(WINDOW_SERVICE) as WindowManager).addView(root, lp)
        overlayRoot = root
        overlayMenu = menu
        updateOverlayText()
    }

    private fun updateOverlayText() {
        mainBubble?.text = if (prefs.getBoolean("auto_enabled", false)) "YM\n● $scrollCount" else "YM\n○"
        autoBubble?.text = if (prefs.getBoolean("auto_enabled", false)) "AUTO ●" else "AUTO ○"
        commentBubble?.text = if (prefs.getBoolean("auto_comment", false)) "تعليق ●" else "تعليق ○"
    }

    private fun removeOverlay() {
        val view = overlayRoot ?: return
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) }
        overlayRoot = null
        overlayMenu = null
        mainBubble = null
        autoBubble = null
        commentBubble = null
    }
}
