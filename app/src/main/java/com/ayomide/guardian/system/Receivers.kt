package com.ayomide.guardian.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.provider.Telephony
import com.ayomide.guardian.core.SmsCommand
import com.ayomide.guardian.data.EventLog
import com.ayomide.guardian.data.Prefs

/** Watches incoming texts for the find-my-phone secret word. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val prefs = Prefs(context)
        if (!prefs.findEnabled) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val bySender = msgs.filterNotNull().groupBy { it.originatingAddress ?: "" }
        for ((sender, parts) in bySender) {
            val body = parts.joinToString("") { it.messageBody ?: "" }
            if (!SmsCommand.matches(body, prefs.secret)) continue
            if (!SmsCommand.isTrusted(sender, prefs.trustedList)) {
                EventLog.add(context, "Ignored secret word from untrusted number")
                continue
            }
            EventLog.add(context, "Find-my-phone text received")
            if (!GuardService.start(context, GuardService.ACTION_RING, sender.ifBlank { null })) {
                // Android blocked the background start: at least alert and reply.
                Notifier.warn(context, Notifier.ID_ALARM, "Find my phone", "Your secret word was texted to this phone.", 1)
                if (sender.isNotBlank()) {
                    val pending = goAsync()
                    FindPhone.replyAsync(context, sender) { pending.finish() }
                }
            }
        }
    }
}

/** Restores guards after a restart or app update. */
class SystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        val prefs = Prefs(context)
        Jobs.scheduleAll(context)
        if (prefs.adEnabled && prefs.adApps.isNotEmpty() && VpnService.prepare(context) == null) AdBlockVpn.start(context)
        if (prefs.armed) GuardService.start(context, GuardService.ACTION_ARM)
        Tiles.refreshAll(context)
    }
}
