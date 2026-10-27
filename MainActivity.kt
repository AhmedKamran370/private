package com.bundlebot

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val G = 0xFF0A7A4F.toInt()
    private val INK = 0xFF14231C.toInt()
    private val GREY = 0xFF5D7066.toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val ui = Handler(Looper.getMainLooper())
    private val curs = arrayOf("PKR", "SAR", "AED", "USD")
    private val rate = mapOf("PKR" to 1.0, "USD" to 0.0036, "AED" to 0.0132, "SAR" to 0.0135)
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var title: TextView
    private lateinit var back: TextView
    private lateinit var act: TextView
    private var cur = "home"
    private var rKind = "d"
    private var rDate = 0L
    private class Row(val sim: () -> String, val cat: () -> String, val name: EditText, val info: EditText, val price: EditText)
    private val rows = ArrayList<Row>()
    private val bankRows = ArrayList<Pair<EditText, EditText>>()

    private fun dp(i: Int) = (i * resources.displayMetrics.density).toInt()
    private fun bg(c: Int, r: Int = 14) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }
    private fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun txt(t: String, sz: Float = 15f, c: Int = INK, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sz; setTextColor(c); if (bold) setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(3), 0, dp(3))
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = bg(Color.WHITE); setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = lp(MATCH, WRAP).apply { setMargins(0, 0, 0, dp(12)) }
    }
    private fun btn(t: String, filled: Boolean = true, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(if (filled) Color.WHITE else G)
        background = bg(if (filled) G else 0xFFDFF3E9.toInt(), 12); stateListAnimator = null
        setOnClickListener { f() }; layoutParams = lp(MATCH, WRAP).apply { setMargins(0, dp(6), 0, 0) }
    }
    private fun edit(v: String, hint: String, num: Boolean = false) = EditText(this).apply {
        setText(v); this.hint = hint; textSize = 14f; if (num) inputType = InputType.TYPE_CLASS_NUMBER
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Store.init(this)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFFF4F7F5.toInt()) }
        val head = LinearLayout(this).apply { setBackgroundColor(G); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)) }
        back = txt("‹ Home", 15f, Color.WHITE, true).apply { setOnClickListener { show("home") } }
        title = txt("", 18f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        act = txt("", 14f, Color.WHITE, true).apply { gravity = Gravity.END }
        head.addView(back, lp(dp(80), WRAP)); head.addView(title, lp(0, WRAP, 1f)); head.addView(act, lp(dp(96), WRAP))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(30)) }
        scroll = ScrollView(this).apply { addView(content) }
        col.addView(head); col.addView(scroll, lp(MATCH, 0, 1f))
        val frame = FrameLayout(this)
        frame.addView(col)
        val sp = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(G); isClickable = true }
        val ic = ImageView(this).apply { setImageResource(R.drawable.ic_fg); alpha = 0f; scaleX = 0.4f; scaleY = 0.4f }
        val nm = txt("Bundle Bot", 26f, Color.WHITE, true).apply { gravity = Gravity.CENTER; alpha = 0f }
        sp.addView(ic, lp(dp(180), dp(180))); sp.addView(nm, lp(WRAP, WRAP))
        frame.addView(sp, FrameLayout.LayoutParams(MATCH, MATCH))
        ic.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(700).start()
        nm.animate().alpha(1f).setStartDelay(500).setDuration(500).start()
        ui.postDelayed({ sp.animate().alpha(0f).setDuration(400).withEndAction { frame.removeView(sp) }.start() }, 1700)
        setContentView(frame)
        show("home")
        tick()
    }

    private fun tick() { ui.postDelayed({ if (cur == "chat" || cur == "home") show(cur); tick() }, 5000) }
    override fun onResume() { super.onResume(); if (::content.isInitialized) show(cur) }

    private fun show(p: String) {
        cur = p; content.removeAllViews(); rows.clear(); bankRows.clear(); act.text = ""; act.setOnClickListener(null)
        back.visibility = if (p == "home") View.INVISIBLE else View.VISIBLE
        title.text = mapOf("home" to "Bundle Bot", "chat" to "Messages", "pk" to "Packages", "rec" to "Records",
            "proof" to "Proofs", "bank" to "Banks", "set" to "Setup", "help" to "Help")[p]
        when (p) { "home" -> home(); "chat" -> chat(); "pk" -> packages(); "rec" -> records(); "proof" -> proofs(); "bank" -> banks(); "set" -> setup(); "help" -> help() }
    }

    private fun home() {
        val notif = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true
        val acc = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains(packageName) == true
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val st = card()
        fun line(l: String, ok: Boolean) = st.addView(txt((if (ok) "● " else "○ ") + l + (if (ok) "  ON" else "  OFF"), 14f, if (ok) G else 0xFFB3261E.toInt()))
        line("WhatsApp messages", notif); line("Screen control", acc); line("Internet", net)
        content.addView(st)
        val a = Store.arr("sales"); var pend = 0; var done = 0; var sum = 0
        val s0 = dayStart()
        for (i in 0 until a.length()) { val o = a.getJSONObject(i)
            if (o.optString("status") == "pending") pend++
            if (o.optString("status") == "done" && o.optLong("time") >= s0) { done++; sum += o.optInt("price") } }
        val sc = card()
        sc.addView(txt("Today", 13f, GREY)); sc.addView(txt("$done sold  ·  ${money(sum)}", 20f, G, true)); sc.addView(txt("$pend waiting for you", 14f, if (pend > 0) 0xFFB45309.toInt() else GREY))
        content.addView(sc)
        content.addView(btn("🔕 Stop alarm", false) { Alarm.stop(this) })
        val tiles = listOf("chat" to "💬 Messages", "pk" to "📦 Packages", "rec" to "📊 Records", "proof" to "🧾 Proofs", "bank" to "🏦 Banks", "set" to "⚙️ Setup", "help" to "🤖 Help")
        tiles.chunked(2).forEach { pair ->
            val r = LinearLayout(this)
            pair.forEach { (pg, lb) ->
                val t = txt(lb, 16f, INK, true).apply { gravity = Gravity.CENTER; background = bg(Color.WHITE); setPadding(0, dp(26), 0, dp(26)); setOnClickListener { show(pg) } }
                r.addView(t, lp(0, WRAP, 1f).apply { setMargins(dp(4), dp(8), dp(4), dp(4)) })
            }
            if (pair.size == 1) r.addView(View(this), lp(0, WRAP, 1f))
            content.addView(r)
        }
    }

    private fun dayStart(): Long { val c = Calendar.getInstance(); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0); return c.timeInMillis }
    private fun money(v: Int): String {
        val pkr = v / (rate[Store.listCur] ?: 1.0)
        val out = pkr * (rate[Store.recCur] ?: 1.0)
        return (if (Store.recCur == "PKR") out.toInt().toString() else String.format(Locale.US, "%.2f", out)) + " " + Store.recCur
    }

    private fun chat() {
        val a = Store.arr("chat")
        if (a.length() == 0) content.addView(txt("No messages yet. Messages from your ticked groups appear here, with the replies the app sends.", 14f, GREY))
        val f = SimpleDateFormat("HH:mm", Locale.US)
        for (i in maxOf(0, a.length() - 100) until a.length()) {
            val o = a.getJSONObject(i); val out = o.optString("who") == "out"
            val t = txt((if (!out) o.optString("g") + "\n" else "") + o.optString("t") + "\n" + f.format(Date(o.optLong("time"))), 14f).apply {
                background = bg(if (out) 0xFFDFF3E9.toInt() else Color.WHITE, 16); setPadding(dp(12), dp(8), dp(12), dp(8)) }
            content.addView(t, lp(WRAP, WRAP).apply { gravity = if (out) Gravity.END else Gravity.START; setMargins(if (out) dp(48) else 0, dp(4), if (out) 0 else dp(48), dp(4)) })
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun packages() {
        content.addView(btn("Choose list file (photo, PDF, Word or text)") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf", "text/plain", "text/csv", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            }, 7)
        })
        content.addView(txt("Prices are private. Clients never see them and they are not used to pick a bundle.", 12f, GREY))
        val l = Store.pkgs()
        if (l.isEmpty()) content.addView(txt("No list yet. Photos and PDFs need your free Gemini key (Setup page).", 14f, GREY))
        for ((sim, ps) in l.groupBy { it.sim }) {
            content.addView(txt(if (sim.isBlank()) "Other" else sim, 20f, G, true))
            for ((cat, qs) in ps.groupBy { it.cat }) {
                content.addView(txt(cat.ifBlank { "Packages" }, 14f, GREY, true))
                val cd = card()
                qs.forEach { q -> addRow(cd, q.sim, q.cat, q) }
                content.addView(cd)
            }
        }
        content.addView(btn("➕ Add a package", false) {
            val cd = card()
            val s = edit("", "Network (e.g. Jazz)"); val c = edit("", "Section (e.g. Weekly packages)")
            cd.addView(s); cd.addView(c)
            addRow(cd, "", "", Pkg("", "", "", "", 0), { s.text.toString().trim() }, { c.text.toString().trim() })
            content.addView(cd, content.childCount - 2)
        })
        content.addView(btn("Save list") {
            Store.savePkgs(rows.filter { it.name.text.isNotBlank() }.map { Pkg(it.sim(), it.cat(), it.name.text.toString().trim(), it.info.text.toString().trim(), it.price.text.toString().toIntOrNull() ?: 0) })
            toast("Saved"); show("pk")
        })
    }

    private fun addRow(parent: LinearLayout, sim: String, cat: String, q: Pkg, sf: () -> String = { sim }, cf: () -> String = { cat }) {
        val n = edit(q.name, "Package name"); val i = edit(q.info, "Details (GB, minutes, validity)")
        val pr = edit(if (q.price > 0) q.price.toString() else "", Store.listCur, true)
        val r = LinearLayout(this)
        r.addView(n, lp(0, WRAP, 1f)); r.addView(pr, lp(dp(90), WRAP))
        parent.addView(r); parent.addView(i)
        rows.add(Row(sf, cf, n, i, pr))
    }

    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        val u = data?.data ?: return
        if (rc != 7) return
        val mime = contentResolver.getType(u) ?: ""
        toast("Reading the list…")
        Thread {
            try {
                val bytes = contentResolver.openInputStream(u)!!.use { it.readBytes() }
                val key = Store.key
                val list: List<Pkg>? = when {
                    mime.startsWith("text") || mime.contains("wordprocessingml") -> {
                        val raw = if (mime.startsWith("text")) String(bytes) else Parser.docxText(bytes)
                        (if (key.isNotBlank()) Parser.aiList(JSONArray().put(JSONObject().put("text", raw)), key) else null) ?: Parser.localList(raw)
                    }
                    key.isBlank() -> { runOnUiThread { toast("Photos and PDFs need the Gemini key. Add it in Setup.") }; null }
                    else -> Parser.aiList(JSONArray().put(Parser.inline(bytes, mime)), key) ?: run { runOnUiThread { toast("AI error: ${Parser.lastError}") }; null }
                }
                if (list != null) {
                    if (list.isEmpty()) runOnUiThread { toast("No packages found in that file") }
                    else { Store.savePkgs(list); runOnUiThread { show("pk"); toast("Loaded ${list.size} packages. Check them and edit if needed.") } }
                }
            } catch (e: Exception) { runOnUiThread { toast("Could not read the file: $e") } }
        }.start()
    }

    private fun range(): Pair<Long, Long> {
        val c = Calendar.getInstance(); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        when (rKind) {
            "w" -> c.set(Calendar.DAY_OF_WEEK, c.firstDayOfWeek)
            "m" -> c.set(Calendar.DAY_OF_MONTH, 1)
            "y" -> { c.set(Calendar.MONTH, 0); c.set(Calendar.DAY_OF_MONTH, 1) }
            "c" -> c.timeInMillis = rDate
        }
        val s = c.timeInMillis
        when (rKind) { "w" -> c.add(Calendar.WEEK_OF_YEAR, 1); "m" -> c.add(Calendar.MONTH, 1); "y" -> c.add(Calendar.YEAR, 1); else -> c.add(Calendar.DAY_OF_YEAR, 1) }
        return Pair(s, c.timeInMillis)
    }

    private fun records() {
        val label = when (rKind) { "d" -> "Today"; "w" -> "This week"; "m" -> "This month"; "y" -> "This year"; else -> SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(rDate)) }
        act.text = "$label ▾"
        act.setOnClickListener {
            AlertDialog.Builder(this).setItems(arrayOf("Today", "This week", "This month", "This year", "Pick a date…")) { _, w ->
                if (w == 4) {
                    val c = Calendar.getInstance()
                    DatePickerDialog(this, DatePickerDialog.OnDateSetListener { _, y, m, d ->
                        val x = Calendar.getInstance(); x.set(y, m, d, 0, 0, 0); x.set(Calendar.MILLISECOND, 0)
                        rKind = "c"; rDate = x.timeInMillis; show("rec")
                    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                } else { rKind = arrayOf("d", "w", "m", "y")[w]; show("rec") }
            }.show()
        }
        val (s, e) = range()
        val a = Store.arr("sales"); val list = ArrayList<JSONObject>()
        for (i in a.length() - 1 downTo 0) { val o = a.getJSONObject(i); if (o.optLong("time") in s until e) list.add(o) }
        val done = list.filter { it.optString("status") == "done" }
        val tc = card()
        tc.addView(txt("${done.size} sold", 13f, GREY)); tc.addView(txt(money(done.sumOf { it.optInt("price") }), 24f, G, true))
        tc.addView(btn("Currency: ${Store.recCur} (tap to change)", false) { Store.recCur = curs[(curs.indexOf(Store.recCur) + 1) % curs.size]; show("rec") })
        tc.addView(btn("Save as Excel file (CSV)", false) { exportCsv(list) })
        content.addView(tc)
        if (list.isEmpty()) content.addView(txt("No requests in this period.", 14f, GREY))
        val f = SimpleDateFormat("d MMM HH:mm", Locale.US)
        for (o in list) {
            val c = card(); val st = o.optString("status")
            c.addView(txt("${f.format(Date(o.optLong("time")))}  ·  ${o.optString("num")} (${o.optString("sim")})", 13f, GREY))
            c.addView(txt(o.optString("pkg"), 15f, INK, true))
            c.addView(txt(money(o.optInt("price")) + "  ·  " + st.uppercase(), 14f, when (st) { "done" -> G; "failed" -> 0xFFB3261E.toInt(); else -> 0xFFB45309.toInt() }, true))
            if (st == "pending") {
                val r = LinearLayout(this)
                r.addView(btn("Done") { Store.setSale(o.optLong("id"), "done"); show("rec") }, lp(0, WRAP, 1f))
                r.addView(btn("Failed", false) { Store.setSale(o.optLong("id"), "failed"); show("rec") }, lp(0, WRAP, 1f))
                c.addView(r)
            }
            content.addView(c)
        }
    }

    private fun exportCsv(list: List<JSONObject>) {
        try {
            val f = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val csv = "Time,Number,Network,Package,Price (${Store.recCur}),Status\n" + list.joinToString("\n") {
                "${f.format(Date(it.optLong("time")))},${it.optString("num")},${it.optString("sim")},\"${it.optString("pkg")}\",${money(it.optInt("price")).substringBefore(" ")},${it.optString("status")}" }
            if (Build.VERSION.SDK_INT < 29) { toast("Needs Android 10 or newer"); return }
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "bundlebot_${System.currentTimeMillis()}.csv"); put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/BundleBot")
            }
            val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
            contentResolver.openOutputStream(u)?.use { it.write(csv.toByteArray()) }
            toast("Saved in Downloads/BundleBot. Open it with Excel.")
        } catch (e: Exception) { toast("Could not save: $e") }
    }

    private fun proofs() {
        content.addView(txt("Payment proofs (price cropped off) are saved here and in Pictures/BundleBot.", 13f, GREY))
        if (Build.VERSION.SDK_INT >= 29) {
            val q = contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("Pictures/BundleBot%"), "${MediaStore.Images.Media.DATE_ADDED} DESC")
            var n = 0
            q?.use { while (it.moveToNext() && n < 30) {
                val iv = ImageView(this).apply { adjustViewBounds = true; setImageURI(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it.getLong(0))) }
                content.addView(iv, lp(MATCH, dp(280)).apply { setMargins(0, dp(6), 0, dp(6)) }); n++ } }
            if (n == 0) content.addView(txt("No proofs yet.", 14f, GREY))
        }
    }

    private fun banks() {
        content.addView(txt("Add the banking apps you use. Balances and low-fund alerts start working after the Easypaisa lesson is added in the next version.", 13f, GREY))
        val a = Store.arr("banks")
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i); val c = card()
            c.addView(txt(o.optString("n") + if (o.optBoolean("active")) "  ✔ in use" else "", 17f, G, true))
            val bal = edit(o.optString("bal"), "Balance", true); val low = edit(o.optString("low"), "Warn me below", true)
            c.addView(bal); c.addView(low); bankRows.add(Pair(bal, low))
            c.addView(btn("Use this bank", false) { saveBanks(); val x = Store.arr("banks"); for (j in 0 until x.length()) x.getJSONObject(j).put("active", j == i); Store.saveArr("banks", x); show("bank") })
            content.addView(c)
        }
        val nb = edit("", "New bank name (e.g. Easypaisa)")
        content.addView(nb)
        content.addView(btn("Add bank") { if (nb.text.isNotBlank()) { saveBanks(); val x = Store.arr("banks"); x.put(JSONObject().put("n", nb.text.toString().trim()).put("bal", "").put("low", "").put("active", x.length() == 0)); Store.saveArr("banks", x); show("bank") } })
        content.addView(btn("Save balances", false) { saveBanks(); toast("Saved") })
    }

    private fun saveBanks() {
        val a = Store.arr("banks")
        for (i in 0 until minOf(a.length(), bankRows.size)) { a.getJSONObject(i).put("bal", bankRows[i].first.text.toString()).put("low", bankRows[i].second.text.toString()) }
        Store.saveArr("banks", a)
    }

    private fun setup() {
        val p = card()
        p.addView(txt("Permissions", 17f, G, true))
        p.addView(btn("1. Allow WhatsApp message access") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
        p.addView(btn("2. Allow screen control") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        p.addView(txt("If screen control switches itself off: Settings > Apps > Bundle Bot > Battery > Unrestricted, allow auto-start, and lock the app in recent apps.", 12f, GREY))
        content.addView(p)
        val w = card(); w.addView(txt("Which WhatsApp?", 17f, G, true))
        val src = RadioGroup(this)
        listOf("both" to "WhatsApp and WhatsApp Business", "wa" to "WhatsApp only", "biz" to "WhatsApp Business only").forEach { (k, l) ->
            src.addView(RadioButton(this).apply { id = View.generateViewId(); text = l; isChecked = Store.source == k; setOnClickListener { Store.source = k } }) }
        w.addView(src); content.addView(w)
        val g = card(); g.addView(txt("Groups to watch, and the currency clients are told", 17f, G, true))
        Store.seen().forEach { n ->
            val r = LinearLayout(this); r.gravity = Gravity.CENTER_VERTICAL
            r.addView(CheckBox(this).apply { text = n; isChecked = n in Store.selected(); setOnCheckedChangeListener { _, c -> Store.toggle(n, c) } }, lp(0, WRAP, 1f))
            r.addView(txt(Store.cur(n), 14f, G, true).apply { background = bg(0xFFDFF3E9.toInt(), 10); setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { val nx = curs[(curs.indexOf(Store.cur(n)) + 1) % curs.size]; Store.setCur(n, nx); text = nx } })
            g.addView(r)
        }
        val gn = edit("", "Add a group by name"); g.addView(gn)
        g.addView(btn("Add group", false) { val x = gn.text.toString().trim(); if (x.isNotEmpty()) { Store.addSeen(x); Store.toggle(x, true); show("set") } })
        g.addView(Switch(this).apply { text = "Reply to clients on WhatsApp"; isChecked = Store.autoReply; setOnCheckedChangeListener { _, c -> Store.autoReply = c } })
        content.addView(g)
        val k = card(); k.addView(txt("AI key (free Gemini key from aistudio.google.com)", 15f, G, true))
        val ke = edit(Store.key, "Gemini API key"); k.addView(ke)
        k.addView(btn("Save key") { Store.key = ke.text.toString().trim(); toast("Saved") })
        k.addView(btn("List currency: ${Store.listCur} (tap to change)", false) { Store.listCur = curs[(curs.indexOf(Store.listCur) + 1) % curs.size]; show("set") })
        content.addView(k)
        val x = card(); x.addView(txt("Network prefixes (first 4 digits)", 15f, G, true))
        val pe = edit(Store.prefixes, "0300-0309=Jazz"); pe.minLines = 4; x.addView(pe)
        x.addView(btn("Save prefixes", false) { Store.prefixes = pe.text.toString(); toast("Saved") })
        content.addView(x)
        val t = card(); t.addView(txt("Teach the app Easypaisa (one time)", 15f, G, true))
        t.addView(txt("Start, open Easypaisa and buy one bundle by hand. Stop before typing the PIN. Come back, press Copy, and send it to Claude.", 12f, GREY))
        t.addView(btn("Start recording (60 s)") { Store.recText = ""; Store.rec = true; toast("Recording. Open Easypaisa now."); ui.postDelayed({ Store.rec = false }, 60000) })
        t.addView(btn("Copy recording", false) { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("rec", Store.recText)); toast("Copied ${Store.recText.length} characters") })
        t.addView(btn("Screenshot test (open Easypaisa within 5 s)", false) { ui.postDelayed({ val a = A11y.inst; if (a == null) toast("Turn on screen control first") else a.shotTest { m -> runOnUiThread { toast(m) } } }, 5000) })
        t.addView(btn("Test the alarm", false) { Alarm.ring(this, "Test alarm") })
        content.addView(t)
    }

    private fun help() {
        val c = card()
        c.addView(txt("Ask anything about how the app works.", 15f, INK, true))
        val q = edit("", "Type your question"); c.addView(q)
        val ans = txt("", 14f)
        c.addView(btn("Ask") {
            if (Store.key.isBlank()) { toast("Add your Gemini key in Setup first"); return@btn }
            ans.text = "Thinking…"
            val ctx = "You are the help assistant inside the Bundle Bot Android app for mobile bundle sellers in Pakistan. Pages: Messages (chat with clients), Packages (the seller's list by network and section, editable, import from photo/PDF/Word), Records (sales by day/week/month/year/date, currency, CSV export), Proofs, Banks, Setup (permissions, groups, currency, Gemini key, prefixes, Easypaisa lesson). The app reads WhatsApp notifications, matches the bundle by network, section and GB, and replies on WhatsApp. Paying through Easypaisa starts only after the one-time lesson is added. Answer briefly and simply. Question: "
            Thread { val r = Parser.gemini(JSONArray().put(JSONObject().put("text", ctx + q.text)), Store.key); runOnUiThread { ans.text = r ?: "Error: ${Parser.lastError}" } }.start()
        })
        c.addView(ans); content.addView(c)
    }
}
