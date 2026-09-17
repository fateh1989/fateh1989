from pathlib import Path

SERVICE = Path("ym-ci/android/app/src/main/java/com/ym/lite/automation/YmTikTokAccessibilityService.kt")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)

s = SERVICE.read_text()

old_toggle = '''    private fun toggleCommentsFromOverlay() {\n        if (currentTikTokRoot() == null) return\n        val turningOn = !prefs.getBoolean("comment_mode_active", false)\n        if (turningOn && !prefs.getBoolean("enabled", false)) {\n            Toast.makeText(this, "شغّل AUTO أولاً ثم فعّل التعليقات", Toast.LENGTH_SHORT).show()\n            return\n        }\n        if (turningOn && comments.isEmpty()) {\n            Toast.makeText(this, "دولاب التعليقات فارغ — أضف التعليقات من YM", Toast.LENGTH_LONG).show()\n            return\n        }\n\n        prefs.edit().putBoolean("comment_mode_active", turningOn).apply()\n        commentModeActive = turningOn\n        updateOverlayText()\n        Toast.makeText(\n            this,\n            if (turningOn) "التعليقات التلقائية تعمل" else "تم إيقاف التعليقات التلقائية",\n            Toast.LENGTH_SHORT,\n        ).show()\n    }\n'''
new_toggle = '''    private fun toggleCommentsFromOverlay() {\n        if (!isTikTokForeground()) return\n        val turningOn = !prefs.getBoolean("comment_mode_active", false)\n\n        if (turningOn && comments.isEmpty()) {\n            Toast.makeText(this, "دولاب التعليقات فارغ — أضف التعليقات من YM", Toast.LENGTH_LONG).show()\n            return\n        }\n\n        if (turningOn) {\n            enabled = true\n            commentModeActive = true\n            sessionEndAt = System.currentTimeMillis() + sessionDurationMs\n            scrollCount = 0\n            commentsSent = 0\n            gestureInFlight = false\n            commentFlowInFlight = false\n            resetVideoTracking()\n            prefs.edit()\n                .putBoolean("enabled", true)\n                .putBoolean("pending_launch", false)\n                .putBoolean("comment_mode_active", true)\n                .putLong("session_end_at", sessionEndAt)\n                .apply()\n            handler.removeCallbacks(loop)\n            handler.postDelayed(loop, 250)\n            updateOverlayText()\n            Toast.makeText(this, "🎡 بدأ دولاب التعليقات والتمرير", Toast.LENGTH_SHORT).show()\n        } else {\n            enabled = false\n            commentModeActive = false\n            gestureInFlight = false\n            commentFlowInFlight = false\n            sessionEndAt = 0L\n            clearVideoTracking()\n            prefs.edit()\n                .putBoolean("enabled", false)\n                .putBoolean("pending_launch", false)\n                .putBoolean("comment_mode_active", false)\n                .putLong("session_end_at", 0L)\n                .apply()\n            handler.removeCallbacks(loop)\n            updateOverlayText()\n            Toast.makeText(this, "🎡 تم إيقاف دولاب التعليقات", Toast.LENGTH_SHORT).show()\n        }\n    }\n'''
s = replace_once(s, old_toggle, new_toggle, "wheel toggle")

old_delay = '''        attemptComment {\n            commentFlowInFlight = false\n            handler.postDelayed({ closeCommentUiAndSwipe(2) }, 250)\n        }\n'''
new_delay = '''        attemptComment {\n            commentFlowInFlight = false\n            val wheelDelayMs = if ((scrollCount + commentsSent) % 2 == 0) 3_000L else 4_000L\n            handler.postDelayed({ closeCommentUiAndSwipe(2) }, wheelDelayMs)\n        }\n'''
s = replace_once(s, old_delay, new_delay, "3-4 second wheel delay")

old_text = '''        commentButton?.text = if (commentsOn) "💬 ON\\n$commentsSent" else "💬 OFF"\n'''
new_text = '''        commentButton?.text = if (commentsOn) "🎡 ON\\n$commentsSent" else "🎡 OFF"\n'''
s = replace_once(s, old_text, new_text, "wheel overlay label")

SERVICE.write_text(s)
print("YM v0.25 wheel toggle patch applied")
