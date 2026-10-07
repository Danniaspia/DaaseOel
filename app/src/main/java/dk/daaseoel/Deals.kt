package dk.daaseoel

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/** En konkret øl, man kan vælge i appen, og hvordan den genkendes i tilbudsteksten. */
class Beer(val name: String, val brand: String, pattern: String) {
    val regex = Regex("(?<!\\p{L})(?:$pattern)(?!\\p{L})", RegexOption.IGNORE_CASE)
}

object Beers {
    // "(?!…0,0|alkoholfri)" holder alkoholfri udgaver ude, f.eks. "Tuborg Classic 0,0".
    private const val NOT_AF = """(?!\s*(?:0[,.]0|alkoholfri))"""

    val ALL = listOf(
        Beer("Grøn Tuborg", "Tuborg", """grøn\s+tuborg$NOT_AF|tuborg\s+grøn$NOT_AF|tuborg\s+pilsner$NOT_AF"""),
        Beer("Tuborg Classic", "Tuborg", """tuborg\s+classic$NOT_AF"""),
        Beer("Tuborg Julebryg", "Tuborg", """tuborg\s+jule-?\s*bryg"""),
        Beer("Carlsberg Pilsner", "Carlsberg", """carlsberg\s+pils(?:ner)?$NOT_AF"""),
        Beer("Royal Pilsner", "Royal", """royal\s+pils(?:ner)?$NOT_AF"""),
        Beer("Royal Classic", "Royal", """royal\s+classic$NOT_AF"""),
        Beer("Royal Export", "Royal", """royal\s+export$NOT_AF"""),
        Beer("Heineken", "Heineken", """heineken(?!\s*(?:0[,.]0|alkoholfri|silver))"""),
        Beer("Harboe Pilsner", "Harboe", """harboe\s+pils(?:ner)?$NOT_AF"""),
        Beer("Harboe Classic", "Harboe", """harboe\s+classic$NOT_AF"""),
        Beer("Albani Pilsner", "Albani", """albani\s+(?:odense\s+)?pils(?:ner)?"""),
        Beer("Thor Pilsner", "Thor", """thor\s+pils(?:ner)?"""),
        Beer("Faxe Premium", "Faxe", """faxe\s+premium"""),
        Beer("Slots Pilsner", "Slots", """slots\s+pils(?:ner)?"""),
        Beer("Slots Classic", "Slots", """slots\s+classic"""),
        Beer("Ceres Top", "Ceres", """ceres\s+top"""),
    )
    val DEFAULT = setOf(
        "Grøn Tuborg", "Tuborg Classic", "Carlsberg Pilsner", "Royal Pilsner",
        "Royal Classic", "Royal Export", "Heineken", "Harboe Pilsner",
    )

    /** Mærker, hvor tilbuddet nogle gange kun skriver mærket ("Tuborg eller Carlsberg øl"). */
    val BRANDS = ALL.map { it.brand }.distinct()
    val brandRegex = BRANDS.associateWith { Regex("(?<!\\p{L})$it(?!\\p{L})", RegexOption.IGNORE_CASE) }

    fun brandsOf(selected: Collection<String>) = ALL.filter { it.name in selected }.map { it.brand }.toSet()
}

/** Én ramme (18 eller 24 dåser) fra ét tilbud. */
data class Deal(
    val offerId: String,
    val dealer: String,
    val heading: String,
    /** Konkrete øl, tilbuddet nævner, f.eks. "Grøn Tuborg". */
    val beers: List<String>,
    /** Mærker nævnt uden sort, f.eks. "Tuborg eller Carlsberg øl". */
    val unspecified: List<String>,
    val price: Double,
    val pieces: Int,
    val cl: Double,
    val runFrom: Long,
    val runTill: Long,
    val link: String,
) {
    val perCan get() = price / pieces
    val perLiter get() = perCan / (cl / 100.0)

    /** Kun de øl, brugeren har valgt – eller null, hvis ingen af dem er med. */
    fun forSelection(selected: Set<String>): Deal? {
        val b = beers.filter { it in selected }
        val brands = Beers.brandsOf(selected)
        val u = unspecified.filter { it in brands }
        return if (b.isEmpty() && u.isEmpty()) null else copy(beers = b, unspecified = u)
    }

    /** "Grøn Tuborg eller Carlsberg Pilsner" / "Heineken eller Royal (sort ikke angivet)". */
    val beerText: String
        get() {
            val parts = beers + if (unspecified.isEmpty()) emptyList() else listOf(
                unspecified.joinToString(" eller ") + " (sort ikke angivet)",
            )
            return parts.joinToString(" eller ")
        }

    fun toJson(): JSONObject = JSONObject()
        .put("id", offerId).put("dealer", dealer).put("heading", heading)
        .put("beers", JSONArray(beers)).put("unspecified", JSONArray(unspecified))
        .put("price", price).put("pieces", pieces).put("cl", cl)
        .put("from", runFrom).put("till", runTill).put("link", link)

    companion object {
        private fun JSONArray.strings() = List(length()) { getString(it) }

        fun fromJson(o: JSONObject) = Deal(
            o.getString("id"), o.getString("dealer"), o.getString("heading"),
            o.getJSONArray("beers").strings(), o.getJSONArray("unspecified").strings(),
            o.getDouble("price"), o.getInt("pieces"), o.getDouble("cl"),
            o.getLong("from"), o.getLong("till"), o.getString("link"),
        )
    }
}

object Ranking {
    /** Billigste først (literpris eller pris pr. dåse); hvert tilbud kun én gang (med sin billigste ramme). */
    fun rank(deals: List<Deal>, perCan: Boolean = false): List<Deal> {
        val key: (Deal) -> Double = if (perCan) { d -> d.perCan } else { d -> d.perLiter }
        return deals
            .groupBy { it.offerId }
            .map { (_, list) -> list.minBy(key) }
            .sortedWith(compareBy<Deal> { key(it) }.thenByDescending { it.pieces })
    }
}

/** Gør et rå tilbud fra Tjek/eTilbudsavis om til de rammer dåseøl, det indeholder. */
object OfferParser {

    val FRAME_SIZES = setOf(18, 24)

    // "18 x 33 cl", "15x33 cl", "15-18 x 33 cl" (laveste antal bruges, så prisen aldrig ser for god ud).
    private val PACK = Regex("""(?:(\d{1,2})\s*-\s*)?(\d{1,2})\s*[xX×]\s*(\d{1,3}(?:[,.]\d+)?)\s*cl""")
    private val BOTTLE = Regex("""(?<!\p{L})fl\.|flask|embl\.|(?<!\p{L})glas(?!\p{L})|fustage|fadøl|(?<!\p{L})kasse""", RegexOption.IGNORE_CASE)
    private val CAN = Regex("""dåse|(?<!\p{L})ds\.?(?!\p{L})""", RegexOption.IGNORE_CASE)
    private val NOT_BEER = Regex(
        """sodavand|cola|pepsi|fanta|sprite|monster|red\s*bull|energy|kondi|squash|juice|somersby|breezer|cider|(?<!\p{L})vand(?!\p{L})|(?<!\p{L})vin(?!\p{L})""",
        RegexOption.IGNORE_CASE,
    )
    private val ALCOHOL_FREE = Regex("""alkoholfri|(?<!\d)0[,.]0\s*%""", RegexOption.IGNORE_CASE)
    // Et mærke står "alene", når det følges af f.eks. "eller", "øl", komma eller tekstens slutning.
    private val BARE_BRAND_FOLLOW = Regex("""^\s*(?:$|[,/.&+()\-]|eller(?!\p{L})|og(?!\p{L})|øl(?!\p{L})|dåse|ds(?!\p{L})|\d)""", RegexOption.IGNORE_CASE)

    private val dateFormat = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US) }
    fun parseDate(s: String?): Long = try { dateFormat.get()!!.parse(s ?: "")?.time ?: 0L } catch (e: Exception) { 0L }

    fun hasBrand(text: String) = Beers.brandRegex.values.any { it.containsMatchIn(text) }

    /** Konkrete øl og "nøgne" mærker i en tekst. */
    fun beersIn(text: String): Pair<List<String>, List<String>> {
        val hits = Beers.ALL.flatMap { b -> b.regex.findAll(text).map { b to it.range } }
        val beers = hits.map { it.first.name }.distinct()
        val covered = hits.map { it.second }
        val bare = Beers.brandRegex.filter { (_, re) ->
            re.findAll(text).any { m ->
                covered.none { m.range.first in it } && BARE_BRAND_FOLLOW.containsMatchIn(text.substring(m.range.last + 1))
            }
        }.keys.filter { brand -> Beers.ALL.none { it.name == brand } }
        return beers to bare
    }

    fun link(o: JSONObject): String {
        val slug = o.optJSONObject("dealer")?.optJSONArray("markets")?.optJSONObject(0)?.optString("slug")
        val catalog = o.optString("catalog_id").takeUnless { o.isNull("catalog_id") || it.isEmpty() }
        val id = o.optString("id")
        return if (!slug.isNullOrEmpty()) {
            "https://etilbudsavis.dk/$slug?publication=${catalog ?: "inventory"}&offer=$id"
        } else {
            "https://etilbudsavis.dk/soeg/" + java.net.URLEncoder.encode(o.optString("heading"), "UTF-8").replace("+", "%20")
        }
    }

    private class Pack(val pieces: Int, val cl: Double, val segment: String)

    fun parse(o: JSONObject): List<Deal> {
        val heading = o.optString("heading")
        val desc = o.optString("description").takeUnless { o.isNull("description") } ?: ""
        val price = o.optJSONObject("pricing")?.optDouble("price") ?: return emptyList()
        if (price.isNaN() || price <= 0) return emptyList()
        if (ALCOHOL_FREE.containsMatchIn(heading) || NOT_BEER.containsMatchIn(heading)) return emptyList()

        val packs = packsFromText(desc).ifEmpty { packsFromText(heading) }.ifEmpty { listOfNotNull(packFromQuantity(o)) }
        // Står mærket ikke ved nogen af pakningerne, gælder overskriftens øl for dem alle.
        val anyBrandInSegments = packs.any { hasBrand(it.segment) }
        val bottleInDesc = BOTTLE.containsMatchIn(desc) && !CAN.containsMatchIn(desc)

        val dealer = o.optJSONObject("dealer")?.optString("name") ?: "?"
        val id = o.optString("id")
        val from = parseDate(o.optString("run_from"))
        val till = parseDate(o.optString("run_till"))
        val link = link(o)

        return packs.mapNotNull { p ->
            if (p.pieces !in FRAME_SIZES || p.cl < 30 || p.cl > 33.5) return@mapNotNull null
            if (NOT_BEER.containsMatchIn(p.segment)) return@mapNotNull null
            val (beers, bare) = beersIn(if (anyBrandInSegments) p.segment else "$heading ${p.segment}")
            if (beers.isEmpty() && bare.isEmpty()) return@mapNotNull null
            val canInSegment = CAN.containsMatchIn(p.segment)
            val bottle = BOTTLE.containsMatchIn(p.segment) || BOTTLE.containsMatchIn(heading) || bottleInDesc
            if (bottle && !canInSegment) return@mapNotNull null
            Deal(id, dealer, heading, beers, bare, price, p.pieces, p.cl, from, till, link)
        }
    }

    private fun packsFromText(text: String): List<Pack> {
        val matches = PACK.findAll(text).toList()
        if (matches.isEmpty()) return emptyList()
        // To skrivemåder: "Carlsberg 18 x 33 cl, Tuborg 15 x 33 cl" (mærke før) og
        // "15x33 cl. ds. Tuborg Classic. 18x33 cl. ds. Carlsberg" (mærke efter).
        val brandBefore = hasBrand(text.substring(0, matches.first().range.first))
        return matches.mapIndexed { i, m ->
            val segment = if (brandBefore) {
                text.substring(if (i == 0) 0 else matches[i - 1].range.last + 1, m.range.first)
            } else {
                text.substring(m.range.last + 1, if (i + 1 < matches.size) matches[i + 1].range.first else text.length)
            }
            val pieces = m.groupValues[1].toIntOrNull() ?: m.groupValues[2].toInt()
            Pack(pieces, m.groupValues[3].replace(',', '.').toDouble(), segment)
        }
    }

    private fun packFromQuantity(o: JSONObject): Pack? {
        val q = o.optJSONObject("quantity") ?: return null
        val unit = q.optJSONObject("unit")?.optString("symbol") ?: return null
        val size = q.optJSONObject("size")?.optDouble("from") ?: return null
        val cl = when (unit) { "cl" -> size; "l" -> size * 100; "ml" -> size / 10; else -> return null }
        val pcs = q.optJSONObject("pieces") ?: return null
        // Kun når antallet er entydigt – "12-18 stk" siger ikke, hvad prisen gælder for.
        val pieces = pcs.optInt("from", 1)
        if (pcs.optInt("to", pieces) != pieces) return null
        return Pack(pieces, cl, "")
    }
}
