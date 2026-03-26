package com.timeflow.selfdiscipline.reminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

public class ReminderAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final int day = intent.getIntExtra(ReminderScheduler.EXTRA_DAY, 0);
        final int hour = intent.getIntExtra(ReminderScheduler.EXTRA_HOUR, 21);
        final int minute = intent.getIntExtra(ReminderScheduler.EXTRA_MINUTE, 0);
        String title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE);
        String content = intent.getStringExtra(ReminderScheduler.EXTRA_CONTENT);
        String triggeredMode = intent.getStringExtra(ReminderScheduler.EXTRA_SCHEDULE_MODE);

        if (TextUtils.isEmpty(title)) {
            title = "TimeFlow Reminder";
        }
        if (TextUtils.isEmpty(content)) {
            content = "Review today and plan your next focus session.";
        }
        if (TextUtils.isEmpty(triggeredMode)) {
            triggeredMode = ReminderScheduler.getStoredScheduleMode(context);
        }

        Log.i(ReminderScheduler.LOG_TAG, "ReminderAlarmReceiver onReceive day=" + day + " hour=" + hour + " minute=" + minute + " mode=" + triggeredMode);

        final String nextMode = ReminderScheduler.scheduleAlarm(
            context,
            day,
            hour,
            minute,
            title,
            content,
            ReminderScheduler.resolveScheduleMode(context)
        );
        ReminderScheduler.persistScheduleMode(context, nextMode);

        ReminderScheduler.showNotification(context, day, title, content);
    }
}
