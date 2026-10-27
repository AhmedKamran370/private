package com.bundlebot

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

data class Req(val number: String?, val gb: Double?, val days: Int?, val name: String, val network: String)
data class Pkg(val sim: String, val cat: String, val name: String, val info: String, val price: Int) {
    val gb: Double? get() = Parser.gbOf("$name $info")
    fun title() = listOf(name, info).filter { it.isNotBlank() }.joinToString(" - ")
}
class M(val pkg: Pkg?, val options: List<Pkg>)

object Parser {
    var lastError = ""
    private val numRe = Regex("(?:\\+?92|0)?(3\\d{2})[-\\s]?(\\d{7})")
    private val gbRe = Regex("(\\d+(?:\\.\\d+)?)\\s*(gb|mb)", RegexOption.IGNORE_CASE)
    private const val LIST_PROMPT = "Convert this mobile internet bundle price list to a JSON array and output only the JSON. " +
        "Each item: {\"sim\":\"network name such as Jazz, Zong, Ufone, Telenor or SCO, or empty\",\"cat\":\"section heading such as Weekly packages or Monthly All data and social packages or Monthly call packages\"," +
        "\"name\":\"package name\",\"info\":\"details such as GB, minutes, SMS, validity\",\"price\":number only}. Keep every package."

    fun gbOf(t: String): Double? = gbRe.find(t)?.let { val v = it.groupValues[1].toDouble(); if (it.groupValues[2].lowercase() == "mb") v / 1024 else v }
    fun daysOf(t: String): Int? {
        val l = t.lowercase()
        return Regex("(\\d+)\\s*day").find(l)?.groupValues?.get(1)?.toInt()
            ?: when { "month" in l -> 30; "week" in l -> 7; "daily" in l || "day" in l -> 1; else -> null }
    }

    fun network(n: String?): String {
        if (n == null || n.length < 4) return "unknown"
        val pre = n.substring(0, 4).toIntOrNull() ?: return "unknown"
        for (l in Store.prefixes.lines()) {
            val m = Regex("(\\d{4})(?:-(\\d{4}))?=(.+)").find(l.trim()) ?: continue
            val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toIntOrNull() ?: a
            if (pre in a..b) return m.groupValues[3].trim()
        }
        return "unknown"
    }

    fun local(text: String): Req {
        val m = numRe.find(text)
        val number = m?.let { "0" + it.groupValues[1] + it.groupValues[2] }
        val rest = if (m != null) text.replace(m.value, " ") else text
        val name = rest.replace(gbRe, " ").replace(Regex("\\s+"), " ").trim()
        return Req(number, gbOf(rest), daysOf(rest), name, network(number))
    }

    fun gemini(parts: JSONArray, key: String): String? {
        return try {
            val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 20000; c.readTimeout = 90000
            c.setRequestProperty("x-goog-api-key", key); c.setRequestProperty("content-type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val resp = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
            if (code !in 200..299) { lastError = "HTTP $code: " + resp.take(160); return null }
            JSONObject(resp).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        } catch (e: Exception) { lastError = e.toString(); null }
    }

    private fun ai(text: String, key: String): Req? {
        val prompt = "Extract a mobile internet bundle request from this WhatsApp message. Reply with JSON only: " +
            "{\"number\":\"03XXXXXXXXX or null\",\"gb\":number or null,\"days\":number or null,\"name\":\"package name or empty\"}. Message: " + text
        val out = gemini(JSONArray().put(JSONObject().put("text", prompt)), key) ?: return null
        return try {
            val j = JSONObject(out.substring(out.indexOf('{'), out.lastIndexOf('}') + 1))
            val num = j.optString("number", "").takeIf { it.matches(Regex("03\\d{9}")) }
            Req(num, if (j.isNull("gb")) null else j.optDouble("gb"), if (j.isNull("days")) null else j.optInt("days"), j.optString("name", ""), network(num))
        } catch (e: Exception) { lastError = e.toString(); null }
    }

    fun parse(text: String): Req {
        val k = Store.key
        val loc = local(text)
        val a = if (k.isNotBlank()) ai(text, k) else null
        return if (a != null && a.number == loc.number) a else loc
    }

    // Matches by network (prefix or name in the message), section words, then GB, name or price. Price is never sent to clients.
    fun match(r: Req, text: String): M {
        val all = Store.pkgs()
        if (all.isEmpty()) return M(null, emptyList())
        val l = text.lowercase()
        var sim = r.network
        all.map { it.sim }.filter { it.isNotBlank() }.distinct().firstOrNull { l.contains(it.lowercase()) }?.let { sim = it }
        var c = all
        val bySim = all.filter { it.sim.isNotBlank() && it.sim.equals(sim, true) }
        if (bySim.isNotEmpty()) c = bySim
        fun narrow(keys: List<String>) {
            val f = c.filter { p -> keys.any { (p.cat + " " + p.name + " " + p.info).lowercase().contains(it) } }
            if (f.isNotEmpty()) c = f
        }
        if ("week" in l) narrow(listOf("week")) else if ("month" in l) narrow(listOf("month")) else if (Regex("\\bdaily\\b|\\bday\\b").containsMatchIn(l)) narrow(listOf("day"))
        if ("call" in l || "minute" in l) narrow(listOf("call", "min")) else if ("social" in l || "all data" in l) narrow(listOf("social", "all data"))
        val g = r.gb
        if (g != null) c = c.filter { val x = it.gb; x != null && Math.abs(x - g) < 0.01 }
        else {
            val skip = setOf("weekly", "monthly", "daily", "call", "social", "data", "package", "bundle", "please", "need", "want", "send")
            val w = r.name.lowercase().split(Regex("\\W+")).filter { it.length > 3 && it !in skip }
            var f: List<Pkg> = if (w.isEmpty()) emptyList() else c.filter { p -> w.any { (p.name + " " + p.info).lowercase().contains(it) } }
            if (f.isEmpty()) {
                val nums = Regex("\\b\\d{1,4}\\b").findAll(r.name).map { it.value.toInt() }.toList()
                f = c.filter { it.price in nums && nums.isNotEmpty() }
            }
            c = f
        }
        if (c.isEmpty()) return M(null, emptyList())
        if (c.map { it.sim + "|" + it.cat + "|" + it.name }.distinct().size > 1) return M(null, c.take(5))
        return M(c.first(), emptyList())
    }

    fun inline(b: ByteArray, mime: String): JSONObject =
        JSONObject().put("inline_data", JSONObject().put("mime_type", mime).put("data", Base64.encodeToString(b, Base64.NO_WRAP)))

    fun aiList(extra: JSONArray, key: String): List<Pkg>? {
        val parts = JSONArray().put(JSONObject().put("text", LIST_PROMPT))
        for (i in 0 until extra.length()) parts.put(extra.get(i))
        val out = gemini(parts, key) ?: return null
        return try {
            val a = JSONArray(out.substring(out.indexOf('['), out.lastIndexOf(']') + 1))
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                val pr = (o.opt("price")?.toString() ?: "").replace(Regex("[^0-9.]"), "").toDoubleOrNull()?.toInt() ?: 0
                Pkg(o.optString("sim"), o.optString("cat"), o.optString("name"), o.optString("info"), pr)
            }.filter { it.name.isNotBlank() }
        } catch (e: Exception) { lastError = "Could not understand the AI answer"; null }
    }

    // Offline reader: headings become network or section, a name line followed by a price line is joined.
    fun localList(raw: String): List<Pkg> {
        val sims = listOf("jazz", "zong", "ufone", "telenor", "sco")
        val priceRe = Regex("(?:^|[\\s=:|,-])(?:rs\\.?|pkr|sar|aed|usd)?\\s*(\\d{1,5})\\s*(?:rs\\.?|pkr|sar|aed|usd)?$", RegexOption.IGNORE_CASE)
        var sim = ""; var cat = ""; var pend: String? = null
        val out = ArrayList<Pkg>()
        for (line in raw.lines().map { it.trim().trim('|').trim() }.filter { it.isNotEmpty() }) {
            val m = priceRe.find(line)
            if (m == null) {
                val low = line.lowercase()
                if (line.length < 30 && sims.any { Regex("\\b$it\\b").containsMatchIn(low) }) { sim = line; pend = null }
                else if (line.length < 60 && !Regex("\\d\\s*(gb|mb)", RegexOption.IGNORE_CASE).containsMatchIn(line)) cat = line
                else pend = line
            } else {
                val head = line.substring(0, m.range.first).trim().trim('|', '=', ':', '-', ',').trim()
                val parts = (if (head.isBlank()) (pend ?: "") else head).split("|").map { it.trim() }.filter { it.isNotEmpty() }
                pend = null
                if (parts.isNotEmpty()) out.add(Pkg(sim, cat, parts[0], parts.drop(1).joinToString(" | "), m.groupValues[1].toInt()))
            }
        }
        return out
    }

    fun docxText(b: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(b)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name == "word/document.xml") {
                    var x = z.readBytes().toString(Charsets.UTF_8)
                    x = Regex("<w:tr[ >].*?</w:tr>", RegexOption.DOT_MATCHES_ALL).replace(x) { it.value.replace("</w:tc>", " | ").replace("</w:p>", " ") + "\n" }
                    return x.replace("</w:p>", "\n").replace(Regex("<[^>]+>"), "").replace("&amp;", "&")
                }
                e = z.nextEntry
            }
        }
        return ""
    }
}
