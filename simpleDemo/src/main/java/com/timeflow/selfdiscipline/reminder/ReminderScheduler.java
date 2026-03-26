package com.timeflow.selfdiscipline.reminder;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public final class ReminderScheduler {
    public static final String LOG_TAG = "TimeFlowReminder";
    public static final String CHANNEL_ID = "timeflow_daily_reminder";
    public static final String CHANNEL_NAME = "TimeFlow Daily Reminder";
    public static final String CHANNEL_DESC = "TimeFlow daily focus reminders";
    public static final String ACTION_TRIGGER = "io.timeflow.localnotification.ACTION_TRIGGER";
    public static final String PREFS_NAME = "timeflow_local_notification";
    public static final String KEY_TIME = "time";
    public static final String KEY_REPEAT_DAYS = "repeat_days";
    public static final String KEY_TITLE = "title";
    public static final String KEY_CONTENT = "content";
    public static final String KEY_SCHEDULE_MODE = "schedule_mode";
    public static final String EXTRA_DAY = "day";
    public static final String EXTRA_HOUR = "hour";
    public static final String EXTRA_MINUTE = "minute";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_CONTENT = "content";
    public static final String EXTRA_SCHEDULE_MODE = "schedule_mode";
    public static final String BACKEND = "android-shell";
    public static final String SCHEDULE_MODE_DISABLED = "disabled";
    public static final String SCHEDULE_MODE_EXACT = "exact";
    public static final String SCHEDULE_MODE_INEXACT = "inexact";

    private ReminderScheduler() {
    }

    public static boolean isAvailable() {
        return true;
    }

    public static String syncReminderSchedule(Context context, String time, String repeatDaysCsv, String title, String content) {
        final boolean exactAlarmSupported = supportsExactAlarms();
        final boolean exactAlarmGranted = context != null && canUseExactAlarms(context);

        if (context == null) {
            return buildResult("syncReminderSchedule:fail app context unavailable", 0, SCHEDULE_MODE_DISABLED, exactAlarmSupported, exactAlarmGranted);
        }

        final List<Integer> repeatDays = parseRepeatDays(repeatDaysCsv);
        if (repeatDays.isEmpty()) {
            clearStoredSchedule(context);
            cancelAllAlarms(context);
            clearDisplayedNotifications(context);
            return buildResult("syncReminderSchedule:fail repeatDays required", 0, SCHEDULE_MODE_DISABLED, exactAlarmSupported, exactAlarmGranted);
        }

        final int[] parsedTime = parseTime(time);
        final String safeTitle = TextUtils.isEmpty(title) ? "TimeFlow Reminder" : title;
        final String safeContent = TextUtils.isEmpty(content) ? "Review today and plan your next focus session." : content;
        final String preferredMode = resolveScheduleMode(context);
        String scheduledMode = preferredMode;
        Log.i(LOG_TAG, "syncReminderSchedule time=" + time + " repeatDays=" + repeatDaysCsv + " preferredMode=" + preferredMode + " now=" + Calendar.getInstance().getTime());

        try {
            cancelAllAlarms(context);
            clearDisplayedNotifications(context);
            ensureNotificationChannel(context);

            for (Integer day : repeatDays) {
                final String alarmMode = scheduleAlarm(
                    context,
                    day,
                    parsedTime[0],
                    parsedTime[1],
                    safeTitle,
                    safeContent,
                    preferredMode
                );
                if (!SCHEDULE_MODE_EXACT.equals(alarmMode)) {
                    scheduledMode = SCHEDULE_MODE_INEXACT;
                }
            }

            persistSchedule(context, padTime(parsedTime[0], parsedTime[1]), repeatDaysCsv, safeTitle, safeContent, scheduledMode);
            final String errMsg = SCHEDULE_MODE_EXACT.equals(scheduledMode)
                ? "syncReminderSchedule:ok"
                : "syncReminderSchedule:ok inexact";
            return buildResult(errMsg, repeatDays.size(), scheduledMode, exactAlarmSupported, exactAlarmGranted);
        } catch (Throwable error) {
            Log.e(LOG_TAG, "syncReminderSchedule failed", error);
            return buildResult("syncReminderSchedule:fail " + error, 0, SCHEDULE_MODE_DISABLED, exactAlarmSupported, exactAlarmGranted);
        }
    }

    public static String clearReminderSchedule(Context context) {
        final boolean exactAlarmSupported = supportsExactAlarms();
        final boolean exactAlarmGranted = context != null && canUseExactAlarms(context);

        if (context == null) {
            return buildResult("clearReminderSchedule:fail app context unavailable", 0, SCHEDULE_MODE_DISABLED, exactAlarmSupported, exactAlarmGranted);
        }

        try {
            clearStoredSchedule(context);
            cancelAllAlarms(context);
            clearDisplayedNotifications(context);
            return buildResult("clearReminderSchedule:ok", 0, SCHEDULE_MODE_DISABLED, exactAlarmSupported, exactAlarmGranted);
        } catch (Throwable error) {
            Log.e(LOG_TAG, "clearReminderSchedule failed", error);
            return buildResult("clearReminderSchedule:fail " + error, 0, SCHEDULE_MODE_DISABLED, exactAlarmSupported, exactAlarmGranted);
        }
    }

    public static String getRuntimeState(Context context) {
        final boolean exactAlarmSupported = supportsExactAlarms();
        final boolean exactAlarmGranted = context != null && canUseExactAlarms(context);
        final String scheduleMode;

        if (context == null) {
            scheduleMode = SCHEDULE_MODE_DISABLED;
        } else {
            final String storedMode = getStoredScheduleMode(context);
            if (TextUtils.isEmpty(storedMode) || SCHEDULE_MODE_DISABLED.equals(storedMode)) {
                scheduleMode = SCHEDULE_MODE_DISABLED;
            } else if (SCHEDULE_MODE_EXACT.equals(storedMode) && exactAlarmGranted) {
                scheduleMode = SCHEDULE_MODE_EXACT;
            } else {
                scheduleMode = SCHEDULE_MODE_INEXACT;
            }
        }

        return buildResult("getRuntimeState:ok", 0, scheduleMode, exactAlarmSupported, exactAlarmGranted);
    }

    public static void restoreSchedule(Context context) {
        if (context == null) {
            return;
        }

        final SharedPreferences prefs = getPreferences(context);
        final String time = prefs.getString(KEY_TIME, "");
        final String repeatDaysCsv = prefs.getString(KEY_REPEAT_DAYS, "");
        final String title = prefs.getString(KEY_TITLE, "TimeFlow Reminder");
        final String content = prefs.getString(KEY_CONTENT, "Review today and plan your next focus session.");

        if (TextUtils.isEmpty(time) || TextUtils.isEmpty(repeatDaysCsv)) {
            cancelAllAlarms(context);
            clearDisplayedNotifications(context);
            Log.i(LOG_TAG, "restoreSchedule skipped: empty schedule");
            return;
        }

        final List<Integer> repeatDays = parseRepeatDays(repeatDaysCsv);
        final int[] parsedTime = parseTime(time);
        final String preferredMode = resolveScheduleMode(context);
        String scheduledMode = preferredMode;
        Log.i(LOG_TAG, "syncReminderSchedule time=" + time + " repeatDays=" + repeatDaysCsv + " preferredMode=" + preferredMode + " now=" + Calendar.getInstance().getTime());

        cancelAllAlarms(context);

        for (Integer day : repeatDays) {
            final String alarmMode = scheduleAlarm(
                context,
                day,
                parsedTime[0],
                parsedTime[1],
                title,
                content,
                preferredMode
            );
            if (!SCHEDULE_MODE_EXACT.equals(alarmMode)) {
                scheduledMode = SCHEDULE_MODE_INEXACT;
            }
        }

        persistScheduleMode(context, scheduledMode);
        Log.i(LOG_TAG, "restoreSchedule complete repeatDays=" + repeatDaysCsv + " mode=" + scheduledMode + " time=" + time);
    }

    static String scheduleAlarm(Context context, int day, int hour, int minute, String title, String content, String scheduleMode) {
        final AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return SCHEDULE_MODE_DISABLED;
        }

        final long triggerAtMillis = computeNextTriggerMillis(day, hour, minute);
        Log.i(LOG_TAG, "scheduleAlarm day=" + day + " requestCode=" + requestCodeForDay(day) + " hour=" + hour + " minute=" + minute + " mode=" + scheduleMode + " now=" + Calendar.getInstance().getTime() + " triggerAt=" + new java.util.Date(triggerAtMillis));
        String effectiveMode = scheduleMode;
        PendingIntent pendingIntent = buildPendingIntent(context, day, hour, minute, title, content, effectiveMode);

        if (SCHEDULE_MODE_EXACT.equals(effectiveMode)) {
            if (Build.VERSION.SDK_INT >= 23) {
                try {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
                    Log.i(LOG_TAG, "setExactAndAllowWhileIdle success requestCode=" + requestCodeForDay(day));
                    return SCHEDULE_MODE_EXACT;
                } catch (Throwable error) {
                    Log.e(LOG_TAG, "setExactAndAllowWhileIdle failed requestCode=" + requestCodeForDay(day), error);
                    effectiveMode = SCHEDULE_MODE_INEXACT;
                    pendingIntent = buildPendingIntent(context, day, hour, minute, title, content, effectiveMode);
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
                    return effectiveMode;
                }
            }

            if (Build.VERSION.SDK_INT >= 19) {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
                return SCHEDULE_MODE_EXACT;
            }

            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
            return SCHEDULE_MODE_EXACT;
        }

        if (Build.VERSION.SDK_INT >= 23) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
            return SCHEDULE_MODE_INEXACT;
        }

        alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
        return SCHEDULE_MODE_INEXACT;
    }

    static boolean isAppInForeground(Context context) {
        final ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) {
            return false;
        }

        final List<ActivityManager.RunningAppProcessInfo> processes = activityManager.getRunningAppProcesses();
        if (processes == null) {
            return false;
        }

        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process != null && context.getPackageName().equals(process.processName)) {
                return process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
            }
        }

        return false;
    }

    static void persistScheduleMode(Context context, String scheduleMode) {
        getPreferences(context)
            .edit()
            .putString(KEY_SCHEDULE_MODE, scheduleMode)
            .apply();
    }

    static String getStoredScheduleMode(Context context) {
        return getPreferences(context).getString(KEY_SCHEDULE_MODE, SCHEDULE_MODE_DISABLED);
    }

    private static SharedPreferences getPreferences(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static void persistSchedule(Context context, String time, String repeatDaysCsv, String title, String content, String scheduleMode) {
        getPreferences(context)
            .edit()
            .putString(KEY_TIME, time)
            .putString(KEY_REPEAT_DAYS, repeatDaysCsv)
            .putString(KEY_TITLE, title)
            .putString(KEY_CONTENT, content)
            .putString(KEY_SCHEDULE_MODE, scheduleMode)
            .apply();
    }

    private static void clearStoredSchedule(Context context) {
        getPreferences(context)
            .edit()
            .remove(KEY_TIME)
            .remove(KEY_REPEAT_DAYS)
            .remove(KEY_TITLE)
            .remove(KEY_CONTENT)
            .remove(KEY_SCHEDULE_MODE)
            .apply();
    }

    private static boolean supportsExactAlarms() {
        return Build.VERSION.SDK_INT >= 31;
    }

    private static boolean canUseExactAlarms(Context context) {
        if (!supportsExactAlarms()) {
            return true;
        }

        final AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return false;
        }

        try {
            return alarmManager.canScheduleExactAlarms();
        } catch (Throwable error) {
            return false;
        }
    }

    static String resolveScheduleMode(Context context) {
        return canUseExactAlarms(context) ? SCHEDULE_MODE_EXACT : SCHEDULE_MODE_INEXACT;
    }

    private static List<Integer> parseRepeatDays(String repeatDaysCsv) {
        final List<Integer> days = new ArrayList<>();
        if (TextUtils.isEmpty(repeatDaysCsv)) {
            return days;
        }

        final String[] pieces = repeatDaysCsv.split(",");
        for (String piece : pieces) {
            if (TextUtils.isEmpty(piece)) {
                continue;
            }

            try {
                final int day = normalizeDay(Integer.parseInt(piece));
                if (!days.contains(day)) {
                    days.add(day);
                }
            } catch (Throwable error) {
                Log.w(LOG_TAG, "parseRepeatDays skipped invalid day=" + piece, error);
            }
        }

        return days;
    }

    private static int[] parseTime(String input) {
        final String safeInput = TextUtils.isEmpty(input) ? "21:00" : input;
        final String[] pieces = safeInput.split(":");
        if (pieces.length != 2) {
            return new int[]{21, 0};
        }

        return new int[]{
            normalizeTimePart(parseIntSafely(pieces[0], 21), 23),
            normalizeTimePart(parseIntSafely(pieces[1], 0), 59)
        };
    }

    private static String padTime(int hour, int minute) {
        return pad(hour) + ":" + pad(minute);
    }

    private static int parseIntSafely(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (Throwable error) {
            return fallback;
        }
    }

    private static int normalizeDay(int day) {
        if (day >= 0 && day <= 6) {
            return day;
        }
        return 0;
    }

    private static int normalizeTimePart(int value, int max) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, max);
    }

    private static int requestCodeForDay(int day) {
        return 42000 + normalizeDay(day);
    }

    private static int toCalendarDay(int day) {
        final int normalizedDay = normalizeDay(day);
        return normalizedDay == 0 ? Calendar.SUNDAY : normalizedDay + 1;
    }

    private static int toJsDay(int calendarDay) {
        return calendarDay == Calendar.SUNDAY ? 0 : calendarDay - 1;
    }

    private static long computeNextTriggerMillis(int day, int hour, int minute) {
        final Calendar now = Calendar.getInstance();
        final int normalizedDay = normalizeDay(day);
        final Calendar target = Calendar.getInstance();
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);
        target.set(Calendar.HOUR_OF_DAY, hour);
        target.set(Calendar.MINUTE, minute);
        target.set(Calendar.DAY_OF_WEEK, toCalendarDay(normalizedDay));

        if (target.getTimeInMillis() <= now.getTimeInMillis()) {
            target.add(Calendar.WEEK_OF_YEAR, 1);
        }

        return target.getTimeInMillis();
    }

    static void cancelAllAlarms(Context context) {
        final AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }

        for (int day = 0; day < 7; day += 1) {
            final PendingIntent pendingIntent = buildCancelIntent(context, day);
            alarmManager.cancel(pendingIntent);
            pendingIntent.cancel();
        }
    }

    static void clearDisplayedNotifications(Context context) {
        final NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        for (int day = 0; day < 7; day += 1) {
            manager.cancel(requestCodeForDay(day));
        }
    }

    static void showNotification(Context context, int day, String title, String content) {
        ensureNotificationChannel(context);

        final NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.getApplicationInfo().icon)
            .setContentTitle(title)
            .setContentText(content)
            .setTicker(content)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory("reminder")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(false)
            .setDefaults(Notification.DEFAULT_ALL);

        final PendingIntent contentIntent = buildContentIntent(context, day);
        if (contentIntent != null) {
            builder.setContentIntent(contentIntent);
        }

        NotificationManagerCompat.from(context).notify(requestCodeForDay(day), builder.build());
        Log.i(LOG_TAG, "showNotification requestCode=" + requestCodeForDay(day) + " title=" + title);
    }

    private static void ensureNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }

        final NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }

        final NotificationChannel existing = manager.getNotificationChannel(CHANNEL_ID);
        if (existing != null) {
            return;
        }

        final NotificationChannel channel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(CHANNEL_DESC);
        channel.enableVibration(true);
        channel.enableLights(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(channel);
    }

    private static PendingIntent buildPendingIntent(Context context, int day, int hour, int minute, String title, String content, String scheduleMode) {
        final Intent intent = createReminderIntent(context, day, hour, minute, title, content, scheduleMode);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, requestCodeForDay(day), intent, flags);
    }

    private static PendingIntent buildCancelIntent(Context context, int day) {
        final Intent intent = new Intent(ACTION_TRIGGER);
        intent.setClass(context, ReminderAlarmReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, requestCodeForDay(day), intent, flags);
    }

    private static PendingIntent buildContentIntent(Context context, int day) {
        final Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launchIntent == null) {
            return null;
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(context, requestCodeForDay(day) + 100, launchIntent, flags);
    }

    private static Intent createReminderIntent(Context context, int day, int hour, int minute, String title, String content, String scheduleMode) {
        final Intent intent = new Intent(ACTION_TRIGGER);
        intent.setClass(context, ReminderAlarmReceiver.class);
        intent.putExtra(EXTRA_DAY, normalizeDay(day));
        intent.putExtra(EXTRA_HOUR, hour);
        intent.putExtra(EXTRA_MINUTE, minute);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_CONTENT, content);
        intent.putExtra(EXTRA_SCHEDULE_MODE, scheduleMode);
        return intent;
    }

    private static String buildResult(String errMsg, int scheduledCount, String scheduleMode, boolean exactAlarmSupported, boolean exactAlarmGranted) {
        final JSONObject result = new JSONObject();
        try {
            result.put("errMsg", errMsg);
            result.put("backend", BACKEND);
            result.put("scheduledCount", scheduledCount);
            result.put("scheduleMode", scheduleMode);
            result.put("exactAlarmSupported", exactAlarmSupported);
            result.put("exactAlarmGranted", exactAlarmGranted);
        } catch (JSONException error) {
            Log.e(LOG_TAG, "buildResult failed", error);
        }
        return result.toString();
    }

    private static String pad(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }
}
