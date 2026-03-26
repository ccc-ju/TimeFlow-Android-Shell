package com.timeflow.selfdiscipline.reminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class ReminderRestoreReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent != null ? intent.getAction() : "";
        Log.i(ReminderScheduler.LOG_TAG, "ReminderRestoreReceiver onReceive action=" + action);
        ReminderScheduler.restoreSchedule(context);
    }
}
