from pathlib import Path

SERVICE = Path("ym-ci/android/app/src/main/java/com/ym/lite/automation/YmTikTokAccessibilityService.kt")
ACTIVITY = Path("ym-ci/android/app/src/main/java/com/ym/lite/TikTokAutoActivity.kt")


def one(text: str, old: str, new: str, label: str) -> str:
    n = text.count(old)
    if n != 1:
        raise SystemExit(f"{label}: expected 1 match, found {n}")
    return text.replace(old, new, 1)


s = SERVICE.read_text()

# The first patch intentionally starts manual comments OFF, but reload must not
# overwrite the in-memory state after reading preferences.
s = one(
    s,
    '        handler.removeCallbacks(loop)\n        commentModeActive = false\n        updateOverlayText()\n        if (enabled && ensureSessionActive()) handler.postDelayed(loop, 250)',
    '        handler.removeCallbacks(loop)\n        updateOverlayText()\n        if (enabled && ensureSessionActive()) handler.postDelayed(loop, 250)',
    'reload state',
)

# STOP always turns both automation parts off so the next run starts clean.
s = one(
    s,
    '        prefs.edit()\n            .putBoolean("enabled", false)\n            .putBoolean("pending_launch", false)\n            .putLong("session_end_at", 0L)\n            .apply()\n        handler.removeCallbacks(loop)',
    '        prefs.edit()\n            .putBoolean("enabled", false)\n            .putBoolean("pending_launch", false)\n            .putBoolean("comment_mode_active", false)\n            .putLong("session_end_at", 0L)\n            .apply()\n        commentModeActive = false\n        handler.removeCallbacks(loop)',
    'stop comment state',
)

# If the user presses 💬 OFF while a composer is opening, do not send that pending comment.
s = one(
    s,
    '        handler.postDelayed(openEditor@{\n            if (!ensureSessionActive()) return@openEditor',
    '        handler.postDelayed(openEditor@{\n            if (!ensureSessionActive() || !prefs.getBoolean("comment_mode_active", false)) {\n                done()\n                return@openEditor\n            }',
    'editor off guard',
)
s = one(
    s,
    '            handler.postDelayed(sendComment@{\n                if (!ensureSessionActive()) return@sendComment',
    '            handler.postDelayed(sendComment@{\n                if (!ensureSessionActive() || !prefs.getBoolean("comment_mode_active", false)) {\n                    done()\n                    return@sendComment\n                }',
    'send off guard',
)
SERVICE.write_text(s)

# The old rate/limit widgets are removed by patch_v023.py, so no code may read them.
a = ACTIVITY.read_text()
a = one(
    a,
    '        commentEvery.setText(prefs.getInt("comment_every", 3).toString())\n        maxCommentsSession.setText(prefs.getInt("max_comments_session", 5).toString())\n        commentGapSeconds.setText(prefs.getInt("comment_gap_sec", 60).toString())\n',
    '',
    'load removed controls',
)
a = one(
    a,
    '        val every = commentEvery.text.toString().toIntOrNull()?.coerceIn(1, 100) ?: 3\n        val maxComments = maxCommentsSession.text.toString().toIntOrNull()?.coerceIn(1, 50) ?: 5\n        val gapSeconds = commentGapSeconds.text.toString().toIntOrNull()?.coerceIn(30, 3600) ?: 60\n',
    '        val every = 1\n        val maxComments = 50\n        val gapSeconds = 30\n',
    'save removed controls',
)
a = one(
    a,
    '        commentEvery.setText(every.toString())\n        maxCommentsSession.setText(maxComments.toString())\n        commentGapSeconds.setText(gapSeconds.toString())\n',
    '',
    'set removed controls',
)
ACTIVITY.write_text(a)

print("YM v0.23 fix patch applied")
