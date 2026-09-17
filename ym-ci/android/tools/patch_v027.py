from pathlib import Path

SERVICE = Path("ym-ci/android/app/src/main/java/com/ym/lite/automation/YmTikTokAccessibilityService.kt")
ACTIVITY = Path("ym-ci/android/app/src/main/java/com/ym/lite/TikTokAutoActivity.kt")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)

s = SERVICE.read_text()

s = replace_once(
    s,
    '''    private val scopeWatch = object : Runnable {\n        override fun run() {\n            if (isTikTokForeground()) ensureOverlay() else removeOverlay()\n''',
    '''    private val scopeWatch = object : Runnable {\n        override fun run() {\n            localPrefs.edit()\n                .putBoolean("diag_service_connected", true)\n                .putLong("diag_service_heartbeat", System.currentTimeMillis())\n                .apply()\n            if (isTikTokForeground()) ensureOverlay() else removeOverlay()\n''',
    "service heartbeat",
)

s = replace_once(
    s,
    '''    override fun onServiceConnected() {\n        super.onServiceConnected()\n        instance = this\n        prefs.edit().putBoolean("comment_mode_active", false).apply()\n''',
    '''    override fun onServiceConnected() {\n        super.onServiceConnected()\n        instance = this\n        val now = System.currentTimeMillis()\n        localPrefs.edit()\n            .putBoolean("diag_service_connected", true)\n            .putLong("diag_service_connected_at", now)\n            .putLong("diag_service_heartbeat", now)\n            .putBoolean("diag_overlay_visible", false)\n            .remove("diag_overlay_error")\n            .apply()\n        prefs.edit().putBoolean("comment_mode_active", false).apply()\n''',
    "service connected diagnostics",
)

s = replace_once(
    s,
    '''    override fun onAccessibilityEvent(event: AccessibilityEvent?) {\n        val packageName = event?.packageName\n''',
    '''    override fun onAccessibilityEvent(event: AccessibilityEvent?) {\n        val packageName = event?.packageName\n        localPrefs.edit()\n            .putString("diag_last_event_package", packageName?.toString().orEmpty())\n            .putLong("diag_last_event_at", System.currentTimeMillis())\n            .apply()\n''',
    "event diagnostics",
)

s = replace_once(
    s,
    '''        if (instance === this) instance = null\n        super.onDestroy()\n''',
    '''        localPrefs.edit()\n            .putBoolean("diag_service_connected", false)\n            .putBoolean("diag_overlay_visible", false)\n            .apply()\n        if (instance === this) instance = null\n        super.onDestroy()\n''',
    "destroy diagnostics",
)

s = replace_once(
    s,
    '''        wm.addView(container, lp)\n        overlayContainer = container\n''',
    '''        localPrefs.edit().putLong("diag_overlay_attempt_at", System.currentTimeMillis()).apply()\n        try {\n            wm.addView(container, lp)\n        } catch (t: Throwable) {\n            localPrefs.edit()\n                .putBoolean("diag_overlay_visible", false)\n                .putString("diag_overlay_error", "${t::class.java.simpleName}: ${t.message.orEmpty()}")\n                .putLong("diag_overlay_error_at", System.currentTimeMillis())\n                .apply()\n            Toast.makeText(this, "YM overlay error: ${t::class.java.simpleName}", Toast.LENGTH_LONG).show()\n            return\n        }\n        localPrefs.edit()\n            .putBoolean("diag_overlay_visible", true)\n            .remove("diag_overlay_error")\n            .apply()\n        overlayContainer = container\n''',
    "overlay error capture",
)

s = replace_once(
    s,
    '''        overlayContainer = null\n        autoButton = null\n        commentButton = null\n''',
    '''        overlayContainer = null\n        autoButton = null\n        commentButton = null\n        localPrefs.edit().putBoolean("diag_overlay_visible", false).apply()\n''',
    "overlay removed diagnostics",
)

SERVICE.write_text(s)

a = ACTIVITY.read_text()
a = replace_once(
    a,
    '''        val fixed = prefs.getInt("interval_sec", 4).coerceIn(3, 30)\n\n        status.text = buildString {\n''',
    '''        val fixed = prefs.getInt("interval_sec", 4).coerceIn(3, 30)\n        val now = System.currentTimeMillis()\n        val heartbeat = localPrefs.getLong("diag_service_heartbeat", 0L)\n        val runtimeConnected = heartbeat > 0L && now - heartbeat <= 3000L\n        val overlayVisible = localPrefs.getBoolean("diag_overlay_visible", false)\n        val overlayError = localPrefs.getString("diag_overlay_error", "").orEmpty()\n        val lastEventPackage = localPrefs.getString("diag_last_event_package", "").orEmpty()\n        val lastEventAt = localPrefs.getLong("diag_last_event_at", 0L)\n        val overlayAttemptAt = localPrefs.getLong("diag_overlay_attempt_at", 0L)\n\n        status.text = buildString {\n''',
    "activity diagnostics values",
)

a = replace_once(
    a,
    '''            append(if (access) "مفعّلة" else "غير مفعّلة")\n            append("\\nدولاب الدعم: ")\n''',
    '''            append(if (access) "مفعّلة" else "غير مفعّلة")\n            append("\\nالخدمة متصلة فعليًا: ")\n            append(if (runtimeConnected) "نعم ✅" else "لا ❌")\n            append("\\nالزر العائم: ")\n            append(if (overlayVisible) "ظاهر ✅" else "غير ظاهر")\n            append("\\nآخر حدث: ")\n            append(if (lastEventPackage.isBlank()) "لا يوجد" else lastEventPackage)\n            if (lastEventAt > 0L) append(" — منذ ${(now - lastEventAt).coerceAtLeast(0L) / 1000}ث")\n            append("\\nآخر محاولة زر: ")\n            append(if (overlayAttemptAt == 0L) "لم تحدث" else "منذ ${(now - overlayAttemptAt).coerceAtLeast(0L) / 1000}ث")\n            if (overlayError.isNotBlank()) append("\\nخطأ الزر: $overlayError")\n            append("\\nدولاب الدعم: ")\n''',
    "activity diagnostics status",
)

ACTIVITY.write_text(a)
print("YM v0.27 runtime diagnostics patch applied")
