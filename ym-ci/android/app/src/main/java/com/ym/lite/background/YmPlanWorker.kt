package com.ym.lite.background

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ym.lite.core.PlanEngine
import com.ym.lite.net.YmRelayClient
import com.ym.lite.security.SecurePrefs
import org.json.JSONArray
import java.time.LocalDate
import java.time.ZoneId

class YmPlanWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {

    companion object {
        const val UNIQUE_WORK = "ym_background_cloud_plan"
        const val KEY_CURRENT = "current"
        const val KEY_TOTAL = "total"
        const val KEY_RUN_ID = "run_id"

        fun enqueue(context: Context, runId: String) {
            val request = OneTimeWorkRequestBuilder<YmPlanWorker>()
                .setInputData(workDataOf(KEY_RUN_ID to runId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }

    override fun doWork(): Result {
        val local = applicationContext.getSharedPreferences("ym_local", Context.MODE_PRIVATE)
        val state = applicationContext.getSharedPreferences("ym_background", Context.MODE_PRIVATE)
        val runs = applicationContext.getSharedPreferences("ym_runs", Context.MODE_PRIVATE)
        val runId = inputData.getString(KEY_RUN_ID).orEmpty()
        if (runId.isBlank()) return Result.failure(workDataOf("error" to "run id missing"))

        state.edit()
            .putBoolean("running", true)
            .putString("active_run_id", runId)
            .putString("last_status", "AUTO بدأ تجهيز الخطة")
            .apply()
        runs.edit().putString("$runId.status", "running").apply()

        return try {
            require(local.getBoolean("provider_proof_passed", false)) {
                "اختبار النشر لم ينجح بعد"
            }

            val accountId = runs.getString("$runId.account_id", "").orEmpty()
            require(accountId.isNotBlank()) { "لا يوجد حساب محدد في لقطة الخطة" }

            val uris = readUris(runs.getString("$runId.uris", "[]").orEmpty())
            require(uris.isNotEmpty()) { "مكتبة الخطة فارغة" }

            val days = runs.getString("$runId.horizon_days", "30")?.toIntOrNull()?.coerceIn(1, 30) ?: 30
            val perDay = runs.getString("$runId.daily_count", "1")?.toIntOrNull()?.coerceIn(1, 10) ?: 1
            val start = PlanEngine.parseClock(runs.getString("$runId.window_start", "18:00").orEmpty())
            val end = PlanEngine.parseClock(runs.getString("$runId.window_end", "23:00").orEmpty())
            val caption = runs.getString("$runId.caption", "").orEmpty()

            val plan = PlanEngine.generate(
                ZoneId.systemDefault(),
                LocalDate.now().plusDays(1),
                days,
                perDay,
                start,
                end,
                uris.size,
                runId.hashCode().toLong(),
            )
            runs.edit().putInt("$runId.plan_size", plan.size).apply()

            val secure = SecurePrefs(applicationContext)
            val baseUrl = secure.workerUrl()
            val token = secure.token()
            require(baseUrl.startsWith("https://")) { "Worker URL غير محفوظ" }
            require(token.isNotBlank()) { "رمز الاقتران غير محفوظ" }
            val api = YmRelayClient(baseUrl, token)

            val mediaUrls = readStringArray(runs.getString("$runId.media_urls", "[]").orEmpty(), uris.size)
            val postIds = readStringArray(runs.getString("$runId.post_ids", "[]").orEmpty(), plan.size)
            val pending = readBooleanArray(runs.getString("$runId.pending_posts", "[]").orEmpty(), plan.size)

            val total = uris.size + plan.size
            var progress = mediaUrls.count { it.isNotBlank() } + postIds.count { it.isNotBlank() }
            updateProgress(progress, total)

            uris.forEachIndexed { index, uri ->
                check(!isStopped) { "تم إيقاف AUTO" }
                if (mediaUrls[index].isNotBlank()) return@forEachIndexed

                state.edit().putString("last_status", "رفع فيديو ${index + 1}/${uris.size}").apply()
                runs.edit().putString("$runId.status", "uploading").apply()

                val upload = api.createUploadTarget()
                api.uploadSigned(
                    applicationContext.contentResolver,
                    uri,
                    upload.uploadUrl,
                    applicationContext.contentResolver.getType(uri),
                )
                mediaUrls[index] = upload.mediaUrl
                saveStringArray(runs, "$runId.media_urls", mediaUrls)
                progress++
                updateProgress(progress, total)
            }

            plan.forEachIndexed { index, slot ->
                check(!isStopped) { "تم إيقاف AUTO" }
                if (postIds[index].isNotBlank()) return@forEachIndexed

                if (pending[index]) {
                    error("نتيجة جدولة المنشور ${index + 1} غير مؤكدة؛ أوقفت YM لمنع التكرار")
                }

                val mediaUrl = mediaUrls[slot.mediaIndex].takeIf { it.isNotBlank() }
                    ?: error("media URL missing")

                state.edit().putString(
                    "last_status",
                    "جدولة ${index + 1}/${plan.size} • ${slot.instant}",
                ).apply()
                runs.edit().putString("$runId.status", "scheduling").apply()

                // Persist an uncertainty marker before the network call. If the process dies
                // after the server accepts the post but before we receive its id, a later run
                // fails closed instead of creating a duplicate post.
                pending[index] = true
                saveBooleanArray(runs, "$runId.pending_posts", pending)

                val postId = api.createScheduledPost(
                    accountId,
                    caption,
                    mediaUrl,
                    slot.instant.toString(),
                )

                postIds[index] = postId
                pending[index] = false
                saveStringArray(runs, "$runId.post_ids", postIds)
                saveBooleanArray(runs, "$runId.pending_posts", pending)
                val scheduledCount = postIds.count { it.isNotBlank() }
                runs.edit().putInt("$runId.scheduled_count", scheduledCount).apply()
                progress++
                updateProgress(progress, total)
            }

            runs.edit()
                .putString("$runId.status", "completed")
                .putInt("$runId.scheduled_count", plan.size)
                .putLong("$runId.completed_at", System.currentTimeMillis())
                .apply()
            state.edit()
                .putBoolean("running", false)
                .putString("last_status", "تم تسليم ${plan.size} منشورًا للخطة السحابية. يمكن إبقاء YM مغلقًا.")
                .putInt("last_plan_size", plan.size)
                .putLong("last_completed_at", System.currentTimeMillis())
                .apply()
            Result.success(workDataOf("scheduled" to plan.size, KEY_RUN_ID to runId))
        } catch (e: Exception) {
            val needsReview = hasPending(runs, runId)
            runs.edit()
                .putString("$runId.status", if (needsReview) "needs_review" else "failed")
                .putString("$runId.error", e.message ?: e.javaClass.simpleName)
                .apply()
            state.edit()
                .putBoolean("running", false)
                .putString(
                    "last_status",
                    if (needsReview) {
                        "AUTO يحتاج مراجعة قبل المتابعة: ${e.message ?: "نتيجة نشر غير مؤكدة"}"
                    } else {
                        "توقف AUTO: ${e.message ?: e.javaClass.simpleName}"
                    },
                )
                .apply()
            Result.failure(workDataOf("error" to (e.message ?: e.javaClass.simpleName), KEY_RUN_ID to runId))
        }
    }

    private fun updateProgress(current: Int, total: Int) {
        setProgressAsync(workDataOf(KEY_CURRENT to current, KEY_TOTAL to total))
    }

    private fun readUris(raw: String): List<Uri> = buildList {
        val array = JSONArray(raw)
        for (i in 0 until array.length()) {
            val value = array.optString(i)
            if (value.isNotBlank()) add(Uri.parse(value))
        }
    }

    private fun readStringArray(raw: String, size: Int): MutableList<String> {
        val out = MutableList(size) { "" }
        runCatching {
            val a = JSONArray(raw)
            for (i in 0 until minOf(size, a.length())) out[i] = a.optString(i)
        }
        return out
    }

    private fun readBooleanArray(raw: String, size: Int): MutableList<Boolean> {
        val out = MutableList(size) { false }
        runCatching {
            val a = JSONArray(raw)
            for (i in 0 until minOf(size, a.length())) out[i] = a.optBoolean(i, false)
        }
        return out
    }

    private fun saveStringArray(prefs: SharedPreferences, key: String, values: List<String>) {
        val a = JSONArray()
        values.forEach { a.put(it) }
        check(prefs.edit().putString(key, a.toString()).commit()) { "تعذر حفظ نقطة الاستكمال" }
    }

    private fun saveBooleanArray(prefs: SharedPreferences, key: String, values: List<Boolean>) {
        val a = JSONArray()
        values.forEach { a.put(it) }
        check(prefs.edit().putString(key, a.toString()).commit()) { "تعذر حفظ نقطة الاستكمال" }
    }

    private fun hasPending(prefs: SharedPreferences, runId: String): Boolean {
        val raw = prefs.getString("$runId.pending_posts", "[]").orEmpty()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).any { a.optBoolean(it, false) }
        }.getOrDefault(false)
    }
}
