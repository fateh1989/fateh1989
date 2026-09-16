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
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.ym.lite.MainActivity
import com.ym.lite.core.AutomationGate
import kotlin.math.roundToInt

class YmAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: YmAccessibilityService? = null
        fun notifyConfigChanged() { instance?.reloadFromPrefs() }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("ym_auto", MODE_PRIVATE) }

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

    private var generation = 0L
    private var enteredTikTokThisRun = false
    private var armedAtMs = 0L

    private val visibilityLoop = object : Runnable {
        override fun run() {
            updateOverlayVisibility()
            handler.postDelayed(this, 400)
        }
    }

    private val automationLoop = object : Runnable {
        override fun run() { tickAutomation() }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        reloadFromPrefs()
        handler.removeCallbacks(visibilityLoop)
        handler.post(visibilityLoop)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        updateOverlayVisibility()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        generation++
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun reloadFromPrefs() {
        val nextEnabled = prefs.getBoolean("auto_enabled", false)
        if (nextEnabled && !enabled) {
            generation++
            enteredTikTokThisRun = false
            armedAtMs = SystemClock.elapsedRealtime()
        } else if (!nextEnabled && enabled) {
            generation++
            enteredTikTokThisRun = false
        }

        enabled = nextEnabled
        scrollEnabled = prefs.getBoolean("auto_scroll", true)
        autoComment = prefs.getBoolean("auto_comment", false)
        intervalMs = prefs.getInt("interval_sec", 8).coerceIn(3, 120) * 1000L
        commentEvery = prefs.getInt("comment_every", 3).coerceIn(1, 100)
        comments = prefs.getString("comment_pool", "").orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        handler.removeCallbacks(automationLoop)
        updateOverlayText()
        if (enabled) handler.postDelayed(automationLoop, 250)
    }

    private fun tickAutomation() {
        if (!prefs.getBoolean("auto_enabled", false)) {
            enabled = false
            generation++
            updateOverlayText()
            return
        }

        if (!enabled) reloadFromPrefs()
        if (!enabled) return

        if (!isTikTokForeground()) {
            if (enteredTikTokThisRun || SystemClock.elapsedRealtime() - armedAtMs > 7_000L) {
                stopAutomationBecauseTikTokLeft()
            } else {
                handler.postDelayed(automationLoop, 200)
            }
            return
        }

        enteredTikTokThisRun = true
        val token = generation
        val shouldComment = autoComment && comments.isNotEmpty() && ((scrollCount + 1) % commentEvery == 0)
        if (shouldComment) {
            attemptComment(token) {
                if (isActionAllowed(token)) {
                    handler.postDelayed({ swipeAndContinue(token) }, 700)
                }
            }
        } else {
            swipeAndContinue(token)
        }
    }

    private fun stopAutomationBecauseTikTokLeft() {
        generation++
        enabled = false
        enteredTikTokThisRun = false
        prefs.edit().putBoolean("auto_enabled", false).apply()
        handler.removeCallbacks(automationLoop)
        updateOverlayText()
        removeOverlay()
    }

    private fun currentForegroundPackage(): String? =
        rootInActiveWindow?.packageName?.toString()

    private fun isTikTokForeground(): Boolean =
        AutomationGate.isTikTokPackage(currentForegroundPackage())

    private fun isActionAllowed(token: Long): Boolean =
        token == generation && enabled &&
            AutomationGate.mayAutomate(true, currentForegroundPackage())

    private fun swipeAndContinue(token: Long) {
        if (!isActionAllowed(token)) {
            stopAutomationBecauseTikTokLeft()
            return
        }

        if (!scrollEnabled) {
            scheduleNext(token)
            return
        }

        val dm = resources.displayMetrics
        val path = Path().apply {
            moveTo(dm.widthPixels / 2f, dm.heightPixels * 0.78f)
            lineTo(dm.widthPixels / 2f, dm.heightPixels * 0.22f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 360))
            .build()

        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (!isActionAllowed(token)) {
                        stopAutomationBecauseTikTokLeft()
                        return
                    }
                    scrollCount++
                    updateOverlayText()
                    scheduleNext(token)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (isActionAllowed(token)) scheduleNext(token, 700L)
                }
            },
            null,
        )

        if (!accepted && isActionAllowed(token)) scheduleNext(token, 700L)
    }

    private fun scheduleNext(token: Long, delayMs: Long = intervalMs) {
        if (!isActionAllowed(token)) return
        handler.removeCallbacks(automationLoop)
        handler.postDelayed(automationLoop, delayMs)
    }

    private fun attemptComment(token: Long, done: () -> Unit) {
        if (!isActionAllowed(token)) return
        val root = rootInActiveWindow ?: run { done(); return }
        val commentButton = findNode(root) { node ->
            val label = nodeLabel(node)
            label.contains("comment") || label.contains("تعليق") || label.contains("kommentar")
        }
        if (!clickNodeSafely(commentButton, token)) {
            done()
            return
        }

        handler.postDelayed({
            if (!isActionAllowed(token)) return@postDelayed
            val editor = rootInActiveWindow?.let { newRoot ->
                findNode(newRoot) { node ->
                    node.isEditable || node.className?.toString()?.contains("EditText") == true
                }
            }
            if (editor == null) {
                safeBack(token)
                done()
                return@postDelayed
            }

            if (!isActionAllowed(token)) return@postDelayed
            val text = comments.getOrNull(commentIndex % comments.size) ?: run { done(); return@postDelayed }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (!isActionAllowed(token)) return@postDelayed
            editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            handler.postDelayed({
                if (!isActionAllowed(token)) return@postDelayed
                val send = rootInActiveWindow?.let { sendRoot ->
                    findNode(sendRoot) { node ->
                        val label = nodeLabel(node)
                        label == "send" || label.contains("post") || label.contains("إرسال") ||
                            label.contains("نشر") || label.contains("skicka")
                    }
                }
                if (clickNodeSafely(send, token)) {
                    commentIndex++
                    handler.postDelayed({
                        if (!isActionAllowed(token)) return@postDelayed
                        safeBack(token)
                        done()
                    }, 450)
                } else {
                    safeBack(token)
                    done()
                }
            }, 350)
        }, 750)
    }

    private fun safeBack(token: Long) {
        if (isActionAllowed(token)) performGlobalAction(GLOBAL_ACTION_BACK)
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

    private fun clickNodeSafely(node: AccessibilityNodeInfo?, token: Long): Boolean {
        var current = node ?: return false
        repeat(5) {
            if (!isActionAllowed(token)) return false
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent ?: return false
        }
        return false
    }

    private fun updateOverlayVisibility() {
        if (isTikTokForeground()) ensureOverlay() else removeOverlay()
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
            setPadding(
                (8 * density).roundToInt(),
                (7 * density).roundToInt(),
                (8 * density).roundToInt(),
                (7 * density).roundToInt(),
            )
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
        if (!isTikTokForeground()) return
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
            if (!isTikTokForeground()) return@bubble
            val value = !prefs.getBoolean("auto_enabled", false)
            prefs.edit().putBoolean("auto_enabled", value).apply()
            reloadFromPrefs()
        }.also { menu.addView(it) }

        commentBubble = bubble("تعليق", Color.rgb(254, 44, 85)) {
            if (!isTikTokForeground()) return@bubble
            val value = !prefs.getBoolean("auto_comment", false)
            prefs.edit().putBoolean("auto_comment", value).apply()
            reloadFromPrefs()
        }.also { menu.addView(it) }

        menu.addView(bubble("ضبط", Color.WHITE) {
            if (!isTikTokForeground()) return@bubble
            prefs.edit().putBoolean("auto_enabled", false).apply()
            reloadFromPrefs()
            removeOverlay()
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        })

        mainBubble = bubble("YM", Color.rgb(254, 44, 85)) {
            if (!isTikTokForeground()) return@bubble
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

        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(root, lp)
            overlayRoot = root
            overlayMenu = menu
            updateOverlayText()
        }
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
