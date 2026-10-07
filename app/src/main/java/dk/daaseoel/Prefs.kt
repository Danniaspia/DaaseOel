package dk.daaseoel

import android.content.Context
import org.json.JSONArray

/** Alle indstillinger og det seneste resultat, delt mellem app, widget og baggrundsjob. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("daaseoel", Context.MODE_PRIVATE)

    var radiusKm: Int
        get() = sp.getInt("radius", 10)
        set(v) = sp.edit().putInt("radius", v).apply()

    /** true = vis og sammenlign pris pr. dåse, false = literpris. */
    var perCan: Boolean
        get() = sp.getBoolean("per_can", false)
        set(v) = sp.edit().putBoolean("per_can", v).apply()

    /** De konkrete øl, der tæller (navne fra [Beers.ALL]). */
    var beers: Set<String>
        get() = sp.getStringSet("beers", null)?.toSet() ?: Beers.DEFAULT
        set(v) = sp.edit().putStringSet("beers", v).apply()

    /** true = brug telefonens position, false = fast adresse. */
    var useGps: Boolean
        get() = sp.getBoolean("gps", true)
        set(v) = sp.edit().putBoolean("gps", v).apply()

    var address: String
        get() = sp.getString("address", "") ?: ""
        set(v) = sp.edit().putString("address", v).apply()

    // Positionerne gemmes hver for sig, så man kan skifte frem og tilbage.
    fun location(gps: Boolean = useGps): Pair<Double, Double>? {
        val k = if (gps) "gps" else "addr"
        if (!sp.contains("${k}_lat")) return null
        return sp.getString("${k}_lat", null)!!.toDouble() to sp.getString("${k}_lng", null)!!.toDouble()
    }

    fun locationTime(gps: Boolean = useGps): Long = sp.getLong("${if (gps) "gps" else "addr"}_time", 0)
    fun locationLabel(gps: Boolean = useGps): String = sp.getString("${if (gps) "gps" else "addr"}_label", "") ?: ""

    fun setLocation(gps: Boolean, lat: Double, lng: Double, label: String?) {
        val k = if (gps) "gps" else "addr"
        val e = sp.edit().putString("${k}_lat", lat.toString()).putString("${k}_lng", lng.toString())
            .putLong("${k}_time", System.currentTimeMillis())
        if (label != null) e.putString("${k}_label", label)
        e.apply()
    }

    var deals: List<Deal>
        get() = runCatching {
            val arr = JSONArray(sp.getString("deals", "[]"))
            List(arr.length()) { Deal.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        set(v) = sp.edit().putString("deals", JSONArray(v.map { it.toJson() }).toString()).apply()

    var updatedAt: Long
        get() = sp.getLong("updated", 0)
        set(v) = sp.edit().putLong("updated", v).apply()

    var error: String?
        get() = sp.getString("error", null)
        set(v) = sp.edit().putString("error", v).apply()

    // Gemmes som tidspunkt, så en opdatering der blev afbrudt (app lukket) ikke hænger for evigt.
    var refreshing: Boolean
        get() = System.currentTimeMillis() - sp.getLong("refreshing", 0) < 90_000
        set(v) = sp.edit().putLong("refreshing", if (v) System.currentTimeMillis() else 0).commit().let { }

    /** Aktuelle rammer med de valgte øl, billigste først (pr. dåse eller pr. liter). */
    fun ranked(now: Long = System.currentTimeMillis()): List<Deal> {
        val selected = beers
        return Ranking.rank(deals.filter { it.runTill == 0L || it.runTill > now }.mapNotNull { it.forSelection(selected) }, perCan)
    }
}
