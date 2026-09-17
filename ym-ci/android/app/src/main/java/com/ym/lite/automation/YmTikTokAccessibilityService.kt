package com.ym.lite.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.roundToInt

class YmTikTokAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: YmTikTokAccessibilityService? = null
        fun notifyConfigChanged() { instance?.reloadFromPrefs() }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val autoPrefs by lazy { getSharedPreferences("ym_auto_comment", MODE_PRIVATE) }
    private val localPrefs by lazy { getSharedPreferences("ym_local", MODE_PRIVATE) }

    private var enabled = false
    private var comments: List<String> = emptyList()
    private var commentIndex = 0
    private var minIntervalMs = 3_000L
    private var inFlight = false
    private var pendingText = ""
    private var textConfirmed = false
    private var panelOpened = false
    private var editorActivated = false
    private var composerTapTried = false
    private var sendTapStep = 0
    private var lastFinishedAt = 0L
    private var ignoreEventsUntil = 0L

    private val feedDebounce = object : Runnable {
        override fun run() {
            if (!enabled || inFlight || !hasTikTokWindow()) return
            val now = SystemClock.uptimeMillis()
            if (now < ignoreEventsUntil) return
            val wait = (lastFinishedAt + minIntervalMs - now).coerceAtLeast(0L)
            if (wait > 0L) {
                handler.postDelayed(this, wait)
                return
            }
            if (!strictCommentPanelOpen()) beginComment("new_video")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        reloadFromPrefs()
        record("engine_ready", "محرك زر 4 جاهز")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !TikTokScope.isAllowed(event.packageName)) return
        if (!enabled || inFlight) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val now = SystemClock.uptimeMillis()
            if (now < ignoreEventsUntil) return
            handler.removeCallbacks(feedDebounce)
            handler.postDelayed(feedDebounce, 500L)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun reloadFromPrefs() {
        val wasEnabled = enabled
        enabled = autoPrefs.getBoolean("enabled", false)
        minIntervalMs = autoPrefs.getInt("min_interval_seconds", 3).coerceIn(2, 8) * 1_000L
        comments = localPrefs.getString("comment_pool", "").orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
            .ifEmpty { YmCommentDefaults.all }
        commentIndex = localPrefs.getInt("comment_index", 0).coerceAtLeast(0)

        handler.removeCallbacks(feedDebounce)
        if (!enabled) {
            resetFlight()
            record("auto_off", "زر 4 متوقف")
            return
        }

        if (!wasEnabled) {
            record("auto_on", "زر 4 يعمل: بدء محاولة تعليق مباشرة")
            handler.postDelayed({
                if (enabled && !inFlight && hasTikTokWindow()) beginComment("button4_on")
            }, 850L)
        }
    }

    private fun beginComment(reason: String) {
        if (!enabled || inFlight || comments.isEmpty() || !hasTikTokWindow()) return
        inFlight = true
        pendingText = comments[commentIndex % comments.size]
        textConfirmed = false
        panelOpened = false
        editorActivated = false
        composerTapTried = false
        sendTapStep = 0
        record("comment_start", "بدء تعليق من الدولاب: $reason")
        openComments(0)
    }

    private fun openComments(attempt: Int) {
        if (!enabled || !hasTikTokWindow()) {
            fail("tiktok_missing", "نافذة TikTok غير متاحة")
            return
        }

        val button = bestNodeAcrossTikTok(::commentButtonScore, 12)
        if (clickNode(button)) {
            panelOpened = true
            record("comment_opened", "تم فتح نافذة التعليقات")
            handler.postDelayed({ activateComposer(0) }, 520L)
            return
        }

        if (attempt < 5) {
            handler.postDelayed({ openComments(attempt + 1) }, 240L)
        } else {
            fail("comment_button_missing", "لم يجد YM زر التعليقات الجانبي")
        }
    }

    private fun activateComposer(attempt: Int) {
        if (!enabled || !hasTikTokWindow()) {
            fail("tiktok_missing", "اختفت نافذة TikTok بعد فتح التعليقات")
            return
        }

        val editor = findEditorAcrossTikTok()
        if (editor != null) {
            editorActivated = true
            focusEditor(editor)
            record("editor_found", "تم العثور على حقل كتابة حقيقي")
            handler.postDelayed({ writeComment(0) }, 160L)
            return
        }

        val entry = bestNodeAcrossTikTok(::composerEntryScore, 14)
        if (entry != null && attempt <= 6) {
            val clicked = clickNode(entry)
            if (clicked) editorActivated = true
            record(
                if (clicked) "composer_clicked" else "composer_retry",
                if (clicked) "تم ضغط خانة إضافة تعليق" else "وجد YM خانة التعليق ولم تُضغط بعد",
            )
            handler.postDelayed({ activateComposer(attempt + 1) }, 300L)
            return
        }

        if (!composerTapTried && attempt >= 2) {
            composerTapTried = true
            editorActivated = true
            val dm = resources.displayMetrics
            val x = dm.widthPixels * 0.50f
            val y = dm.heightPixels * 0.92f
            record("composer_direct_tap", "نقر مباشر على شريط إضافة تعليق أسفل النافذة")
            dispatchTap(x, y) {
                handler.postDelayed({ activateComposer(attempt + 1) }, 420L)
            }
            return
        }

        if (attempt < 14) {
            handler.postDelayed({ activateComposer(attempt + 1) }, 250L)
        } else {
            fail("editor_missing", "فتحت التعليقات لكن لم يظهر محرر نص قابل للكتابة")
        }
    }

    private fun writeComment(attempt: Int) {
        val editor = findEditorAcrossTikTok()
        if (editor == null) {
            if (attempt < 5) {
                handler.postDelayed({ activateComposer(attempt + 1) }, 220L)
            } else {
                fail("editor_lost", "اختفى حقل التعليق قبل الكتابة")
            }
            return
        }

        editorActivated = true
        focusEditor(editor)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pendingText)
        }
        if (editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            record("text_set", "تم إدخال تعليق الدولاب مباشرة")
            handler.postDelayed({ verifyText(0) }, 260L)
            return
        }

        if (pasteInto(editor)) {
            record("text_paste", "تم إدخال تعليق الدولاب بالنسخ واللصق")
            handler.postDelayed({ verifyText(0) }, 300L)
            return
        }

        if (attempt < 4) {
            handler.postDelayed({ writeComment(attempt + 1) }, 230L)
        } else {
            fail("write_failed", "TikTok رفض الكتابة المباشرة واللصق")
        }
    }

    private fun pasteInto(editor: AccessibilityNodeInfo): Boolean = runCatching {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("YM comment", pendingText))
        focusEditor(editor)
        editor.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }.getOrDefault(false)

    private fun verifyText(attempt: Int) {
        val editor = findEditorAcrossTikTok()
        if (editor == null) {
            if (attempt < 5) handler.postDelayed({ verifyText(attempt + 1) }, 220L)
            else fail("text_unverified", "لم يستطع YM تأكيد النص داخل الحقل")
            return
        }

        val actual = normalize(editor.text?.toString().orEmpty())
        val expected = normalize(pendingText)
        if (actual == expected && expected.isNotEmpty()) {
            textConfirmed = true
            record("text_confirmed", "تم تأكيد تعليق الدولاب داخل TikTok")
            handler.postDelayed({ sendComment(0) }, 160L)
            return
        }

        if (actual.isNotEmpty() && actual != expected) {
            fail("foreign_text", "ظهر نص مختلف عن تعليق الدولاب؛ لن يتم إرساله")
            return
        }

        if (attempt < 3) handler.postDelayed({ writeComment(attempt + 1) }, 220L)
        else fail("text_missing", "تعليق الدولاب لم يظهر في الحقل")
    }

    private fun sendComment(attempt: Int) {
        if (!textConfirmed) {
            fail("send_blocked", "منع الإرسال لأن النص غير مؤكد")
            return
        }

        val editor = findEditorAcrossTikTok()
        if (editor == null) {
            fail("editor_before_send_missing", "اختفى حقل التعليق قبل الإرسال")
            return
        }

        val actual = normalize(editor.text?.toString().orEmpty())
        if (actual != normalize(pendingText)) {
            fail("text_changed", "تغير النص قبل النشر")
            return
        }

        val send = findSendNearEditor(editor)
            ?: bestNodeAcrossTikTok(::sendScore, 10)
            ?: findSendByGeometry(editor)

        if (send != null && clickNode(send)) {
            record("send_clicked", "تم ضغط زر نشر التعليق")
            handler.postDelayed({ verifySubmitted(0) }, 400L)
            return
        }

        if (attempt >= 2) {
            directSend(editor, leftSide = true)
            return
        }

        handler.postDelayed({ sendComment(attempt + 1) }, 220L)
    }

    private fun directSend(editor: AccessibilityNodeInfo, leftSide: Boolean) {
        if (!textConfirmed || !inFlight) return
        val bounds = Rect()
        editor.getBoundsInScreen(bounds)
        val dm = resources.displayMetrics
        val x = dm.widthPixels * if (leftSide) 0.08f else 0.92f
        val y = if (!bounds.isEmpty) bounds.centerY().toFloat() else dm.heightPixels * 0.92f
        sendTapStep = if (leftSide) 1 else 2
        record(
            if (leftSide) "send_tap_left" else "send_tap_right",
            if (leftSide) "تجربة زر الإرسال يسار حقل التعليق" else "تجربة زر الإرسال يمين حقل التعليق",
        )
        dispatchTap(x, y) {
            handler.postDelayed({ verifySubmitted(0) }, 420L)
        }
    }

    private fun verifySubmitted(attempt: Int) {
        val editor = findEditorAcrossTikTok()
        if (editor == null) {
            succeed()
            return
        }

        val actual = normalize(editor.text?.toString().orEmpty())
        val expected = normalize(pendingText)
        if (actual.isBlank() || actual != expected) {
            succeed()
            return
        }

        if (attempt < 4) {
            handler.postDelayed({ verifySubmitted(attempt + 1) }, 240L)
            return
        }

        when (sendTapStep) {
            0 -> directSend(editor, leftSide = true)
            1 -> directSend(editor, leftSide = false)
            else -> fail("send_unconfirmed", "بقي النص داخل الحقل بعد محاولات الإرسال")
        }
    }

    private fun succeed() {
        commentIndex += 1
        localPrefs.edit().putInt("comment_index", commentIndex).apply()
        bump("stat_comment_ok")
        record("comment_ok", "تم نشر تعليق من الدولاب")
        lastFinishedAt = SystemClock.uptimeMillis()
        ignoreEventsUntil = lastFinishedAt + 1_500L
        closeAfterAttempt(success = true, backCount = 0)
    }

    private fun fail(code: String, message: String) {
        bump("stat_comment_fail")
        record(code, message)
        lastFinishedAt = SystemClock.uptimeMillis()
        ignoreEventsUntil = lastFinishedAt + 1_200L
        closeAfterAttempt(success = false, backCount = 0)
    }

    private fun closeAfterAttempt(success: Boolean, backCount: Int) {
        if (!panelOpened) {
            resetFlight()
            return
        }

        if (backCount > 0 && !strictCommentPanelOpen()) {
            record(
                if (success) "panel_closed" else "failure_panel_closed",
                if (success) "تم النشر وإغلاق نافذة التعليقات" else "تم إغلاق نافذة التعليقات بعد فشل المحاولة",
            )
            resetFlight()
            return
        }

        val maxBacks = if (editorActivated) 3 else 2
        if (backCount >= maxBacks) {
            record("panel_close_finished", "انتهت محاولات إغلاق لوحة المفاتيح/التعليقات")
            resetFlight()
            return
        }

        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({ closeAfterAttempt(success, backCount + 1) }, 420L)
    }

    private fun resetFlight() {
        pendingText = ""
        textConfirmed = false
        inFlight = false
        panelOpened = false
        editorActivated = false
        composerTapTried = false
        sendTapStep = 0
    }

    private fun tiktokRoots(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val active = rootInActiveWindow
        if (active != null && TikTokScope.isAllowed(active.packageName)) result += active
        windows.forEach { window ->
            val root = runCatching { window.root }.getOrNull() ?: return@forEach
            if (TikTokScope.isAllowed(root.packageName) && result.none { it === root }) result += root
        }
        return result
    }

    private fun hasTikTokWindow(): Boolean = tiktokRoots().isNotEmpty()

    private fun strictCommentPanelOpen(): Boolean {
        if (findEditorAcrossTikTok() != null) return true
        return bestNodeAcrossTikTok(::composerEntryScore, 14) != null
    }

    private fun findEditorAcrossTikTok(): AccessibilityNodeInfo? {
        tiktokRoots().forEach { root ->
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && editorScore(focused) >= 20) return focused
        }
        return bestNodeAcrossTikTok(::editorScore, 24)
    }

    private fun bestNodeAcrossTikTok(
        scorer: (AccessibilityNodeInfo) -> Int,
        minScore: Int,
    ): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = minScore - 1
        tiktokRoots().forEach { root ->
            walk(root, 800) { node ->
                val score = scorer(node)
                if (score > bestScore) {
                    bestScore = score
                    best = node
                }
            }
        }
        return best
    }

    private fun commentButtonScore(node: AccessibilityNodeInfo): Int {
        if (!node.isEnabled || node.isEditable) return 0
        val t = token(node)
        var score = 0
        if (t.contains("read or add comments")) score += 60
        if (t.contains("view comments") || t.contains("open comments")) score += 50
        if (containsAny(t, "comments", "comment", "kommentarer", "kommentar", "تعليقات", "التعليقات", "تعليق")) score += 18
        if (node.viewIdResourceName?.contains("comment", true) == true) score += 22
        if (node.contentDescription?.toString()?.contains("comment", true) == true) score += 18
        if (node.isClickable || supports(node, AccessibilityNodeInfo.ACTION_CLICK)) score += 6
        if (isButtonLike(node)) score += 4

        val b = Rect()
        node.getBoundsInScreen(b)
        if (!b.isEmpty) {
            val dm = resources.displayMetrics
            val side = b.centerX() < dm.widthPixels * 0.30f || b.centerX() > dm.widthPixels * 0.70f
            val middle = b.centerY() > dm.heightPixels * 0.25f && b.centerY() < dm.heightPixels * 0.90f
            if (side && middle) score += 12
        }
        return score
    }

    private fun composerEntryScore(node: AccessibilityNodeInfo): Int {
        if (!node.isEnabled) return 0
        val t = token(node)
        if (containsAny(t, "read or add comments", "view comments", "open comments")) return 0
        if (containsAny(t, "suggested", "suggestion", "quick reply", "recommended", "اقتراح", "مقترح")) return 0

        val structural = node.isEditable ||
            node.className?.toString()?.contains("EditText", true) == true ||
            supports(node, AccessibilityNodeInfo.ACTION_SET_TEXT) ||
            supports(node, AccessibilityNodeInfo.ACTION_PASTE)

        val b = Rect()
        node.getBoundsInScreen(b)
        val dm = resources.displayMetrics
        val bottomWide = !b.isEmpty &&
            b.centerY() > dm.heightPixels * 0.58f &&
            b.width() > dm.widthPixels * 0.24f

        if (!structural && !bottomWide) return 0

        var score = 0
        if (containsAny(t, "add comment", "add a comment", "write a comment", "write comment", "comment here", "say something", "kommentera", "skriv en kommentar", "lägg till kommentar", "أضف تعليق", "اكتب تعليق")) score += 36
        if (node.viewIdResourceName?.let { containsAny(it.lowercase(), "comment_input", "comment_editor", "comment_compose", "comment_text") } == true) score += 24
        if (structural) score += 24
        if (bottomWide) score += 14
        if (node.isFocusable) score += 3
        if (node.isClickable) score += 2
        return score
    }

    private fun editorScore(node: AccessibilityNodeInfo): Int {
        if (!node.isEnabled) return 0
        val t = token(node)
        if (containsAny(t, "read or add comments", "view comments", "open comments")) return 0
        if (containsAny(t, "suggested", "suggestion", "quick reply", "recommended", "اقتراح", "مقترح")) return 0

        val structural = node.isEditable ||
            node.className?.toString()?.contains("EditText", true) == true ||
            supports(node, AccessibilityNodeInfo.ACTION_SET_TEXT) ||
            supports(node, AccessibilityNodeInfo.ACTION_PASTE)
        if (!structural) return 0

        val b = Rect()
        node.getBoundsInScreen(b)
        val dm = resources.displayMetrics
        val bottom = b.isEmpty || b.centerY() > dm.heightPixels * 0.52f || node.isFocused
        if (!bottom) return 0

        var score = 30
        if (node.isEditable) score += 36
        if (node.className?.toString()?.contains("EditText", true) == true) score += 24
        if (supports(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) score += 20
        if (supports(node, AccessibilityNodeInfo.ACTION_PASTE)) score += 8
        if (node.isFocused) score += 10
        if (containsAny(t, "add comment", "add a comment", "write a comment", "write comment", "comment here", "say something", "kommentera", "skriv en kommentar", "lägg till kommentar", "أضف تعليق", "اكتب تعليق")) score += 14
        return score
    }

    private fun sendScore(node: AccessibilityNodeInfo): Int {
        if (!node.isEnabled || node.isEditable) return 0
        val t = token(node)
        val l = label(node)
        if (containsAny(t, "suggested", "suggestion", "quick reply", "recommended", "اقتراح", "مقترح")) return 0
        var score = 0
        if (containsAny(t, "send_comment", "post_comment", "submit_comment", "comment_send", "comment_post")) score += 44
        if (l in setOf("send", "post", "publish", "submit", "skicka", "publicera", "إرسال", "نشر")) score += 36
        if (containsAny(t, "send comment", "post comment", "publish comment", "submit comment", "skicka", "publicera", "إرسال", "نشر")) score += 18
        if (node.viewIdResourceName?.let { containsAny(it.lowercase(), "send", "post", "submit") } == true) score += 18
        if (node.isClickable || supports(node, AccessibilityNodeInfo.ACTION_CLICK)) score += 5
        if (isButtonLike(node)) score += 3
        return score
    }

    private fun focusEditor(editor: AccessibilityNodeInfo) {
        editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (editor.isClickable || supports(editor, AccessibilityNodeInfo.ACTION_CLICK)) {
            editor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    private fun findSendNearEditor(editor: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var parent: AccessibilityNodeInfo? = editor.parent
        repeat(7) {
            val current = parent ?: return@repeat
            val candidate = findBestNode(current, ::sendScore, 10)
            if (candidate != null && candidate !== editor) return candidate
            parent = current.parent
        }
        return null
    }

    private fun findSendByGeometry(editor: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val eb = Rect()
        editor.getBoundsInScreen(eb)
        if (eb.isEmpty) return null

        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        tiktokRoots().forEach { root ->
            walk(root, 800) { node ->
                if (!node.isEnabled || node.isEditable) return@walk
                if (!node.isClickable && !supports(node, AccessibilityNodeInfo.ACTION_CLICK)) return@walk
                val t = token(node)
                if (containsAny(t, "emoji", "sticker", "gif", "mention", "camera", "photo", "image", "voice", "microphone", "audio")) return@walk
                val b = Rect()
                node.getBoundsInScreen(b)
                if (b.isEmpty) return@walk
                val vertical = abs(b.centerY() - eb.centerY())
                if (vertical > dp(85)) return@walk
                val gap = when {
                    b.right <= eb.left -> eb.left - b.right
                    b.left >= eb.right -> b.left - eb.right
                    else -> return@walk
                }
                if (gap > dp(140)) return@walk
                val score = 260 - gap - vertical + sendScore(node) * 4
                if (score > bestScore) {
                    bestScore = score
                    best = node
                }
            }
        }
        return best
    }

    private fun dispatchTap(x: Float, y: Float, after: () -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 70L))
            .build()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                after()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                after()
            }
        }
        if (!dispatchGesture(gesture, callback, handler)) after()
    }

    private fun findBestNode(
        root: AccessibilityNodeInfo,
        scorer: (AccessibilityNodeInfo) -> Int,
        minScore: Int,
    ): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = minScore - 1
        walk(root, 800) { node ->
            val score = scorer(node)
            if (score > bestScore) {
                bestScore = score
                best = node
            }
        }
        return best
    }

    private fun walk(root: AccessibilityNodeInfo, limit: Int, block: (AccessibilityNodeInfo) -> Unit) {
        var visited = 0
        fun visit(node: AccessibilityNodeInfo) {
            if (visited >= limit) return
            visited++
            block(node)
            for (i in 0 until node.childCount) {
                if (visited >= limit) return
                node.getChild(i)?.let(::visit)
            }
        }
        visit(root)
    }

    private fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        var current = node ?: return false
        repeat(8) {
            if (current.isEnabled && supports(current, AccessibilityNodeInfo.ACTION_CLICK) && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            if (current.isClickable && current.isEnabled && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent ?: return false
        }
        return false
    }

    private fun supports(node: AccessibilityNodeInfo, actionId: Int): Boolean =
        node.actionList.any { it.id == actionId }

    private fun isButtonLike(node: AccessibilityNodeInfo): Boolean {
        val name = node.className?.toString().orEmpty().lowercase()
        return name.contains("button") || name.contains("image")
    }

    private fun containsAny(text: String, vararg values: String): Boolean =
        values.any { text.contains(it, ignoreCase = true) }

    private fun label(node: AccessibilityNodeInfo): String =
        listOfNotNull(node.text, node.contentDescription, node.hintText)
            .joinToString(" ")
            .lowercase()
            .trim()

    private fun token(node: AccessibilityNodeInfo): String {
        val actions = node.actionList.mapNotNull { it.label?.toString() }.joinToString(" ")
        return listOfNotNull(node.text, node.contentDescription, node.hintText, node.viewIdResourceName, node.className, actions)
            .joinToString(" ")
            .lowercase()
            .trim()
    }

    private fun normalize(text: String): String = text
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun bump(key: String) {
        localPrefs.edit().putInt(key, localPrefs.getInt(key, 0) + 1).apply()
    }

    private fun record(code: String, message: String) {
        localPrefs.edit()
            .putString("last_auto_stage", code)
            .putString("last_auto_action", message)
            .putLong("last_auto_stage_at", System.currentTimeMillis())
            .apply()
    }
}
