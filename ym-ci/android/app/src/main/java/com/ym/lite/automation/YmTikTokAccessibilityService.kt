package com.ym.lite.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
    private var commentInFlight = false
    private var pendingCommentText = ""
    private var lastActionFinishedAt = 0L
    private var ignoreEventsUntil = 0L

    private val feedDebounce = object : Runnable {
        override fun run() {
            if (!enabled || commentInFlight || !isTikTokActive()) return
            val root = currentTikTokRoot() ?: return
            if (isCommentPanelOpen(root)) return

            val now = SystemClock.uptimeMillis()
            if (now < ignoreEventsUntil) return
            val wait = (lastActionFinishedAt + minIntervalMs - now).coerceAtLeast(0L)
            if (wait > 0L) {
                handler.postDelayed(this, wait)
                return
            }
            startComment("new_video")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        reloadFromPrefs()
        recordStage("engine_ready", "محرك زر 4 جاهز")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !TikTokScope.isAllowed(event.packageName)) return
        if (!enabled || commentInFlight) return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val now = SystemClock.uptimeMillis()
            if (now < ignoreEventsUntil) return
            handler.removeCallbacks(feedDebounce)
            handler.postDelayed(feedDebounce, 480L)
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
            pendingCommentText = ""
            commentInFlight = false
            recordStage("auto_off", "زر 4 متوقف")
            return
        }

        if (!wasEnabled) {
            recordStage("auto_on", "زر 4: التعليق الأوتوماتيكي يعمل")
            handler.postDelayed({
                if (!enabled || commentInFlight || !isTikTokActive()) return@postDelayed
                val root = currentTikTokRoot() ?: return@postDelayed
                if (!isCommentPanelOpen(root)) startComment("first_video")
            }, 900L)
        }
    }

    private fun startComment(reason: String) {
        if (!enabled || commentInFlight || comments.isEmpty() || !isTikTokActive()) return
        val root = currentTikTokRoot() ?: return
        if (isCommentPanelOpen(root)) return

        commentInFlight = true
        pendingCommentText = comments[commentIndex % comments.size]
        recordStage("comment_start", "بدء تعليق زر 4: $reason")
        findCommentButtonAndOpen(0)
    }

    private fun findCommentButtonAndOpen(attempt: Int) {
        if (!enabled || !isTikTokActive()) {
            finishFailure("scope_lost", "TikTok لم يعد النافذة النشطة")
            return
        }
        val root = currentTikTokRoot() ?: run {
            finishFailure("root_missing", "نافذة TikTok غير متاحة")
            return
        }

        val button = findBestNode(root, ::commentButtonScore, 8)
        if (clickNode(button)) {
            recordStage("comment_opened", "تم فتح تعليقات TikTok")
            handler.postDelayed({ findEditorAndWrite(0) }, 360L)
            return
        }

        if (attempt < 4) {
            handler.postDelayed({ findCommentButtonAndOpen(attempt + 1) }, 220L)
        } else {
            finishFailure("button_missing", "لم أجد زر التعليقات | ${probeSummary(root)}")
        }
    }

    private fun findEditorAndWrite(attempt: Int) {
        if (!enabled || !isTikTokActive()) {
            finishFailure("scope_lost", "TikTok لم يعد النافذة النشطة")
            return
        }
        val root = currentTikTokRoot() ?: run {
            finishFailure("root_lost", "اختفت نافذة TikTok أثناء فتح التعليقات")
            return
        }

        val editor = findBestNode(root, ::commentEditorScore, 20)
        if (editor == null) {
            if (attempt < 8) {
                handler.postDelayed({ findEditorAndWrite(attempt + 1) }, 180L)
            } else {
                safeBackIfTikTok()
                finishFailure("editor_missing", "لم يظهر حقل كتابة التعليق | ${probeSummary(root)}")
            }
            return
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pendingCommentText)
        }
        editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val wrote = editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!wrote) {
            safeBackIfTikTok()
            finishFailure("write_failed", "تعذر إدخال نص التعليق")
            return
        }

        recordStage("text_written", "تم اختيار تعليق من دولاب زر 4")
        handler.postDelayed({ findSendAndSubmit(editor, 0) }, 220L)
    }

    private fun findSendAndSubmit(editor: AccessibilityNodeInfo, attempt: Int) {
        if (!enabled || !isTikTokActive()) {
            finishFailure("scope_lost", "TikTok لم يعد النافذة النشطة")
            return
        }
        val root = currentTikTokRoot() ?: run {
            finishFailure("root_before_send", "اختفت نافذة TikTok قبل الإرسال")
            return
        }

        val send = findSendNearEditor(editor) ?: findBestNode(root, ::commentSendScore, 8)
        if (send == null) {
            if (attempt < 8) {
                handler.postDelayed({ findSendAndSubmit(editor, attempt + 1) }, 180L)
            } else {
                safeBackIfTikTok()
                finishFailure("send_missing", "لم يظهر زر نشر التعليق | ${probeSummary(root)}")
            }
            return
        }

        if (!clickNode(send)) {
            safeBackIfTikTok()
            finishFailure("send_click_failed", "تعذر ضغط زر نشر التعليق")
            return
        }

        recordStage("send_clicked", "تم ضغط نشر تعليق زر 4")
        handler.postDelayed({ verifySubmitted(0) }, 260L)
    }

    private fun verifySubmitted(attempt: Int) {
        if (!isTikTokActive()) {
            finishSuccess(closePanel = false)
            return
        }

        val root = currentTikTokRoot()
        val editor = root?.let { findBestNode(it, ::commentEditorScore, 20) }
        if (editor == null) {
            finishSuccess(closePanel = false)
            return
        }

        val currentText = editor.text?.toString().orEmpty().trim()
        if (currentText.isBlank() || currentText != pendingCommentText) {
            finishSuccess(closePanel = true)
            return
        }

        if (attempt < 6) {
            handler.postDelayed({ verifySubmitted(attempt + 1) }, 220L)
        } else {
            safeBackIfTikTok()
            finishFailure("send_unconfirmed", "تم ضغط النشر لكن النص بقي في الحقل")
        }
    }

    private fun finishSuccess(closePanel: Boolean) {
        commentIndex += 1
        localPrefs.edit().putInt("comment_index", commentIndex).apply()
        bumpStat("stat_comment_ok")
        recordStage("comment_ok", "تم نشر تعليق من دولاب زر 4")
        pendingCommentText = ""
        val now = SystemClock.uptimeMillis()
        lastActionFinishedAt = now
        ignoreEventsUntil = now + 1_200L

        handler.postDelayed({
            if (closePanel) safeBackIfTikTok()
            handler.postDelayed({ commentInFlight = false }, 420L)
        }, 220L)
    }

    private fun finishFailure(code: String, reason: String) {
        pendingCommentText = ""
        bumpStat("stat_comment_fail")
        recordStage(code, reason)
        val now = SystemClock.uptimeMillis()
        lastActionFinishedAt = now
        ignoreEventsUntil = now + 900L
        handler.postDelayed({ commentInFlight = false }, 420L)
    }

    private fun currentTikTokRoot(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.takeIf { TikTokScope.isAllowed(it.packageName) }
    }

    private fun isTikTokActive(): Boolean = currentTikTokRoot() != null

    private fun isCommentPanelOpen(root: AccessibilityNodeInfo): Boolean {
        return findBestNode(root, ::commentEditorScore, 20) != null
    }

    private fun commentButtonScore(node: AccessibilityNodeInfo): Int {
        if (!node.isEnabled || node.isEditable) return 0
        val token = nodeToken(node)
        var score = 0
        if (token.contains("read or add comments")) score += 45
        if (token.contains("view comments") || token.contains("open comments")) score += 36
        if (containsAny(token, "comments", "comment", "kommentarer", "kommentar", "تعليقات", "التعليقات", "تعليق")) score += 16
        if (node.viewIdResourceName?.contains("comment", ignoreCase = true) == true) score += 20
        if (node.contentDescription?.toString()?.contains("comment", ignoreCase = true) == true) score += 16
        if (node.isClickable) score += 5
        if (isButtonLike(node)) score += 3
        if (containsAny(token, "add comment", "write a comment", "comment here", "اكتب تعليق", "أضف تعليق")) score -= 24

        if (score in 1..7 && node.isClickable && isButtonLike(node)) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val dm = resources.displayMetrics
            val onSideRail = bounds.centerX() < (dm.widthPixels * 0.28f) || bounds.centerX() > (dm.widthPixels * 0.72f)
            val middle = bounds.centerY() in (dm.heightPixels * 0.25f).roundToInt()..(dm.heightPixels * 0.85f).roundToInt()
            if (onSideRail && middle && token.contains("comment")) score += 4
        }
        return score
    }

    private fun commentEditorScore(node: AccessibilityNodeInfo): Int {
        if (!node.isEnabled) return 0
        val token = nodeToken(node)
        var score = 0
        if (node.isEditable) score += 30
        if (node.className?.toString()?.contains("EditText", ignoreCase = true) == true) score += 20
        if (supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) score += 14
        if (containsAny(token, "add comment", "write a comment", "comment here", "kommentera", "skriv en kommentar", "أضف تعليق", "اكتب تعليق")) score += 18
        if (node.isFocusable) score += 2
        return score
    }

    private fun commentSendScore(node: AccessibilityNodeInfo): Int {
        if (!node.isEnabled || node.isEditable) return 0
        val token = nodeToken(node)
        val label = nodeLabel(node)
        var score = 0
        if (containsAny(token, "send_comment", "post_comment", "submit_comment", "comment_send", "comment_post")) score += 40
        if (label in setOf("send", "post", "publish", "skicka", "publicera", "إرسال", "نشر")) score += 30
        if (containsAny(token, "send comment", "post comment", "publish comment", "skicka", "publicera", "إرسال", "نشر")) score += 12
        if (node.viewIdResourceName?.let { containsAny(it.lowercase(), "send", "post", "submit") } == true) score += 14
        if (node.isClickable) score += 5
        if (isButtonLike(node)) score += 3
        return score
    }

    private fun findSendNearEditor(editor: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var anchor: AccessibilityNodeInfo? = editor.parent
        repeat(6) {
            val current = anchor ?: return@repeat
            val candidate = findBestNode(current, ::commentSendScore, 8)
            if (candidate != null && candidate !== editor) return candidate
            anchor = current.parent
        }
        return null
    }

    private fun findBestNode(
        root: AccessibilityNodeInfo,
        scorer: (AccessibilityNodeInfo) -> Int,
        minScore: Int,
    ): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = minScore - 1
        walkNodes(root, 700) { node ->
            val score = scorer(node)
            if (score > bestScore) {
                best = node
                bestScore = score
            }
        }
        return best
    }

    private fun walkNodes(root: AccessibilityNodeInfo, limit: Int, block: (AccessibilityNodeInfo) -> Unit) {
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
        if (!isTikTokActive()) return false
        var current = node ?: return false
        repeat(8) {
            if (!isTikTokActive()) return false
            if (current.isEnabled && supportsAction(current, AccessibilityNodeInfo.ACTION_CLICK) && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            if (current.isClickable && current.isEnabled && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent ?: return false
        }
        return false
    }

    private fun supportsAction(node: AccessibilityNodeInfo, actionId: Int): Boolean {
        return node.actionList.any { it.id == actionId }
    }

    private fun isButtonLike(node: AccessibilityNodeInfo): Boolean {
        val name = node.className?.toString().orEmpty().lowercase()
        return name.contains("button") || name.contains("image")
    }

    private fun containsAny(text: String, vararg values: String): Boolean {
        return values.any { text.contains(it, ignoreCase = true) }
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String {
        return listOfNotNull(node.text, node.contentDescription, node.hintText)
            .joinToString(" ")
            .lowercase()
            .trim()
    }

    private fun nodeToken(node: AccessibilityNodeInfo): String {
        val actions = node.actionList.mapNotNull { it.label?.toString() }.joinToString(" ")
        return listOfNotNull(node.text, node.contentDescription, node.hintText, node.viewIdResourceName, node.className, actions)
            .joinToString(" ")
            .lowercase()
            .trim()
    }

    private fun probeSummary(root: AccessibilityNodeInfo): String {
        val hits = mutableListOf<String>()
        walkNodes(root, 280) { node ->
            if (hits.size >= 6) return@walkNodes
            val token = nodeToken(node)
            if (containsAny(token, "comment", "kommentar", "تعليق", "send", "post", "نشر", "إرسال") || node.isEditable) {
                hits += "${node.className?.toString()?.substringAfterLast('.').orEmpty()}:${token.replace('\n', ' ').take(90)}"
            }
        }
        return if (hits.isEmpty()) "لا توجد عقد تعليق ظاهرة" else hits.joinToString(" || ")
    }

    private fun safeBackIfTikTok() {
        if (isTikTokActive()) performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun bumpStat(key: String) {
        localPrefs.edit().putInt(key, localPrefs.getInt(key, 0) + 1).apply()
    }

    private fun recordStage(code: String, message: String) {
        localPrefs.edit()
            .putString("last_auto_stage", code)
            .putString("last_auto_action", message)
            .putLong("last_auto_stage_at", System.currentTimeMillis())
            .apply()
    }
}
