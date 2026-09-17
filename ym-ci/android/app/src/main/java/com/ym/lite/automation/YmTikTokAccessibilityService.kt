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
import android.widget.Toast
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
    private var panelOpened = false
    private var pendingText = ""
    private var textConfirmed = false
    private var pendingVideoAdvance = false
    private var videoEpoch = 0L
    private var attemptedEpoch = -1L
    private var lastFinishedAt = 0L
    private var lastOutcomeSuccess = false
    private var retryAfterFailureAt = Long.MAX_VALUE
    private var composerTapIndex = 0
    private var sendTapIndex = 0
    private var lastToastAt = 0L

    private val attemptRunnable = object : Runnable {
        override fun run() {
            if (!enabled || inFlight || !hasTikTokWindow()) return
            val now = SystemClock.uptimeMillis()
            val wait = (lastFinishedAt + minIntervalMs - now).coerceAtLeast(0L)
            if (wait > 0L) {
                handler.postDelayed(this, wait)
                return
            }

            val newVideoPending = videoEpoch > attemptedEpoch
            val retryFailedVideo = !lastOutcomeSuccess && now >= retryAfterFailureAt
            if (newVideoPending || retryFailedVideo) {
                beginComment(if (newVideoPending) "video_$videoEpoch" else "retry_failed_video")
            }
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (enabled && !inFlight && hasTikTokWindow()) {
                val now = SystemClock.uptimeMillis()
                if ((!lastOutcomeSuccess && now >= retryAfterFailureAt) || videoEpoch > attemptedEpoch) {
                    scheduleAttempt(120L)
                }
            }
            handler.postDelayed(this, 700L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        reloadFromPrefs()
        handler.removeCallbacks(watchdog)
        handler.post(watchdog)
        record("engine_ready", "محرك زر 4 الجديد جاهز")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !TikTokScope.isAllowed(event.packageName) || !enabled) return

        val isFeedAdvance = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_SELECTED
        if (!isFeedAdvance) return

        if (inFlight) {
            // Do not lose a real feed transition that happened during a closing/cooldown phase.
            if (!panelOpened) pendingVideoAdvance = true
            return
        }

        markNewVideo("accessibility_event")
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

        handler.removeCallbacks(attemptRunnable)

        if (!enabled) {
            resetFlight()
            retryAfterFailureAt = Long.MAX_VALUE
            record("auto_off", "زر 4 متوقف")
            return
        }

        if (!wasEnabled) {
            lastOutcomeSuccess = false
            retryAfterFailureAt = Long.MAX_VALUE
            videoEpoch += 1
            record("auto_on", "زر 4 يعمل؛ تجهيز أول تعليق")
            scheduleAttempt(850L)
        }
    }

    private fun markNewVideo(reason: String) {
        videoEpoch += 1
        record("video_seen", "فيديو جديد: $reason / $videoEpoch")
        scheduleAttempt(520L)
    }

    private fun scheduleAttempt(delayMs: Long) {
        handler.removeCallbacks(attemptRunnable)
        handler.postDelayed(attemptRunnable, delayMs)
    }

    private fun beginComment(reason: String) {
        if (!enabled || inFlight || comments.isEmpty() || !hasTikTokWindow()) return
        inFlight = true
        attemptedEpoch = videoEpoch
        panelOpened = false
        pendingText = comments[commentIndex % comments.size]
        textConfirmed = false
        composerTapIndex = 0
        sendTapIndex = 0
        record("comment_start", "بدء تعليق زر 4: $reason")
        openComments(0)
    }

    private fun openComments(attempt: Int) {
        if (!enabled || !hasTikTokWindow()) {
            fail("tiktok_missing", "TikTok غير متاح")
            return
        }

        val button = bestNodeAcrossTikTok(::commentButtonScore, 14)
        if (clickNode(button)) {
            panelOpened = true
            record("comment_opened", "تم فتح التعليقات")
            handler.postDelayed({ activateComposer(0) }, 520L)
            return
        }

        if (attempt < 4) {
            handler.postDelayed({ openComments(attempt + 1) }, 220L)
            return
        }

        // Arabic/RTL TikTok places the action rail on the left on this device.
        val leftFirst = attempt == 4
        val dm = resources.displayMetrics
        val x = dm.widthPixels * if (leftFirst) 0.045f else 0.955f
        val y = dm.heightPixels * 0.705f
        if (attempt <= 5) {
            record("comment_direct_tap", if (leftFirst) "نقر مباشر على زر التعليقات يسار الشاشة" else "تجربة زر التعليقات يمين الشاشة")
            dispatchTap(x, y) {
                panelOpened = true
                handler.postDelayed({ activateComposer(0) }, 650L)
            }
            return
        }

        fail("comment_button_missing", "لم يتمكن YM من فتح التعليقات")
    }

    private fun activateComposer(attempt: Int) {
        if (!enabled || !hasTikTokWindow()) {
            fail("tiktok_missing", "اختفت نافذة TikTok")
            return
        }

        val editor = findEditorAcrossTikTok()
        if (editor != null) {
            focusEditor(editor)
            record("editor_found", "تم العثور على حقل التعليق")
            handler.postDelayed({ writeComment(0) }, 160L)
            return
        }

        val entry = bestNodeAcrossTikTok(::composerEntryScore, 12)
        if (entry != null && attempt < 4) {
            clickNode(entry)
            record("composer_clicked", "تم ضغط خانة إضافة تعليق")
            handler.postDelayed({ activateComposer(attempt + 1) }, 300L)
            return
        }

        if (composerTapIndex < 2) {
            val dm = resources.displayMetrics
            val yFractions = floatArrayOf(0.915f, 0.945f)
            val y = dm.heightPixels * yFractions[composerTapIndex]
            composerTapIndex += 1
            setClipboardText()
            record("composer_direct_tap", "نقر مباشر على شريط التعليق ${composerTapIndex}/2")
            dispatchTap(dm.widthPixels * 0.50f, y) {
                handler.postDelayed({ activateComposer(attempt + 1) }, 420L)
            }
            return
        }

        if (attempt < 8) {
            handler.postDelayed({ activateComposer(attempt + 1) }, 260L)
            return
        }

        pasteThroughContextMenu()
    }

    private fun writeComment(attempt: Int) {
        val editor = findEditorAcrossTikTok()
        if (editor == null) {
            if (attempt < 3) {
                handler.postDelayed({ activateComposer(attempt + 1) }, 220L)
            } else {
                pasteThroughContextMenu()
            }
            return
        }

        focusEditor(editor)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pendingText)
        }
        if (editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            record("text_set", "تم إدخال تعليق الدولاب مباشرة")
            handler.postDelayed({ verifyText(0) }, 260L)
            return
        }

        setClipboardText()
        if (editor.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            record("text_paste", "تم لصق تعليق الدولاب")
            handler.postDelayed({ verifyText(0) }, 300L)
            return
        }

        if (attempt < 2) {
            handler.postDelayed({ writeComment(attempt + 1) }, 240L)
        } else {
            pasteThroughContextMenu()
        }
    }

    private fun verifyText(attempt: Int) {
        val editor = findEditorAcrossTikTok()
        if (editor == null) {
            if (attempt < 3) handler.postDelayed({ verifyText(attempt + 1) }, 220L)
            else pasteThroughContextMenu()
            return
        }

        val actual = normalize(editor.text?.toString().orEmpty())
        val expected = normalize(pendingText)
        if (actual == expected && expected.isNotBlank()) {
            textConfirmed = true
            record("text_confirmed", "تم تأكيد نص تعليق الدولاب")
            handler.postDelayed({ sendComment(0) }, 160L)
            return
        }

        if (actual.isNotBlank() && actual != expected) {
            fail("foreign_text", "ظهر نص مختلف عن تعليق الدولاب")
            return
        }

        if (attempt < 2) handler.postDelayed({ writeComment(attempt + 1) }, 220L)
        else pasteThroughContextMenu()
    }

    private fun pasteThroughContextMenu() {
        if (!inFlight || !enabled) return
        setClipboardText()
        val dm = resources.displayMetrics
        val x = dm.widthPixels * 0.50f
        val y = dm.heightPixels * 0.92f
        record("context_paste_start", "محاولة لصق تعليق الدولاب عبر قائمة Paste")
        dispatchLongPress(x, y) {
            handler.postDelayed({
                val paste = bestNodeAcrossAllWindows(::pasteMenuScore, 10)
                if (clickNode(paste)) {
                    textConfirmed = true
                    record("context_paste_ok", "تم اختيار لصق من قائمة النظام")
                    handler.postDelayed({ sendComment(0) }, 280L)
                } else {
                    fail("paste_menu_missing", "لم يجد YM أمر لصق بعد فتح خانة التعليق")
                }
            }, 420L)
        }
    }

    private fun sendComment(attempt: Int) {
        if (!textConfirmed) {
            fail("send_blocked", "النص غير مؤكد")
            return
        }

        val editor = findEditorAcrossTikTok()
        if (editor != null) {
            val send = findSendNearEditor(editor)
                ?: bestNodeAcrossTikTok(::sendScore, 10)
                ?: findSendByGeometry(editor)
            if (clickNode(send)) {
                record("send_clicked", "تم ضغط نشر التعليق")
                handler.postDelayed({ verifySubmitted(0) }, 380L)
                return
            }
        } else {
            val send = bestNodeAcrossTikTok(::sendScore, 10)
            if (clickNode(send)) {
                record("send_clicked", "تم ضغط نشر التعليق")
                handler.postDelayed({ verifySubmitted(0) }, 380L)
                return
            }
        }

        if (attempt < 2) {
            handler.postDelayed({ sendComment(attempt + 1) }, 220L)
            return
        }

        directSend()
    }

    private fun directSend() {
        if (!inFlight || !textConfirmed) return
        val dm = resources.displayMetrics
        val xFractions = floatArrayOf(0.08f, 0.92f)
        val x = dm.widthPixels * xFractions[sendTapIndex.coerceIn(0, 1)]
        val editor = findEditorAcrossTikTok()
        val bounds = Rect()
        editor?.getBoundsInScreen(bounds)
        val y = if (!bounds.isEmpty) bounds.centerY().toFloat() else dm.heightPixels * 0.92f
        sendTapIndex += 1
        record("send_direct_tap", "تجربة زر الإرسال ${sendTapIndex}/2")
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

        if (attempt < 3) {
            handler.postDelayed({ verifySubmitted(attempt + 1) }, 220L)
            return
        }

        if (sendTapIndex < 2) directSend()
        else fail("send_unconfirmed", "بقي النص داخل الحقل بعد محاولات النشر")
    }

    private fun succeed() {
        commentIndex += 1
        localPrefs.edit().putInt("comment_index", commentIndex).apply()
        bump("stat_comment_ok")
        lastOutcomeSuccess = true
        retryAfterFailureAt = Long.MAX_VALUE
        lastFinishedAt = SystemClock.uptimeMillis()
        record("comment_ok", "تم نشر تعليق من الدولاب")
        closePanel(success = true, backCount = 0)
    }

    private fun fail(code: String, message: String) {
        bump("stat_comment_fail")
        lastOutcomeSuccess = false
        lastFinishedAt = SystemClock.uptimeMillis()
        retryAfterFailureAt = lastFinishedAt + minIntervalMs.coerceAtLeast(2_500L)
        record(code, message)
        showFailure(message)
        closePanel(success = false, backCount = 0)
    }

    private fun closePanel(success: Boolean, backCount: Int) {
        if (!panelOpened) {
            resetFlight()
            return
        }

        if (backCount >= 3) {
            record(if (success) "panel_closed" else "failure_panel_closed", "انتهت محاولات إغلاق نافذة التعليقات")
            resetFlight()
            return
        }

        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({ closePanel(success, backCount + 1) }, 360L)
    }

    private fun resetFlight() {
        pendingText = ""
        textConfirmed = false
        inFlight = false
        panelOpened = false
        composerTapIndex = 0
        sendTapIndex = 0

        if (pendingVideoAdvance) {
            pendingVideoAdvance = false
            markNewVideo("queued_during_previous_attempt")
        } else if (!lastOutcomeSuccess && enabled) {
            scheduleAttempt((retryAfterFailureAt - SystemClock.uptimeMillis()).coerceAtLeast(200L))
        }
    }

    private fun tiktokRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        val active = rootInActiveWindow
        if (active != null && TikTokScope.isAllowed(active.packageName)) roots += active
        windows.forEach { window ->
            val root = runCatching { window.root }.getOrNull() ?: return@forEach
            if (TikTokScope.isAllowed(root.packageName) && roots.none { it === root }) roots += root
        }
        return roots
    }

    private fun allRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots += it }
        windows.forEach { window ->
            val root = runCatching { window.root }.getOrNull() ?: return@forEach
            if (roots.none { it === root }) roots += root
        }
        return roots
    }

    private fun hasTikTokWindow(): Boolean = tiktokRoots().isNotEmpty()

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
            walk(root, 850) { node ->
                val score = scorer(node)
                if (score > bestScore) {
                    best = node
                    bestScore = score
                }
            }
        }
        return best
    }

    private fun bestNodeAcrossAllWindows(
        scorer: (AccessibilityNodeInfo) -> Int,
        minScore: Int,
    ): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = minScore - 1
        allRoots().forEach { root ->
            walk(root, 850) { node ->
                val score = scorer(node)
                if (score > bestScore) {
                    best = node
                    bestScore = score
                }
            }
        }
        return best
    }

    private fun commentButtonScore(node: AccessibilityNodeInfo): Int {
        if (!node.isEnabled || node.isEditable) return 0
        val t = token(node)
        var score = 0
        if (t.contains("read or add comments")) score += 70
        if (t.contains("view comments") || t.contains("open comments")) score += 55
        if (containsAny(t, "comments", "comment", "kommentarer", "kommentar", "تعليقات", "التعليقات", "تعليق")) score += 18
        if (node.viewIdResourceName?.contains("comment", true) == true) score += 24
        if (node.contentDescription?.toString()?.contains("comment", true) == true) score += 20
        if (node.isClickable || supports(node, AccessibilityNodeInfo.ACTION_CLICK)) score += 7
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
        val b = Rect()
        node.getBoundsInScreen(b)
        val dm = resources.displayMetrics
        val bottomWide = !b.isEmpty && b.centerY() > dm.heightPixels * 0.58f && b.width() > dm.widthPixels * 0.22f
        val structural = node.isEditable ||
            node.className?.toString()?.contains("EditText", true) == true ||
            supports(node, AccessibilityNodeInfo.ACTION_SET_TEXT) ||
            supports(node, AccessibilityNodeInfo.ACTION_PASTE)
        if (!bottomWide && !structural) return 0
        var score = 0
        if (containsAny(t, "add comment", "add a comment", "write a comment", "write comment", "comment here", "say something", "kommentera", "skriv en kommentar", "lägg till kommentar", "أضف تعليق", "اكتب تعليق")) score += 40
        if (structural) score += 28
        if (bottomWide) score += 16
        if (node.isFocusable) score += 4
        if (node.isClickable) score += 3
        return score
    }

    private fun editorScore(node: AccessibilityNodeInfo): Int {
        if (!node.isEnabled) return 0
        val t = token(node)
        if (containsAny(t, "read or add comments", "view comments", "open comments")) return 0
        val structural = node.isEditable ||
            node.className?.toString()?.contains("EditText", true) == true ||
            supports(node, AccessibilityNodeInfo.ACTION_SET_TEXT) ||
            supports(node, AccessibilityNodeInfo.ACTION_PASTE)
        if (!structural) return 0
        val b = Rect()
        node.getBoundsInScreen(b)
        val dm = resources.displayMetrics
        if (!b.isEmpty && !node.isFocused && b.centerY() < dm.heightPixels * 0.50f) return 0
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
        var score = 0
        if (containsAny(t, "send_comment", "post_comment", "submit_comment", "comment_send", "comment_post")) score += 46
        if (l in setOf("send", "post", "publish", "submit", "skicka", "publicera", "إرسال", "نشر")) score += 38
        if (containsAny(t, "send comment", "post comment", "publish comment", "submit comment", "skicka", "publicera", "إرسال", "نشر")) score += 20
        if (node.viewIdResourceName?.let { containsAny(it.lowercase(), "send", "post", "submit") } == true) score += 20
        if (node.isClickable || supports(node, AccessibilityNodeInfo.ACTION_CLICK)) score += 5
        if (isButtonLike(node)) score += 3
        return score
    }

    private fun pasteMenuScore(node: AccessibilityNodeInfo): Int {
        if (!node.isEnabled) return 0
        val l = label(node)
        var score = 0
        if (l == "paste" || l == "لصق" || l == "klistra in") score += 60
        if (containsAny(l, "paste", "لصق", "klistra in")) score += 25
        if (node.isClickable || supports(node, AccessibilityNodeInfo.ACTION_CLICK)) score += 8
        return score
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
            walk(root, 850) { node ->
                if (!node.isEnabled || node.isEditable) return@walk
                if (!node.isClickable && !supports(node, AccessibilityNodeInfo.ACTION_CLICK)) return@walk
                val t = token(node)
                if (containsAny(t, "emoji", "sticker", "gif", "mention", "camera", "photo", "image", "voice", "microphone", "audio")) return@walk
                val b = Rect()
                node.getBoundsInScreen(b)
                if (b.isEmpty) return@walk
                val vertical = abs(b.centerY() - eb.centerY())
                if (vertical > dp(90)) return@walk
                val gap = when {
                    b.right <= eb.left -> eb.left - b.right
                    b.left >= eb.right -> b.left - eb.right
                    else -> return@walk
                }
                if (gap > dp(150)) return@walk
                val score = 280 - gap - vertical + sendScore(node) * 4
                if (score > bestScore) {
                    best = node
                    bestScore = score
                }
            }
        }
        return best
    }

    private fun setClipboardText() {
        runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("YM comment", pendingText))
        }
    }

    private fun focusEditor(editor: AccessibilityNodeInfo) {
        editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (editor.isClickable || supports(editor, AccessibilityNodeInfo.ACTION_CLICK)) {
            editor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    private fun dispatchTap(x: Float, y: Float, after: () -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 70L))
            .build()
        dispatchGestureWithCallback(gesture, after)
    }

    private fun dispatchLongPress(x: Float, y: Float, after: () -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 650L))
            .build()
        dispatchGestureWithCallback(gesture, after)
    }

    private fun dispatchGestureWithCallback(gesture: GestureDescription, after: () -> Unit) {
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
        walk(root, 850) { node ->
            val score = scorer(node)
            if (score > bestScore) {
                best = node
                bestScore = score
            }
        }
        return best
    }

    private fun walk(root: AccessibilityNodeInfo, limit: Int, block: (AccessibilityNodeInfo) -> Unit) {
        var visited = 0
        fun visit(node: AccessibilityNodeInfo) {
            if (visited >= limit) return
            visited += 1
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

    private fun showFailure(message: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastToastAt < 3_000L) return
        lastToastAt = now
        Toast.makeText(this, "YM4: $message", Toast.LENGTH_SHORT).show()
    }
}
