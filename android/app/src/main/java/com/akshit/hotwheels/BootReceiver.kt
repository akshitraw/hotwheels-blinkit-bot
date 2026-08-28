package com.akshit.hotwheels

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restart watching after a reboot, but only if it was on when we shut down. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        if (Store(context).enabled) WatcherService.start(context)
    }
}
