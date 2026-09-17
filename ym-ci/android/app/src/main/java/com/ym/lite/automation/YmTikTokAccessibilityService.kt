package com.ym.lite.automation

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
    private var lastFinishedAt = 0L
    private var ignoreEventsUntil = 0L
    private var panelOpened = false

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
            if (!anyTikTokRootHasPanel()) beginComment("new_video")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        reloadFromPrefs()
        record("engine_ready", "محرك زر 4 الآمن جاهز")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !TikTokScope.isAllowed(event.packageName)) return
        if (!enabled || inFlight) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val now = SystemClock.uptimeMillis()
            if (now < ignoreEventsUntil) return
            handler.removeCallbacks(feedDebounce)
            handler.postDelayed(feedDebounce, 520L)
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
            record("auto_on", "زر 4 يعمل: تعليق من الدولاب فقط")
            handler.postDelayed({
                if (enabled && !inFlight && hasTikTokWindow() && !anyTikTokRootHasPanel()) {
                    beginComment("first_video")
                }
            }, 900L)
        }
    }

    private fun beginComment(reason: String) {
        if (!enabled || inFlight || comments.isEmpty() || !hasTikTokWindow()) return
        inFlight = true
        panelOpened = false
        textConfirmed = false
        pendingText = comments[commentIndex % comments.size]
        record("comment_start", "اختار الدولاب: ${pendingText.take(60)} | $reason")
        openComments(0)
    }

    private fun openComments(attempt: Int) {
        if (!enabled || !hasTikTokWindow()) {
            fail("tiktok_missing", "نافذة TikTok غير متاحة")
            return
        }
        val candidate = bestNodeAcrossTikTok(::commentButtonScore, 8)
        if (clickNode(candidate)) {
            panelOpened = true
            record("comment_opened", "تم فتح التعليقات؛ البحث عن خانة الكتابة")
            handler.postDelayed({ activateComposer(0) }, 520L)
            return
        }
        if (attempt < 5) handler.postDelayed({ openComments(attempt + 1) }, 240L)
        else fail("comment_button_missing", "تعذر العثور على زر التعليقات")
    }

    private fun activateComposer(attempt: Int) {
        val editor = findEditorAcrossTikTok()
        if (editor != null) {
            focusEditor(editor)
            record("editor_found", "تم العثور على محرر التعليق الحقيقي")
            handler.postDelayed({ writeComment(0) }, 160L)
            return
        }

        val entry = bestNodeAcrossTikTok(::composerEntryScore, 10)
        if (entry != null && attempt <= 7) {
            val clicked = clickNode(entry)
            record(
                if (clicked) "composer_clicked" else "composer_retry",
                if (clicked) "تم ضغط خانة إضافة تعليق" else "وجدت خانة إضافة تعليق ولم تُضغط بعد",
            )
            handler.postDelayed({ activateComposer(attempt + 1) }, 300L)
            return
        }

        if (attempt < 14) {
            record("editor_wait", "انتظار محرر التعليق ${attempt + 1}/15")
            handler.postDelayed({ activateComposer(attempt + 1) }, 260L)
        } else {
            fail("editor_missing", "فتح التعليقات لكن لم يجد خانة كتابة حقيقية؛ لن يضغط اقتراحات TikTok")
        }
    }

    private fun writeComment(attempt: Int) {
        val editor = findEditorAcrossTikTok()
        if (editor == null) {
            if (attempt < 5) handler.postDelayed({ activateComposer(attempt + 1) }, 220L)
            else fail("editor_lost", "اختفى محرر التعليق قبل الكتابة")
            return
        }

        focusEditor(editor)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pendingText)
        }
        if (editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            record("text_set", "أدخل YM تعليق الدولاب مباشرة")
            handler.postDelayed({ verifyWheelText(0) }, 260L)
            return
        }

        if (pasteInto(editor)) {
            record("text_paste", "أدخل YM تعليق الدولاب باللصق")
            handler.postDelayed({ verifyWheelText(0) }, 300L)
            return
        }

        if (attempt < 4) {
            record("write_retry", "إعادة محاولة كتابة تعليق الدولاب ${attempt + 1}/5")
            handler.postDelayed({ writeComment(attempt + 1) }, 240L)
        } else {
            fail("write_failed", "TikTok رفض كتابة تعليق الدولاب")
        }
    }

    private fun pasteInto(editor: AccessibilityNodeInfo): Boolean = runCatching {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("YM wheel comment", pendingText))
        focusEditor(editor)
        editor.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }.getOrDefault(false)

    private fun verifyWheelText(attempt: Int) {
        val editor = findEditorAcrossTikTok()
        if (editor == null) {
            if (attempt < 5) {
                handler.postDelayed({ verifyWheelText(attempt + 1) }, 220L)
            } else {
                fail("text_unverified", "لا يمكن تأكيد تعليق الدولاب داخل الحقل؛ الإرسال ممنوع")
            }
            return
        }

        val actual = normalize(editor.text?.toString().orEmpty())
        val expected = normalize(pendingText)
        if (actual == expected && expected.isNotEmpty()) {
            textConfirmed = true
            record("wheel_text_confirmed", "تم تأكيد تعليق الدولاب حرفيًا داخل TikTok")
            handler.postDelayed({ findAndSend(0) }, 160L)
            return
        }

        if (actual.isNotEmpty() && actual != expected) {
            fail("foreign_text_blocked", "ظهر نص ليس من الدولاب؛ تم منع إرساله")
            return
        }

        if (attempt < 3) handler.postDelayed({ writeComment(attempt + 1) }, 220L)
        else fail("wheel_text_missing", "تعليق الدولاب لم يظهر في الحقل؛ الإرسال ممنوع")
    }

    private fun findAndSend(attempt: Int) {
        if (!textConfirmed) {
            fail("send_blocked", "منع النشر لأن نص الدولاب غير مؤكد")
            return
        }

        val editor = findEditorAcrossTikTok()
        if (editor == null) {
            fail("editor_before_send_missing", "اختفى محرر التعليق قبل النشر")
            return
        }

        val actual = normalize(editor.text?.toString().orEmpty())
        val expected = normalize(pendingText)
        if (actual != expected || expected.isEmpty()) {
            fail("foreign_text_blocked", "تغير النص قبل النشر؛ تم منع الإرسال")
            return
        }

        val send = findSendNearEditor(editor)
            ?: bestNodeAcrossTikTok(::sendScore, 8)
            ?: findSendByGeometry(editor)

        if (send != null && clickNode(send)) {
            record("send_clicked", "تم ضغط نشر تعليق الدولاب فقط")
            handler.postDelayed({ verifySubmitted(0) }, 400L)
            return
        }

        if (attempt < 8) {
            record("send_wait", "انتظار زر النشر ${attempt + 1}/9")
            handler.postDelayed({ findAndSend(attempt + 1) }, 220L)
        } else {
            fail("send_missing", "تعذر العثور على زر النشر بدون نقر أعمى")
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
        if (attempt < 6) handler.postDelayed({ verifySubmitted(attempt + 1) }, 240L)
        else fail("send_unconfirmed", "بقي تعليق الدولاب داخل الحقل بعد الضغط على النشر")
    }

    private fun succeed() {
        commentIndex += 1
        localPrefs.edit().putInt("comment_index", commentIndex).apply()
        bump("stat_comment_ok")
        record("comment_ok", "تم نشر تعليق من الدولاب؛ إغلاق التعليقات")
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

        if (backCount > 0) {
            val roots = tiktokRoots()
            if (roots.isNotEmpty() && roots.none(::isPanelLikelyOpen)) {
                record(
                    if (success) "panel_closed" else "failure_panel_closed",
                    if (success) "تم نشر تعليق الدولاب وإغلاق التعليقات" else "تم إغلاق التعليقات بعد منع/فشل المحاولة",
                )
                resetFlight()
                return
            }
        }

        if (backCount >= 3) {
            record("panel_close_finished", "انتهت محاولات إغلاق لوحة المفاتيح/التعليقات")
            resetFlight()
            return
        }

        performGlobalAction(GLOBAL_ACTION_BACK)
        record("closing_panel", "رجوع ${backCount + 1}/3 لإغلاق لوحة المفاتيح/التعليقات")
        handler.postDelayed({ closeAfterAttempt(success, backCount + 1) }, 420L)
    }

    private fun resetFlight() {
        pendingText = ""
        textConfirmed = false
        inFlight = false
        panelOpened = false
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
    private fun anyTikTokRootHasPanel(): Boolean = tiktokRoots().any(::isPanelLikelyOpen)

    private fun findEditorAcrossTikTok(): AccessibilityNodeInfo? {
        tiktokRoots().forEach { root ->
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && editorScore(focused) >= 12) return focused
        }
        return bestNodeAcrossTikTok(::editorScore, 18)
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

    private fun isPanelLikelyOpen(root: AccessibilityNodeInfo): Boolean {
        if (findBestNode(root, ::editorScore, 18) != null) return true
        if (findBestNode(root, ::composerEntryScore, 10) != null) return true
        var evidence = 0
        walk(root, 450) { node ->
            val t = token(node)
            if (containsAny(t, "comments", "comment list", "reply", "replies", "kommentarer", "kommentar", "svara", "التعليقات", "تعليقات", "رد")) evidence++
        }
        return evidence >= 3
    }

    private fun commentButtonScore(node: AccessibilityNodeInfo): Int {
        if (!node.isEnabled || node.isEditable) return 0
        val t = token(node)
        var score = 0
        if (t.contains("read or add comments")) score += 45
        if (t.contains("view comments") || t.contains("open comments")) score += 38
        if (containsAny(t, "comments", "comment", "kommentarer", "kommentar", "تعليقات", "التعليقات", "تعليق")) score += 16
        if (node.viewIdResourceName?.contains("comment", true) == true) score += 20
        if (node.contentDescription?.toString()?.contains("comment", true) == true) score += 16
        if (node.isClickable) score += 5
        if (isButtonLike(node)) score += 3
        if (containsAny(t, "add comment", "add a comment", "write a comment", "comment here", "اكتب تعليق", "أضف تعليق")) score -= 26
        return score
    }

    private fun composerEntryScore(node: AccessibilityNodeInfo): Int {
        if (!node.isEnabled) return 0
        val t = token(node)
        var score = 0
        if (containsAny(t, "suggested", "suggestion", "quick reply", "recommended", "اقتراح", "مقترح")) return 0
        if (containsAny(t, "add comment", "add a comment", "write a comment", "write comment", "comment here", "say something", "kommentera", "skriv en kommentar", "lägg till kommentar", "أضف تعليق", "اكتب تعليق")) score += 38
        if (node.viewIdResourceName?.let { containsAny(it.lowercase(), "comment_input", "comment_editor", "comment_compose", "comment_text") } == true) score += 20
        if (node.isEditable) score += 18
        if (node.isFocusable) score += 4
        if (node.isClickable) score += 3
        return score
    }

    private fun editorScore(node: AccessibilityNodeInfo): Int {
        if (!node.isEnabled) return 0
        val t = token(node)
        if (containsAny(t, "suggested", "suggestion", "quick reply", "recommended", "اقتراح", "مقترح")) return 0
        var score = 0
        if (node.isEditable) score += 38
        if (node.className?.toString()?.contains("EditText", true) == true) score += 24
        if (supports(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) score += 20
        if (supports(node, AccessibilityNodeInfo.ACTION_PASTE)) score += 8
        if (node.isFocused) score += 8
        if (containsAny(t, "add comment", "add a comment", "write a comment", "write comment", "comment here", "say something", "kommentera", "skriv en kommentar", "lägg till kommentar", "أضف تعليق", "اكتب تعليق")) score += 18
        if (node.isFocusable) score += 3
        return score
    }

    private fun sendScore(node: AccessibilityNodeInfo): Int {
        if (!node.isEnabled || node.isEditable) return 0
        val t = token(node)
        val l = label(node)
        if (containsAny(t, "suggested", "suggestion", "quick reply", "recommended", "اقتراح", "مقترح")) return 0
        var score = 0
        if (containsAny(t, "send_comment", "post_comment", "submit_comment", "comment_send", "comment_post")) score += 44
        if (l in setOf("send", "post", "publish", "submit", "skicka", "publicera", "إرسال", "نشر")) score += 34
        if (containsAny(t, "send comment", "post comment", "publish comment", "submit comment", "skicka", "publicera", "إرسال", "نشر")) score += 18
        if (node.viewIdResourceName?.let { containsAny(it.lowercase(), "send", "post", "submit") } == true) score += 18
        if (node.isClickable) score += 5
        if (isButtonLike(node)) score += 3
        return score
    }

    private fun focusEditor(editor: AccessibilityNodeInfo) {
        editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (editor.isClickable || supports(editor, AccessibilityNodeInfo.ACTION_CLICK)) editor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findSendNearEditor(editor: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var parent: AccessibilityNodeInfo? = editor.parent
        repeat(7) {
            val current = parent ?: return@repeat
            val candidate = findBestNode(current, ::sendScore, 8)
            if (candidate != null && candidate !== editor) return candidate
            parent = current.parent
        }
        return null
    }

    private fun findSendByGeometry(editor: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val editorBounds = Rect()
        editor.getBoundsInScreen(editorBounds)
        if (editorBounds.isEmpty) return null
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        tiktokRoots().forEach { root ->
            walk(root, 800) { node ->
                if (!node.isEnabled || node.isEditable) return@walk
                if (!node.isClickable && !supports(node, AccessibilityNodeInfo.ACTION_CLICK)) return@walk
                val t = token(node)
                if (containsAny(t, "suggested", "suggestion", "quick reply", "recommended", "اقتراح", "مقترح", "emoji", "sticker", "gif", "mention", "camera", "photo", "image", "voice", "microphone", "audio")) return@walk
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.isEmpty) return@walk
                if (bounds.width() !in dp(14)..dp(110) || bounds.height() !in dp(14)..dp(110)) return@walk
                val vertical = abs(bounds.centerY() - editorBounds.centerY())
                if (vertical > dp(85)) return@walk
                val gap = when {
                    bounds.right <= editorBounds.left -> editorBounds.left - bounds.right
                    bounds.left >= editorBounds.right -> bounds.left - editorBounds.right
                    else -> return@walk
                }
                if (gap > dp(140)) return@walk
                val score = sendScore(node) * 5 + 260 - gap - vertical
                if (score > bestScore) {
                    bestScore = score
                    best = node
                }
            }
        }
        return best
    }

    private fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        var current = node ?: return false
        repeat(8) {
            if (current.isEnabled && supports(current, AccessibilityNodeInfo.ACTION_CLICK) && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            if (current.isEnabled && current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent ?: return false
        }
        return false
    }

    private fun findBestNode(root: AccessibilityNodeInfo, scorer: (AccessibilityNodeInfo) -> Int, minScore: Int): AccessibilityNodeInfo? {
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

    private fun supports(node: AccessibilityNodeInfo, actionId: Int): Boolean = node.actionList.any { it.id == actionId }

    private fun isButtonLike(node: AccessibilityNodeInfo): Boolean {
        val name = node.className?.toString().orEmpty().lowercase()
        return name.contains("button") || name.contains("image")
    }

    private fun label(node: AccessibilityNodeInfo): String = listOfNotNull(node.text, node.contentDescription, node.hintText)
        .joinToString(" ").lowercase().trim()

    private fun token(node: AccessibilityNodeInfo): String {
        val actions = node.actionList.mapNotNull { it.label?.toString() }.joinToString(" ")
        return listOfNotNull(node.text, node.contentDescription, node.hintText, node.viewIdResourceName, node.className, actions)
            .joinToString(" ").lowercase().trim()
    }

    private fun containsAny(text: String, vararg values: String): Boolean = values.any { text.contains(it, ignoreCase = true) }
    private fun normalize(text: String): String = text.trim().replace(Regex("\\s+"), " ")
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
