package dk.daaseoel

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tekster taget fra rigtige tilbud i eTilbudsavis (oktober 2026). */
class OfferParserTest {

    private val brands = OfferParser.DEFAULT_BRANDS

    private fun offer(
        heading: String, desc: String, price: Double,
        unit: String = "cl", size: Double = 33.0, pieces: Int = 1, dealer: String = "Test",
    ) = JSONObject(
        """{"id":"${heading.hashCode()}","heading":${JSONObject.quote(heading)},"description":${JSONObject.quote(desc)},
           "pricing":{"price":$price},"dealer":{"name":"$dealer"},
           "quantity":{"unit":{"symbol":"$unit"},"size":{"from":$size,"to":$size},"pieces":{"from":$pieces,"to":$pieces}},
           "run_from":"2026-10-08T22:00:00+0000","run_till":"2026-10-15T21:59:59+0000"}""",
    )

    @Test fun mixedFramesPickTheRightSizePerBrand() {
        val deals = OfferParser.parse(offer(
            "TUBORG ELLER CARLSBERG",
            "Carlsberg Pilsner, Carlsberg Nordlyst eller Grøn Tuborg 18 x 33 cl, Tuborg Classic 15 x 33 cl, " +
                "Grimbergen Double Ambrée 12 x 25 cl eller 1664 12 x 33 cl. Max. literpris 23,32.",
            69.95, pieces = 12,
        ), brands)
        assertEquals(listOf(18, 15), deals.map { it.pieces })
        assertEquals(listOf("Carlsberg", "Tuborg"), deals[0].brands.sorted())
        val best = Ranking.rank(deals, Mode.R18).first()
        assertEquals(69.95, best.value, 0.01)
        assertTrue(best.exactFrame)
    }

    @Test fun brandOnlyInHeading() {
        val deals = OfferParser.parse(offer(
            "Heineken eller Royal",
            "PRISEN GÆLDER KUN VED KØB AF HELE FORPAKNINGER 18 x 33 cl. + pant. Pr. liter 11,62 Max 6 rammer",
            69.0, pieces = 18,
        ), brands)
        assertEquals(1, deals.size)
        assertEquals(listOf("Royal", "Heineken"), deals[0].brands)
        assertEquals(11.62, deals[0].perLiter, 0.01)
    }

    @Test fun brandAfterPackAndCansMarked() {
        val deals = OfferParser.parse(offer(
            "Ølmarked",
            "15x33 cl. ds. Tuborg Classic, Classic alkoholfri eller Carlsberg IPA. 18x33 cl. ds. Carlsberg Pilsner eller Tuborg Grøn.",
            90.0, pieces = 15,
        ), brands)
        assertEquals(listOf(15, 18), deals.map { it.pieces })
        assertTrue(deals.all { it.canConfirmed })
        assertEquals(90.0 / 18 * 24, Ranking.rank(deals, Mode.R24).first().value, 0.01)
    }

    @Test fun bottlesAreSkipped() {
        assertTrue(OfferParser.parse(offer(
            "Carlsberg, Grøn Tuborg eller Tuborg Classic flasker", "30x33 cl. fl. Ekskl. embl. Pr. liter 12,12", 120.0, pieces = 30,
        ), brands).isEmpty())
        assertTrue(OfferParser.parse(offer(
            "Royal", "Flere varianter. 75 cl. PR. FLASKE + pant", 20.0, size = 75.0,
        ), brands).isEmpty())
    }

    @Test fun singleCanFromQuantity() {
        val deals = OfferParser.parse(offer(
            "Tuborg eller Carlsberg øl", "Ved køb af flere end 72 stk. pr. variant pr. dag er prisen op til 7.25 pr. stk. 33 cl. Ex. pant",
            4.5,
        ), brands)
        assertEquals(1, deals.size)
        assertEquals(108.0, Ranking.rank(deals, Mode.R24).first().value, 0.01)
        assertEquals(13.64, Ranking.rank(deals, Mode.LITER).first().value, 0.01)
        assertTrue(!Ranking.rank(deals, Mode.R24).first().exactFrame)
    }

    @Test fun pieceRangeUsesLowestCount() {
        val deals = OfferParser.parse(offer(
            "TUBORG Øl", "Sælges kun i hele rammer 0,0-4,6 % alc. Ekskl. pant. 15-18 x 33 cl. Pr. l maks. 15,15", 75.0, pieces = 15,
        ), brands)
        assertEquals(15, deals.single().pieces)
    }

    @Test fun unselectedBrandsAndAlcoholFreeAreSkipped() {
        assertTrue(OfferParser.parse(offer("ALKOHOLFRI CARLSBERG ELLER TUBORG", "6 x 33 cl", 35.0, pieces = 6), brands).isEmpty())
        assertTrue(OfferParser.parse(offer("Slots Classic", "24 x 33 cl", 89.0, pieces = 24), brands).isEmpty())
        assertEquals(1, OfferParser.parse(offer("Slots Classic", "24 x 33 cl", 89.0, pieces = 24), setOf("Slots")).size)
    }

    @Test fun cheapestPackPerOfferOnly() {
        val deals = OfferParser.parse(offer(
            "Ølmarked", "15x33 cl. ds. Tuborg Classic. 18x33 cl. ds. Carlsberg Pilsner.", 90.0,
        ), brands)
        assertEquals(1, Ranking.rank(deals, Mode.LITER).size)
    }
}
