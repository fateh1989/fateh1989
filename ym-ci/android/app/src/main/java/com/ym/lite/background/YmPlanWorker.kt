package com.ym.lite.background

import android.content.Context
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

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<YmPlanWorker>()
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
        state.edit().putBoolean("running", true).putString("last_status", "AUTO بدأ تجهيز الخطة").apply()

        return try {
            require(local.getBoolean("provider_proof_passed", false)) {
                "اختبار النشر لم ينجح بعد"
            }

            val accountId = local.getString("selected_account_id", "").orEmpty()
            require(accountId.isNotBlank()) { "لا يوجد حساب محدد" }

            val uris = readUris(local.getString("wheel_uris", "[]").orEmpty())
            require(uris.isNotEmpty()) { "مكتبة الفيديوهات فارغة" }

            val days = local.getString("horizon_days", "30")?.toIntOrNull()?.coerceIn(1, 30) ?: 30
            val perDay = local.getString("daily_count", "1")?.toIntOrNull()?.coerceIn(1, 10) ?: 1
            val start = PlanEngine.parseClock(local.getString("window_start", "18:00").orEmpty())
            val end = PlanEngine.parseClock(local.getString("window_end", "23:00").orEmpty())
            val caption = local.getString("caption", "").orEmpty()

            val plan = PlanEngine.generate(
                ZoneId.systemDefault(),
                LocalDate.now().plusDays(1),
                days,
                perDay,
                start,
                end,
                uris.size,
                accountId.hashCode().toLong(),
            )

            val secure = SecurePrefs(applicationContext)
            val baseUrl = secure.workerUrl()
            val token = secure.token()
            require(baseUrl.startsWith("https://")) { "Worker URL غير محفوظ" }
            require(token.isNotBlank()) { "رمز الاقتران غير محفوظ" }
            val api = YmRelayClient(baseUrl, token)

            // Upload each library item once, then reuse its provider media URL across the plan.
            val mediaUrls = MutableList<String?>(uris.size) { null }
            val total = uris.size + plan.size
            var progress = 0
            setProgressAsync(workDataOf(KEY_CURRENT to progress, KEY_TOTAL to total))

            uris.forEachIndexed { index, uri ->
                check(!isStopped) { "تم إيقاف AUTO" }
                state.edit().putString("last_status", "رفع فيديو ${index + 1}/${uris.size}").apply()
                val upload = api.createUploadTarget()
                api.uploadSigned(
                    applicationContext.contentResolver,
                    uri,
                    upload.uploadUrl,
                    applicationContext.contentResolver.getType(uri),
                )
                mediaUrls[index] = upload.mediaUrl
                progress++
                setProgressAsync(workDataOf(KEY_CURRENT to progress, KEY_TOTAL to total))
            }

            plan.forEachIndexed { index, slot ->
                check(!isStopped) { "تم إيقاف AUTO" }
                val mediaUrl = mediaUrls[slot.mediaIndex] ?: error("media URL missing")
                state.edit().putString(
                    "last_status",
                    "جدولة ${index + 1}/${plan.size} • ${slot.instant}",
                ).apply()
                api.createScheduledPost(
                    accountId,
                    caption,
                    mediaUrl,
                    slot.instant.toString(),
                )
                progress++
                setProgressAsync(workDataOf(KEY_CURRENT to progress, KEY_TOTAL to total))
            }

            state.edit()
                .putBoolean("running", false)
                .putString("last_status", "تم تسليم ${plan.size} منشورًا للخطة السحابية. يمكن إبقاء YM مغلقًا.")
                .putInt("last_plan_size", plan.size)
                .putLong("last_completed_at", System.currentTimeMillis())
                .apply()
            Result.success(workDataOf("scheduled" to plan.size))
        } catch (e: Exception) {
            state.edit()
                .putBoolean("running", false)
                .putString("last_status", "توقف AUTO: ${e.message ?: e.javaClass.simpleName}")
                .apply()
            // Do not auto-retry after partial scheduling because that could duplicate posts.
            Result.failure(workDataOf("error" to (e.message ?: e.javaClass.simpleName)))
        }
    }

    private fun readUris(raw: String): List<Uri> = buildList {
        val array = JSONArray(raw)
        for (i in 0 until array.length()) {
            val value = array.optString(i)
            if (value.isNotBlank()) add(Uri.parse(value))
        }
    }
}
