package com.bundlebot

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class WaListener : NotificationListenerService() {
    companion object {
        val last = ConcurrentHashMap<String, Notification>()
        val recent = ConcurrentHashMap<String, Long>()
    }

    @Suppress("DEPRECATION")
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        Store.init(this)
        val pkg = sbn.packageName
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return
        val n = sbn.notification
        val e = n.extras
        val conv = e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        var text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        (e.getParcelableArray(Notification.EXTRA_MESSAGES)?.lastOrNull() as? Bundle)?.getCharSequence("text")?.toString()?.let { text = it }
        val isGroup = e.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        Store.push("dbg", "${if (pkg.endsWith("w4b")) "Business" else "WhatsApp"} | conv=$conv | title=$title | group=$isGroup | $text", 10)
        val ok = when (Store.source) { "wa" -> pkg == "com.whatsapp"; "biz" -> pkg == "com.whatsapp.w4b"; else -> true }
        if (!ok || text.isBlank() || text.startsWith("🤖")) return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val name = conv ?: title ?: return
        val clean = name.replace(Regex("\\s*\\(\\d+ messages?\\)\\s*$"), "").trim()
        if (isGroup || conv != null) Store.addSeen(clean)
        val g = Store.matchGroup(name) ?: return
        last[Store.norm(name)] = n
        val msg = text
        Thread {
            try {
                val now = System.currentTimeMillis()
                val k = Store.norm(name) + "|" + msg
                val prev = recent[k]; recent[k] = now
                if (prev != null && now - prev < 15000) return@Thread
                Store.addTo("chat", JSONObject().put("g", g).put("who", "in").put("t", msg).put("time", now), 200)
                handle(g, name, msg)
            } catch (ex: Exception) { Store.push("dbg", "ERROR $ex", 10) }
        }.start()
    }

    private fun handle(g: String, name: String, text: String) {
        val r = Parser.parse(text)
        val num = r.number
        if (num == null && r.gb == null) return
        val m = Parser.match(r, text)
        val p = m.pkg
        val out: String = when {
            num == null -> "🤖 Please send the mobile number you want the bundle on."
            p != null -> "🤖 Received: ${p.title()} for $num. Please wait."
            m.options.isNotEmpty() -> "🤖 Which one for $num? " + m.options.mapIndexed { i, o -> "${i + 1}) ${o.sim} ${o.cat}: ${o.title()}" }.joinToString("  ") + "  Please send the number again with your choice."
            r.gb == null && r.name.isBlank() -> "🤖 Which package do you want for $num?"
            else -> "🤖 That bundle is not available. Please choose another."
        }
        if (num != null && p != null) {
            val now = System.currentTimeMillis()
            Store.addTo("sales", JSONObject().put("id", now).put("time", now).put("g", g).put("num", num)
                .put("sim", p.sim.ifBlank { r.network }).put("pkg", p.title()).put("price", p.price).put("status", "pending"), 2000)
            if (!Store.trained) Alarm.ring(this, "Do this bundle by hand: ${p.title()} for $num")
        }
        if (!Store.autoReply) return
        val sent = reply(Store.norm(name), out)
        Store.addTo("chat", JSONObject().put("g", g).put("who", "out").put("t", if (sent) out else "$out (could not be sent)").put("time", System.currentTimeMillis()), 200)
        if (!sent) Alarm.ring(this, "Could not reply in $g. Reply by hand.")
    }

    private fun reply(key: String, msg: String): Boolean {
        val n = last[key] ?: return false
        val acts = n.actions ?: return false
        for (a in acts) {
            val ri = a.remoteInputs ?: continue
            if (ri.isEmpty()) continue
            val i = Intent(); val b = Bundle()
            ri.forEach { b.putCharSequence(it.resultKey, msg) }
            RemoteInput.addResultsToIntent(ri, i, b)
            return try { a.actionIntent.send(this, 0, i); true } catch (e: Exception) { false }
        }
        return false
    }
}
