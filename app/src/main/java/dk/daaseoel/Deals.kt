package dk.daaseoel

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/** Hvordan tilbud sammenlignes og vises. */
enum class Mode(val label: String) {
    R18("Pr. 18 stk"),
    R24("Pr. 24 stk"),
    LITER("Literpris");

    val pieces get() = when (this) { R18 -> 18; R24 -> 24; LITER -> 0 }
}

/** Én konkret pakning fra ét tilbud, f.eks. "Carlsberg/Tuborg 18 × 33 cl til 69,95 kr hos MENY". */
data class Deal(
    val offerId: String,
    val dealer: String,
    val heading: String,
    val brands: List<String>,
    val price: Double,
    val pieces: Int,
    val cl: Double,
    val canConfirmed: Boolean,
    val runFrom: Long,
    val runTill: Long,
) {
    val perCan get() = price / pieces
    val perLiter get() = perCan / (cl / 100.0)

    fun toJson(): JSONObject = JSONObject()
        .put("id", offerId).put("dealer", dealer).put("heading", heading)
        .put("brands", JSONArray(brands)).put("price", price).put("pieces", pieces)
        .put("cl", cl).put("can", canConfirmed).put("from", runFrom).put("till", runTill)

    companion object {
        fun fromJson(o: JSONObject): Deal {
            val b = o.getJSONArray("brands")
            return Deal(
                o.getString("id"), o.getString("dealer"), o.getString("heading"),
                List(b.length()) { b.getString(it) }, o.getDouble("price"), o.getInt("pieces"),
                o.getDouble("cl"), o.getBoolean("can"), o.getLong("from"), o.getLong("till"),
            )
        }
    }
}

/** En pakning set gennem en bestemt visning: værdien der sammenlignes på. */
data class Ranked(val deal: Deal, val value: Double, val exactFrame: Boolean)

object Ranking {
    /**
     * Rammevisning: kun små dåser (25–33 cl), omregnet til 18 eller 24 stk. Pakker med præcis den
     * rammestørrelse markeres som "ægte ramme". Literpris: alle dåser op til 50 cl.
     * Hvert tilbud optræder kun én gang, med sin billigste pakning.
     */
    fun rank(deals: List<Deal>, mode: Mode): List<Ranked> {
        val n = mode.pieces
        return deals
            .filter { mode == Mode.LITER || it.cl <= 33.5 }
            .map { Ranked(it, if (mode == Mode.LITER) it.perLiter else it.perCan * n, it.pieces == n) }
            .groupBy { it.deal.offerId }
            .map { (_, list) -> list.minBy { it.value } }
            .sortedWith(compareBy<Ranked> { it.value }.thenByDescending { it.exactFrame })
    }
}

/** Gør et rå tilbud fra Tjek/eTilbudsavis om til de dåseøl-pakninger, det indeholder. */
object OfferParser {

    /** De største mærker, man kan vælge imellem i appen. */
    val BRANDS = listOf(
        "Carlsberg", "Tuborg", "Royal", "Heineken", "Harboe", "Albani",
        "Thor", "Faxe", "Slots", "Ceres", "Hancock", "Schiøtz",
    )
    val DEFAULT_BRANDS = setOf("Carlsberg", "Tuborg", "Royal", "Heineken", "Harboe")

    // "18 x 33 cl", "15x33 cl", "15-18 x 33 cl" (laveste antal bruges, så prisen aldrig ser for god ud).
    private val PACK = Regex("""(?:(\d{1,2})\s*-\s*)?(\d{1,2})\s*[xX×]\s*(\d{1,3}(?:[,.]\d+)?)\s*cl""")
    private val BOTTLE = Regex("""(?<!\p{L})fl\.|flask|embl\.|(?<!\p{L})glas(?!\p{L})|fustage|fadøl|(?<!\p{L})kasse""", RegexOption.IGNORE_CASE)
    private val CAN = Regex("""dåse|(?<!\p{L})ds\.?(?!\p{L})""", RegexOption.IGNORE_CASE)
    private val NOT_BEER = Regex("""sodavand|cola|monster|somersby|breezer|cider|(?<!\p{L})vand(?!\p{L})""", RegexOption.IGNORE_CASE)
    private val ALCOHOL_FREE = Regex("""alkoholfri""", RegexOption.IGNORE_CASE)

    private val brandPatterns = BRANDS.associateWith {
        Regex("(?<!\\p{L})" + Regex.escape(it) + "(?!\\p{L})", RegexOption.IGNORE_CASE)
    }

    fun brandsIn(text: String, among: Collection<String> = BRANDS): List<String> =
        among.filter { brandPatterns[it]?.containsMatchIn(text) == true }

    private val dateFormat = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US) }
    fun parseDate(s: String?): Long = try { dateFormat.get()!!.parse(s ?: "")?.time ?: 0L } catch (e: Exception) { 0L }

    private class Pack(val pieces: Int, val cl: Double, val segment: String)

    fun parse(o: JSONObject, selected: Collection<String>): List<Deal> {
        val heading = o.optString("heading")
        val desc = o.optString("description").takeUnless { o.isNull("description") } ?: ""
        val price = o.optJSONObject("pricing")?.optDouble("price") ?: return emptyList()
        if (price.isNaN() || price <= 0) return emptyList()
        if (ALCOHOL_FREE.containsMatchIn(heading)) return emptyList()

        val packs = packsFromText(desc).ifEmpty { packsFromText(heading) }.ifEmpty { listOfNotNull(packFromQuantity(o)) }
        // Står mærket ikke ved nogen af pakningerne, gælder overskriftens mærker for dem alle.
        val anyBrandInSegments = packs.any { brandsIn(it.segment).isNotEmpty() }
        val bottleInDesc = BOTTLE.containsMatchIn(desc) && !CAN.containsMatchIn(desc)

        val dealer = o.optJSONObject("dealer")?.optString("name") ?: "?"
        val id = o.optString("id")
        val from = parseDate(o.optString("run_from"))
        val till = parseDate(o.optString("run_till"))

        return packs.mapNotNull { p ->
            val brandText = if (anyBrandInSegments) p.segment else "$heading ${p.segment}"
            if (anyBrandInSegments && NOT_BEER.containsMatchIn(p.segment)) return@mapNotNull null
            val brands = brandsIn(brandText, selected)
            if (brands.isEmpty()) return@mapNotNull null
            if (p.cl < 25 || p.cl > 50) return@mapNotNull null
            val can = CAN.containsMatchIn(p.segment) || CAN.containsMatchIn(heading)
            val bottle = BOTTLE.containsMatchIn(p.segment) || BOTTLE.containsMatchIn(heading) ||
                bottleInDesc || (p.pieces == 30 && !can)
            if (bottle && !(can && CAN.containsMatchIn(p.segment))) return@mapNotNull null
            Deal(id, dealer, heading, brands, price, p.pieces, p.cl, can, from, till)
        }
    }

    private fun packsFromText(text: String): List<Pack> {
        val matches = PACK.findAll(text).toList()
        if (matches.isEmpty()) return emptyList()
        // To skrivemåder: "Carlsberg 18 x 33 cl, Tuborg 15 x 33 cl" (mærke før) og
        // "15x33 cl. ds. Tuborg Classic. 18x33 cl. ds. Carlsberg" (mærke efter).
        val brandBefore = brandsIn(text.substring(0, matches.first().range.first)).isNotEmpty()
        return matches.mapIndexed { i, m ->
            val segment = if (brandBefore) {
                text.substring(if (i == 0) 0 else matches[i - 1].range.last + 1, m.range.first)
            } else {
                text.substring(m.range.last + 1, if (i + 1 < matches.size) matches[i + 1].range.first else text.length)
            }
            val low = m.groupValues[1].toIntOrNull()
            val pieces = low ?: m.groupValues[2].toInt()
            Pack(pieces, m.groupValues[3].replace(',', '.').toDouble(), segment)
        }.filter { it.pieces > 0 }
    }

    private fun packFromQuantity(o: JSONObject): Pack? {
        val q = o.optJSONObject("quantity") ?: return null
        val unit = q.optJSONObject("unit")?.optString("symbol") ?: return null
        val size = q.optJSONObject("size")?.optDouble("from") ?: return null
        val cl = when (unit) { "cl" -> size; "l" -> size * 100; "ml" -> size / 10; else -> return null }
        val pieces = q.optJSONObject("pieces")?.optInt("from", 1)?.coerceAtLeast(1) ?: 1
        return Pack(pieces, cl, "")
    }
}
