package com.bundlebot

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object Store {
    private lateinit var sp: SharedPreferences
    const val PX = "0300-0309=Jazz\n0320-0329=Jazz\n0310-0319=Zong\n0330-0337=Ufone\n0340-0349=Telenor\n0355=SCO"
    fun init(c: Context) { if (!::sp.isInitialized) sp = c.applicationContext.getSharedPreferences("bb", 0) }
    private fun s(k: String, d: String = ""): String = sp.getString(k, d)!!
    private fun p(k: String, v: String) { sp.edit().putString(k, v).apply() }
    private fun set(k: String) = HashSet(sp.getStringSet(k, emptySet())!!)
    fun seen(): List<String> = set("seen").sorted()
    fun selected(): Set<String> = set("sel")
    fun addSeen(g: String) { val x = set("seen"); if (x.add(g)) sp.edit().putStringSet("seen", x).apply() }
    fun toggle(g: String, on: Boolean) { val x = set("sel"); if (on) x.add(g) else x.remove(g); sp.edit().putStringSet("sel", x).apply() }
    fun cur(g: String): String = s("cur:$g", "PKR")
    fun setCur(g: String, c: String) = p("cur:$g", c)
    var key: String
        get() = s("key")
        set(v) = p("key", v)
    var source: String
        get() = s("source", "both")
        set(v) = p("source", v)
    var recText: String
        get() = s("rt")
        set(v) = p("rt", v)
    var listCur: String
        get() = s("lc", "PKR")
        set(v) = p("lc", v)
    var recCur: String
        get() = s("rc", "PKR")
        set(v) = p("rc", v)
    var prefixes: String
        get() = s("px", PX)
        set(v) = p("px", v)
    var autoReply: Boolean
        get() = sp.getBoolean("ar", true)
        set(v) { sp.edit().putBoolean("ar", v).apply() }
    var rec: Boolean
        get() = sp.getBoolean("rec", false)
        set(v) { sp.edit().putBoolean("rec", v).apply() }
    var trained: Boolean
        get() = sp.getBoolean("trained", false)
        set(v) { sp.edit().putBoolean("trained", v).apply() }
    fun arr(k: String): JSONArray = try { JSONArray(s(k, "[]")) } catch (e: Exception) { JSONArray() }
    fun saveArr(k: String, a: JSONArray) = p(k, a.toString())
    @Synchronized fun addTo(k: String, o: JSONObject, max: Int) {
        val a = arr(k); a.put(o)
        val n = JSONArray()
        for (i in maxOf(0, a.length() - max) until a.length()) n.put(a.get(i))
        saveArr(k, n)
    }
    @Synchronized fun setSale(id: Long, st: String) {
        val a = arr("sales")
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.optLong("id") == id) o.put("status", st) }
        saveArr("sales", a)
    }
    fun pkgs(): List<Pkg> {
        val a = arr("pk2")
        return (0 until a.length()).map { val o = a.getJSONObject(it); Pkg(o.optString("sim"), o.optString("cat"), o.optString("name"), o.optString("info"), o.optInt("price")) }
    }
    fun savePkgs(l: List<Pkg>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("sim", it.sim).put("cat", it.cat).put("name", it.name).put("info", it.info).put("price", it.price)) }
        saveArr("pk2", a)
    }
    @Synchronized fun push(k: String, line: String, max: Int) { p(k, (listOf(line) + list(k)).take(max).joinToString("\u0001")) }
    fun list(k: String): List<String> = s(k).split("\u0001").filter { it.isNotBlank() }
    fun norm(t: String) = t.lowercase().replace(Regex("\\s*\\(\\d+ messages?\\)\\s*$"), "").trim()
    fun matchGroup(t: String): String? {
        val n = norm(t)
        if (n.isEmpty()) return null
        return selected().firstOrNull { val x = norm(it); x == n || (x.length >= 4 && n.contains(x)) }
    }
}
