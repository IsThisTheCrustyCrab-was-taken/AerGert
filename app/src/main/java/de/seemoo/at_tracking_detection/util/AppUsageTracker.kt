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
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * Utility to manage permissions, permanently monitor app usage in the background,
 * and persist chronological launch history for retrospective tracking.
 */
object AppUsageTracker {

    private const val PREF_LAST_CHECK_TIMESTAMP = "app_usage_last_check_timestamp"
    private const val HISTORY_FILE_NAME = "app_usage_history.json"
    private const val MAX_SAVED_EVENTS = 5000
    private const val DEFAULT_BACKGROUND_CHECK_INTERVAL_MS = 30_000L // 30 seconds

    private val gson = Gson()
    private val lock = Any()
    private var permanentTrackingJob: Job? = null

    data class AppLaunchEvent(
        val packageName: String,
        val appName: String,
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
     * for newly opened apps as long as the application process is alive.
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
                            checkAndRecordNewAppLaunches(appContext)
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
     * Reads all new app launch events since the last recorded timestamp,
     * logs them, appends them to persistent history, and advances the timestamp.
     *
     * Can be invoked from WorkManager, AlarmReceivers, or the permanent coroutine loop.
     */
    fun checkAndRecordNewAppLaunches(context: Context): List<AppLaunchEvent> {
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

            val newEvents = getAppLaunchEvents(appContext, lastCheck, now)

            if (newEvents.isNotEmpty()) {
                Timber.i("Found ${newEvents.size} newly opened app events in background")
                for (event in newEvents) {
                    Timber.d("Tracked app open: ${event.appName} (${event.packageName}) at ${event.timestamp}")
                }

                // Append to persistent history file
                saveToHistoryFile(appContext, newEvents)
            }

            // Update timestamp so we don't duplicate events on next scan
            prefs.edit { putLong(PREF_LAST_CHECK_TIMESTAMP, now) }

            return newEvents
        }
    }

    /**
     * Retrieves all recorded app launch history from the persistent storage.
     */
    fun getRecordedHistory(context: Context): List<AppLaunchEvent> {
        val file = File(context.filesDir, HISTORY_FILE_NAME)
        synchronized(lock) {
            if (!file.exists()) return emptyList()
            return try {
                val json = file.readText()
                val type = object : TypeToken<List<AppLaunchEvent>>() {}.type
                gson.fromJson<List<AppLaunchEvent>>(json, type) ?: emptyList()
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

    private fun saveToHistoryFile(context: Context, newEvents: List<AppLaunchEvent>) {
        val file = File(context.filesDir, HISTORY_FILE_NAME)
        try {
            val existingList: MutableList<AppLaunchEvent> = if (file.exists()) {
                val json = file.readText()
                val type = object : TypeToken<MutableList<AppLaunchEvent>>() {}.type
                gson.fromJson(json, type) ?: mutableListOf()
            } else {
                mutableListOf()
            }

            existingList.addAll(newEvents)

            // Keep only the most recent events up to MAX_SAVED_EVENTS
            val trimmedList = if (existingList.size > MAX_SAVED_EVENTS) {
                existingList.takeLast(MAX_SAVED_EVENTS)
            } else {
                existingList
            }

            file.writeText(gson.toJson(trimmedList))
        } catch (e: Exception) {
            Timber.w(e, "Failed to persist app usage history")
        }
    }

    /**
     * Queries app launch events (when apps are brought to the foreground / resumed)
     * within the specified time range.
     */
    fun getAppLaunchEvents(
        context: Context,
        startTime: Long,
        endTime: Long
    ): List<AppLaunchEvent> {
        if (!hasUsageStatsPermission(context)) {
            Timber.w("Cannot query usage events: PACKAGE_USAGE_STATS permission not granted")
            return emptyList()
        }

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()

        val events = usageStatsManager.queryEvents(startTime, endTime) ?: return emptyList()
        val eventList = mutableListOf<AppLaunchEvent>()
        val packageManager = context.packageManager
        val appNameCache = mutableMapOf<String, String>()

        val outEvent = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(outEvent)

            // ACTIVITY_RESUMED (API 29+) or MOVE_TO_FOREGROUND (<29) indicate an app being opened/focused
            val isForeground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                outEvent.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            } else {
                @Suppress("DEPRECATION")
                outEvent.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            }

            if (isForeground) {
                val pkgName = outEvent.packageName ?: continue
                // Filter out our own package name from being recorded as "other apps"
                if (pkgName == context.packageName) continue

                val appName = appNameCache.getOrPut(pkgName) {
                    resolveAppName(packageManager, pkgName)
                }
                eventList.add(
                    AppLaunchEvent(
                        packageName = pkgName,
                        appName = appName,
                        timestamp = outEvent.timeStamp
                    )
                )
            }
        }

        return eventList
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
}
