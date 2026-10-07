package dk.daaseoel

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Tjek/eTilbudsavis' offentlige tilbuds-API (det samme som deres egen app bruger).
 * Det er ikke officielt dokumenteret, så alt kendskab til det er samlet her.
 */
object TjekApi {
    private const val BASE = "https://squid-api.tjek.com/v2/offers/search"

    /** Henter alle aktuelle øltilbud inden for radius (søgning på "øl" og hvert mærke) og fjerner dubletter. */
    fun fetchBeerOffers(lat: Double, lng: Double, radiusM: Int, brands: Collection<String>): List<JSONObject> {
        val queries = listOf("øl", "dåseøl", "pilsner") + brands.map { it.lowercase(Locale("da", "DK")) }
        val byId = LinkedHashMap<String, JSONObject>()
        var failures = 0
        for (q in queries) {
            try {
                for (page in 0 until 2) {
                    val arr = search(q, lat, lng, radiusM, offset = page * 100)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        byId.putIfAbsent(o.getString("id"), o)
                    }
                    if (arr.length() < 100) break
                }
            } catch (e: Exception) {
                failures++
            }
        }
        if (failures == queries.size) throw java.io.IOException("Kunne ikke hente tilbud")
        return byId.values.toList()
    }

    private fun search(query: String, lat: Double, lng: Double, radiusM: Int, offset: Int): JSONArray {
        val url = String.format(
            Locale.US, "%s?query=%s&r_lat=%.5f&r_lng=%.5f&r_radius=%d&limit=100&offset=%d",
            BASE, URLEncoder.encode(query, "UTF-8"), lat, lng, radiusM, offset,
        )
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            return JSONArray(conn.inputStream.bufferedReader(Charsets.UTF_8).readText())
        } finally {
            conn.disconnect()
        }
    }
}
