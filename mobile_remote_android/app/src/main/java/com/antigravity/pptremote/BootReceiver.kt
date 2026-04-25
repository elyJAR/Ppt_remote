package com.antigravity.pptremote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            // Only start service if user had bridge URL configured (meaning they used the app before)
            val savedUrl = RemotePrefs.getBridgeUrl(context)
            if (savedUrl.isNotBlank()) {
                RemoteControlService.start(context)
            }
        }
    }
}
