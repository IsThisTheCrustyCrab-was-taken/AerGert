package de.seemoo.at_tracking_detection.util

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Utility to manage permissions, permanently monitor app usage in the background,
 * and persist chronological usage sessions (open time, close time, duration) for retrospective tracking.
 */
object AppUsageTracker {

    private const val PREF_LAST_CHECK_TIMESTAMP = "app_usage_last_check_timestamp"
    private const val HISTORY_FILE_NAME = "app_usage_history.json"
    private const val MAX_SAVED_SESSIONS = 5000
    private const val DEFAULT_BACKGROUND_CHECK_INTERVAL_MS = 30_000L // 30 seconds
    private const val LOOKBACK_WINDOW_MS = 24 * 60 * 60 * 1000L // 24 hours

    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val lock = Any()
    private var permanentTrackingJob: Job? = null

    /**
     * Represents a discrete session where an application was in the foreground.
     */
    data class AppUsageSession(
        val packageName: String,
        val appName: String,
        val startTime: Long,
        val endTime: Long,
        val durationMs: Long,
        val durationSeconds: Long,
        val formattedDuration: String,
        val formattedStart: String,
        val formattedEnd: String
    )

    /**
     * Represents aggregated foreground usage time for an app over a time range.
     */
    data class AppAggregateUsage(
        val packageName: String,
        val appName: String,
        val totalTimeInForegroundMs: Long
    )

    // Backwards compatibility alias for existing code referencing launch events
    typealias AppLaunchEvent = AppUsageSession

    /**
     * Represents a raw foreground or background event for processing.
     */
    internal data class RawEvent(
        val packageName: String,
        val isForeground: Boolean,
        val timestamp: Long
    )

    /**
     * Checks if the PACKAGE_USAGE_STATS permission has been granted via AppOps.
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false

        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Opens the system settings screen where the user can grant "Usage Access".
     */
    fun openUsageAccessSettings(context: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                data = Uri.parse("package:${context.packageName}")
            }
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Timber.w(e, "Failed to open package-specific usage access settings, falling back to general list")
            val fallbackIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(fallbackIntent)
        }
    }

    /**
     * Starts a permanent background coroutine loop that periodically checks
     * for newly closed/opened apps as long as the application process is alive.
     */
    fun startPermanentAppTracking(
        context: Context,
        intervalMs: Long = DEFAULT_BACKGROUND_CHECK_INTERVAL_MS
    ) {
        synchronized(lock) {
            if (permanentTrackingJob?.isActive == true) {
                Timber.d("Permanent app usage tracking is already running")
                return
            }

            val appContext = context.applicationContext
            permanentTrackingJob = CoroutineScope(Dispatchers.IO).launch {
                Timber.i("Started permanent background app usage monitoring (interval: ${intervalMs}ms)")
                while (isActive) {
                    try {
                        if (hasUsageStatsPermission(appContext)) {
                            checkAndRecordNewAppUsage(appContext)
                        } else {
                            Timber.d("Usage access permission not yet granted; skipping cycle")
                        }
                    } catch (t: Throwable) {
                        Timber.w(t, "Error during background app tracking cycle")
                    }
                    delay(intervalMs)
                }
            }
        }
    }

    /**
     * Stops the permanent background loop if active.
     */
    fun stopPermanentAppTracking() {
        synchronized(lock) {
            permanentTrackingJob?.cancel()
            permanentTrackingJob = null
            Timber.i("Stopped permanent background app usage monitoring")
        }
    }

    /**
     * Core background checking logic:
     * Reads all new app usage sessions completed since the last recorded timestamp,
     * logs human-readable summaries, appends them to formatted history, and advances the timestamp.
     */
    fun checkAndRecordNewAppUsage(context: Context): List<AppUsageSession> {
        if (!hasUsageStatsPermission(context)) {
            return emptyList()
        }

        val appContext = context.applicationContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
        val now = System.currentTimeMillis()

        synchronized(lock) {
            val lastCheck = prefs.getLong(PREF_LAST_CHECK_TIMESTAMP, 0L).let { stored ->
                if (stored <= 0L) {
                    // First run: check the past hour
                    now - (60 * 60 * 1000L)
                } else {
                    stored
                }
            }

            if (now <= lastCheck) {
                return emptyList()
            }

            val newSessions = getAppUsageSessions(appContext, lastCheck, now)

            if (newSessions.isNotEmpty()) {
                val summaryLines = newSessions.joinToString("\n") { session ->
                    "  • ${session.appName}: ${session.formattedDuration} (${session.formattedStart.substringAfter(" ")} -> ${session.formattedEnd.substringAfter(" ")})"
                }
                Timber.i("Recorded ${newSessions.size} completed app session(s):\n$summaryLines")

                // Append to persistent pretty history file
                saveToHistoryFile(appContext, newSessions)
            }

            // Update timestamp so we don't duplicate sessions on next scan
            prefs.edit { putLong(PREF_LAST_CHECK_TIMESTAMP, now) }

            return newSessions
        }
    }

    /**
     * Backwards-compatible alias for existing callers.
     */
    fun checkAndRecordNewAppLaunches(context: Context): List<AppUsageSession> {
        return checkAndRecordNewAppUsage(context)
    }

    /**
     * Retrieves all recorded app usage history from the persistent storage.
     */
    fun getRecordedHistory(context: Context): List<AppUsageSession> {
        val file = File(context.filesDir, HISTORY_FILE_NAME)
        synchronized(lock) {
            if (!file.exists()) return emptyList()
            return try {
                val json = file.readText()
                val type = object : TypeToken<List<AppUsageSession>>() {}.type
                gson.fromJson<List<AppUsageSession>>(json, type) ?: emptyList()
            } catch (e: Exception) {
                Timber.w(e, "Failed to read app usage history file")
                emptyList()
            }
        }
    }

    /**
     * Clears stored history file and resets the check timestamp.
     */
    fun clearHistory(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            val file = File(appContext.filesDir, HISTORY_FILE_NAME)
            if (file.exists()) {
                file.delete()
            }
            PreferenceManager.getDefaultSharedPreferences(appContext).edit {
                remove(PREF_LAST_CHECK_TIMESTAMP)
            }
        }
    }

    private fun saveToHistoryFile(context: Context, newSessions: List<AppUsageSession>) {
        val file = File(context.filesDir, HISTORY_FILE_NAME)
        try {
            val existingList: MutableList<AppUsageSession> = if (file.exists()) {
                val json = file.readText()
                val type = object : TypeToken<MutableList<AppUsageSession>>() {}.type
                try {
                    gson.fromJson(json, type) ?: mutableListOf()
                } catch (_: Exception) {
                    mutableListOf()
                }
            } else {
                mutableListOf()
            }

            val existingKeys = existingList.map { "${it.packageName}_${it.startTime}_${it.endTime}" }.toSet()
            val uniqueNewSessions = newSessions.filter { "${it.packageName}_${it.startTime}_${it.endTime}" !in existingKeys }
            existingList.addAll(uniqueNewSessions)

            // Keep only the most recent sessions up to MAX_SAVED_SESSIONS
            val trimmedList = if (existingList.size > MAX_SAVED_SESSIONS) {
                existingList.takeLast(MAX_SAVED_SESSIONS)
            } else {
                existingList
            }

            file.writeText(gson.toJson(trimmedList))
        } catch (e: Exception) {
            Timber.w(e, "Failed to persist app usage history")
        }
    }

    /**
     * Queries usage events and stitches foreground and background transitions
     * to compute start time, end time, and duration for each usage session.
     */
    fun getAppUsageSessions(
        context: Context,
        startTime: Long,
        endTime: Long
    ): List<AppUsageSession> {
        if (!hasUsageStatsPermission(context)) {
            Timber.w("Cannot query usage events: PACKAGE_USAGE_STATS permission not granted")
            return emptyList()
        }

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()

        // Look back 24h prior to startTime so sessions starting before startTime but ending inside range are detected
        val queryStart = (startTime - LOOKBACK_WINDOW_MS).coerceAtLeast(0L)
        val events = usageStatsManager.queryEvents(queryStart, endTime) ?: return emptyList()

        val packageManager = context.packageManager
        val launcherPackages = getLauncherPackages(packageManager)
        val appNameCache = mutableMapOf<String, String>()

        val rawEvents = mutableListOf<RawEvent>()

        val outEvent = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(outEvent)
            val pkgName = outEvent.packageName ?: continue

            // Filter out our own app and home launchers
            if (pkgName == context.packageName || launcherPackages.contains(pkgName)) {
                continue
            }

            val eventType = outEvent.eventType
            val isForeground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                eventType == UsageEvents.Event.ACTIVITY_RESUMED
            } else {
                @Suppress("DEPRECATION")
                eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            }

            val isBackground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                eventType == UsageEvents.Event.ACTIVITY_PAUSED || eventType == UsageEvents.Event.ACTIVITY_STOPPED
            } else {
                @Suppress("DEPRECATION")
                eventType == UsageEvents.Event.MOVE_TO_BACKGROUND
            }

            if (isForeground) {
                rawEvents.add(RawEvent(pkgName, true, outEvent.timeStamp))
            } else if (isBackground) {
                rawEvents.add(RawEvent(pkgName, false, outEvent.timeStamp))
            }
        }

        val allSessions = processRawEvents(rawEvents) { pkg ->
            appNameCache.getOrPut(pkg) { resolveAppName(packageManager, pkg) }
        }

        // Filter sessions that ended within or after the requested startTime window
        return allSessions.filter { it.endTime > startTime && it.startTime <= endTime }.sortedBy { it.startTime }
    }

    /**
     * Uses UsageStatsManager.queryUsageStats to get aggregated total foreground time per app
     * within the specified time range.
     */
    fun getAggregateAppUsage(
        context: Context,
        startTime: Long,
        endTime: Long
    ): List<AppAggregateUsage> {
        if (!hasUsageStatsPermission(context)) return emptyList()
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()

        val statsList = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        ) ?: return emptyList()

        val packageManager = context.packageManager
        val launcherPackages = getLauncherPackages(packageManager)
        val appNameCache = mutableMapOf<String, String>()

        val aggregatedMap = mutableMapOf<String, Long>()
        for (stat in statsList) {
            val pkg = stat.packageName ?: continue
            if (pkg == context.packageName || launcherPackages.contains(pkg)) continue
            val time = stat.totalTimeInForeground
            if (time > 0) {
                aggregatedMap[pkg] = (aggregatedMap[pkg] ?: 0L) + time
            }
        }

        return aggregatedMap.map { (pkg, totalTime) ->
            val appName = appNameCache.getOrPut(pkg) { resolveAppName(packageManager, pkg) }
            AppAggregateUsage(
                packageName = pkg,
                appName = appName,
                totalTimeInForegroundMs = totalTime
            )
        }.sortedByDescending { it.totalTimeInForegroundMs }
    }

    /**
     * Pure function to process raw foreground/background events into completed AppUsageSessions.
     */
    internal fun processRawEvents(
        rawEvents: List<RawEvent>,
        appNameResolver: (String) -> String
    ): List<AppUsageSession> {
        val completedSessions = mutableListOf<AppUsageSession>()
        val eventsByPackage = rawEvents.groupBy { it.packageName }

        for ((pkgName, pkgEvents) in eventsByPackage) {
            var sessionStart: Long? = null
            var lastBackgroundTime: Long? = null

            for (ev in pkgEvents) {
                if (ev.isForeground) {
                    if (sessionStart == null) {
                        sessionStart = ev.timestamp
                        lastBackgroundTime = null
                    } else if (lastBackgroundTime != null) {
                        if (ev.timestamp - lastBackgroundTime > 2000L) {
                            // Previous session ended and a new session started after a gap
                            val durationMs = lastBackgroundTime - sessionStart
                            if (durationMs >= 500L) {
                                val appName = appNameResolver(pkgName)
                                completedSessions.add(
                                    createSession(pkgName, appName, sessionStart, lastBackgroundTime)
                                )
                            }
                            sessionStart = ev.timestamp
                            lastBackgroundTime = null
                        } else {
                            // Brief pause/transition <= 2s: App resumed, clear background timestamp
                            lastBackgroundTime = null
                        }
                    }
                } else {
                    // Background event
                    if (sessionStart != null) {
                        lastBackgroundTime = ev.timestamp
                    }
                }
            }

            // Finalize any session that ended with a background event
            if (sessionStart != null && lastBackgroundTime != null && lastBackgroundTime >= sessionStart) {
                val durationMs = lastBackgroundTime - sessionStart
                if (durationMs >= 500L) {
                    val appName = appNameResolver(pkgName)
                    completedSessions.add(
                        createSession(pkgName, appName, sessionStart, lastBackgroundTime)
                    )
                }
            }
        }

        return completedSessions.sortedBy { it.startTime }
    }

    private fun createSession(
        packageName: String,
        appName: String,
        startTime: Long,
        endTime: Long
    ): AppUsageSession {
        val durationMs = endTime - startTime
        val roundedSeconds = (durationMs + 500L) / 1000L
        return AppUsageSession(
            packageName = packageName,
            appName = appName,
            startTime = startTime,
            endTime = endTime,
            durationMs = durationMs,
            durationSeconds = roundedSeconds,
            formattedDuration = formatDuration(durationMs),
            formattedStart = formatTimestamp(startTime),
            formattedEnd = formatTimestamp(endTime)
        )
    }

    fun formatDuration(durationMs: Long): String {
        return when {
            durationMs < 1000L -> "${durationMs}ms"
            durationMs < 60_000L -> {
                val secs = durationMs / 1000.0
                String.format(Locale.US, "%.1fs", secs)
            }
            else -> {
                val totalSecs = durationMs / 1000L
                val mins = totalSecs / 60L
                val secs = totalSecs % 60L
                if (secs == 0L) "${mins}m" else "${mins}m ${secs}s"
            }
        }
    }

    /**
     * Resolves all installed home screen / launcher package names
     * so their events can be excluded from app usage tracking.
     */
    private fun getLauncherPackages(packageManager: PackageManager): Set<String> {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                homeIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(
                homeIntent,
                PackageManager.MATCH_DEFAULT_ONLY
            )
        }
        val launcherPkgs = resolveInfos.mapNotNull { it.activityInfo?.packageName }.toMutableSet()
        // Common known stock/OEM launcher fallbacks
        launcherPkgs.add("com.google.android.apps.nexuslauncher")
        launcherPkgs.add("com.android.launcher3")
        return launcherPkgs
    }

    private fun resolveAppName(packageManager: PackageManager, packageName: String): String {
        return try {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    private fun formatTimestamp(timestamp: Long): String {
        return LocalDateTime.ofInstant(
            Instant.ofEpochMilli(timestamp),
            ZoneId.systemDefault()
        ).format(timeFormatter)
    }
}
