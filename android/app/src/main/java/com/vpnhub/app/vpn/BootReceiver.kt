package com.vpnhub.app.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.vpnhub.app.data.Prefs

/** Reconnects the VPN after the phone reboots, when the user asked for it in settings. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            -> {
                if (!Prefs.autoConnectBoot) return
                // VPN mode needs the permission already granted (we can't show the dialog at boot);
                // proxy mode needs nothing. If consent is missing, wait until the user opens the app.
                if (Prefs.mode != Prefs.MODE_PROXY && VpnService.prepare(context) != null) return
                runCatching { VpnController.start(context) }
            }
        }
    }
}
