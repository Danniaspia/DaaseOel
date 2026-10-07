package dk.daaseoel

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tekster taget fra rigtige tilbud i eTilbudsavis (oktober 2026). */
class OfferParserTest {

    private fun offer(
        heading: String, desc: String, price: Double,
        unit: String = "cl", size: Double = 33.0, pieces: Int = 1, slug: String? = "Netto",
    ) = JSONObject(
        """{"id":"o${heading.hashCode()}","heading":${JSONObject.quote(heading)},"description":${JSONObject.quote(desc)},
           "pricing":{"price":$price},"catalog_id":"Cat1",
           "dealer":{"name":"Test"${if (slug != null) ""","markets":[{"slug":"$slug"}]""" else ""}},
           "quantity":{"unit":{"symbol":"$unit"},"size":{"from":$size,"to":$size},"pieces":{"from":$pieces,"to":$pieces}},
           "run_from":"2026-10-08T22:00:00+0000","run_till":"2026-10-15T21:59:59+0000"}""",
    )

    @Test fun mixedFramesKeepOnly18And24AndNameTheBeers() {
        val deals = OfferParser.parse(offer(
            "TUBORG ELLER CARLSBERG",
            "Carlsberg Pilsner, Carlsberg Nordlyst eller Grøn Tuborg 18 x 33 cl, Tuborg Classic 15 x 33 cl, " +
                "Grimbergen Double Ambrée 12 x 25 cl eller 1664 12 x 33 cl. Max. literpris 23,32.",
            69.95, pieces = 12,
        ))
        val d = deals.single()
        assertEquals(18, d.pieces)
        assertEquals(listOf("Grøn Tuborg", "Carlsberg Pilsner"), d.beers)
        assertTrue(d.unspecified.isEmpty())
        assertEquals(11.77, d.perLiter, 0.01)
    }

    @Test fun headingOnlyGivesUnspecifiedBrand() {
        val d = OfferParser.parse(offer(
            "Heineken eller Royal",
            "PRISEN GÆLDER KUN VED KØB AF HELE FORPAKNINGER 18 x 33 cl. + pant. Pr. liter 11,62 Max 6 rammer",
            69.0, pieces = 18,
        )).single()
        assertEquals(listOf("Heineken"), d.beers)
        assertEquals(listOf("Royal"), d.unspecified)
        assertEquals("Heineken eller Royal (sort ikke angivet)", d.beerText)
        assertEquals(11.62, d.perLiter, 0.01)
    }

    @Test fun brandAfterPackAndConcreteVariants() {
        val deals = OfferParser.parse(offer(
            "Ølmarked",
            "15x33 cl. ds. Tuborg Classic, Classic alkoholfri eller Carlsberg IPA. 18x33 cl. ds. Carlsberg Pilsner eller Tuborg Grøn.",
            90.0, pieces = 15,
        ))
        val d = deals.single()
        assertEquals(18, d.pieces)
        assertEquals(listOf("Grøn Tuborg", "Carlsberg Pilsner"), d.beers)
    }

    @Test fun colaInHeadingIsSkipped() {
        assertTrue(OfferParser.parse(offer(
            "Monster, Coca-Cola eller Carlsberg, Tuborg øl",
            "Monster, Coca-Cola eller Carlsberg, Tuborg øl 8 x 50 cl/24 x 33 cl./15-18 x 33 cl.", 79.0, pieces = 8,
        )).isEmpty())
    }

    @Test fun bottlesAndSinglesAndOtherSizesAreSkipped() {
        assertTrue(OfferParser.parse(offer(
            "Carlsberg, Grøn Tuborg eller Tuborg Classic flasker", "24x33 cl. fl. Ekskl. embl.", 120.0, pieces = 24,
        )).isEmpty())
        assertTrue(OfferParser.parse(offer("Tuborg eller Carlsberg øl", "33 cl. Ex. pant", 4.5)).isEmpty())
        assertTrue(OfferParser.parse(offer("ROYAL Øl", "4,6 % alc. Ekskl. pant. 20 x 33 cl.", 75.0, pieces = 20)).isEmpty())
        assertTrue(OfferParser.parse(offer("TUBORG Øl", "Ekskl. pant. 15-18 x 33 cl.", 75.0, pieces = 15)).isEmpty())
    }

    @Test fun alcoholFreeIsSkipped() {
        assertTrue(OfferParser.parse(offer("ALKOHOLFRI CARLSBERG ELLER TUBORG", "18 x 33 cl", 35.0, pieces = 18)).isEmpty())
        val d = OfferParser.parse(offer("Tuborg", "Tuborg Classic 0,0 eller Grøn Tuborg 18 x 33 cl", 80.0, pieces = 18)).single()
        assertEquals(listOf("Grøn Tuborg"), d.beers)
    }

    @Test fun selectionFiltersBeersAndUnspecifiedBrands() {
        val d = OfferParser.parse(offer("Heineken eller Royal", "18 x 33 cl", 69.0, pieces = 18)).single()
        assertNull(d.forSelection(setOf("Grøn Tuborg")))
        assertEquals("Royal (sort ikke angivet)", d.forSelection(setOf("Royal Export"))!!.beerText)
        assertEquals("Heineken", d.forSelection(setOf("Heineken"))!!.beerText)
    }

    @Test fun cheapestLiterPriceWinsBetween18And24() {
        val a = OfferParser.parse(offer("Slots Classic", "24 x 33 cl", 89.0, pieces = 24)).single()
        val b = OfferParser.parse(offer("Grøn Tuborg", "18 x 33 cl", 69.0, pieces = 18)).single()
        assertEquals(listOf(a, b), Ranking.rank(listOf(b, a)))
    }

    @Test fun perCanRankingUsesCanPrice() {
        val big = OfferParser.parse(offer("Grøn Tuborg", "24 x 33 cl", 96.0, pieces = 24)).single()   // 4,00 kr/dåse
        val small = OfferParser.parse(offer("Royal Export", "18 x 30 cl", 70.2, pieces = 18)).single() // 3,90 kr/dåse, men dyrere pr. liter
        assertEquals(listOf(small, big), Ranking.rank(listOf(big, small), perCan = true))
        assertEquals(listOf(big, small), Ranking.rank(listOf(big, small), perCan = false))
    }

    @Test fun linkPointsToOfferInEtilbudsavis() {
        val d = OfferParser.parse(offer("Grøn Tuborg", "18 x 33 cl", 69.0, pieces = 18, slug = "REMA-1000")).single()
        assertEquals("https://etilbudsavis.dk/REMA-1000?publication=Cat1&offer=${d.offerId}", d.link)
    }
}
