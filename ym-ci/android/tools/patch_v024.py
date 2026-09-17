from pathlib import Path

SERVICE = Path("ym-ci/android/app/src/main/java/com/ym/lite/automation/YmTikTokAccessibilityService.kt")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)

s = SERVICE.read_text()

s = replace_once(
    s,
    "import com.ym.lite.TikTokAutoActivity\n",
    "import com.ym.lite.SupportCommentWheel\nimport com.ym.lite.TikTokAutoActivity\n",
    "support wheel import",
)

s = replace_once(
    s,
    "    private var videoStartedAt = 0L\n    private var lastVideoProgress = -1f\n    private var videoProgressAdvanced = false\n",
    "    private var videoStartedAt = 0L\n    private var lastVideoProgress = -1f\n    private var videoProgressAdvanced = false\n    private var lastAllowedEventAt = 0L\n",
    "foreground timestamp",
)

s = replace_once(
    s,
    "            if (currentTikTokRoot() == null) removeOverlay() else ensureOverlay()\n",
    "            if (isTikTokForeground()) ensureOverlay() else removeOverlay()\n",
    "scope watch",
)

s = replace_once(
    s,
    "    override fun onAccessibilityEvent(event: AccessibilityEvent?) {\n        if (currentTikTokRoot() == null) removeOverlay() else ensureOverlay()\n    }\n",
    "    override fun onAccessibilityEvent(event: AccessibilityEvent?) {\n        val packageName = event?.packageName\n        if (TikTokScope.isAllowed(packageName)) {\n            lastAllowedEventAt = System.currentTimeMillis()\n            ensureOverlay()\n        } else if (!isTikTokForeground()) {\n            removeOverlay()\n        }\n    }\n",
    "event foreground tracking",
)

old_comments = '''        comments = localPrefs.getString("comment_pool", "").orEmpty()\n            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()\n        commentIndex = localPrefs.getInt("comment_wheel_index", 0).coerceAtLeast(0)\n'''
new_comments = '''        val savedCommentPool = localPrefs.getString("comment_pool", "").orEmpty()\n        comments = savedCommentPool.lineSequence()\n            .map { it.trim() }.filter { it.isNotEmpty() }.toList()\n            .ifEmpty { SupportCommentWheel.all }\n        if (savedCommentPool.isBlank()) {\n            localPrefs.edit().putString("comment_pool", comments.joinToString("\\n")).apply()\n        }\n        commentIndex = localPrefs.getInt("comment_wheel_index", 0).coerceAtLeast(0) % comments.size\n'''
s = replace_once(s, old_comments, new_comments, "default comment wheel")

old_root = '''    private fun currentTikTokRoot(): AccessibilityNodeInfo? {\n        val root = rootInActiveWindow ?: return null\n        return root.takeIf { TikTokScope.isAllowed(it.packageName) }\n    }\n'''
new_root = '''    private fun isTikTokForeground(): Boolean {\n        rootInActiveWindow?.let { if (TikTokScope.isAllowed(it.packageName)) return true }\n\n        for (window in windows) {\n            val root = window.root ?: continue\n            if ((window.isActive || window.isFocused) && TikTokScope.isAllowed(root.packageName)) return true\n        }\n\n        // TikTok Lite can briefly expose no active root while its surface changes.\n        // Keep the overlay only for a short grace period after a TikTok event.\n        return lastAllowedEventAt > 0L &&\n            System.currentTimeMillis() - lastAllowedEventAt <= 2500L\n    }\n\n    private fun currentTikTokRoot(): AccessibilityNodeInfo? {\n        rootInActiveWindow?.let { root ->\n            if (TikTokScope.isAllowed(root.packageName)) return root\n        }\n\n        for (window in windows) {\n            val root = window.root ?: continue\n            if ((window.isActive || window.isFocused) && TikTokScope.isAllowed(root.packageName)) return root\n        }\n\n        for (window in windows) {\n            val root = window.root ?: continue\n            if (TikTokScope.isAllowed(root.packageName)) return root\n        }\n        return null\n    }\n'''
s = replace_once(s, old_root, new_root, "window root fallback")

SERVICE.write_text(s)
print("YM v0.24 overlay/runtime patch applied")
