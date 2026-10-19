package com.onmyway.app.core.location

import com.onmyway.app.core.model.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FamiliarPlaceResolverTest {
    private val origin = Place("origin", "当前位置", 120.0, 31.0)

    private fun place(id: String, name: String, northMeters: Double = 0.0, eastMeters: Double = 0.0) =
        Place(id, name, 120.0 + eastMeters / (111_320.0 * Math.cos(Math.toRadians(31.0))),
            31.0 + northMeters / 110_540.0)

    @Test fun taggedCandidatesUseNearestAndIgnoreKeywordFallback() {
        val nearTagged = place("near", "东门驿站", northMeters = 200.0)
        val farTagged = place("far", "西门驿站", northMeters = 5_000.0)
        val untagged = place("untagged", "快递代收点", northMeters = 50.0)
        val places = mapOf("近处" to nearTagged, "远处" to farTagged, "未标注" to untagged)
        val purposes = mapOf("近处" to setOf("PARCEL"), "远处" to setOf("PARCEL"))

        val lookup = FamiliarPlaceResolver.resolve("PARCEL", origin, places, purposes)

        assertTrue(lookup is FamiliarPlaceLookup.Matched)
        assertEquals("near", (lookup as FamiliarPlaceLookup.Matched).match.place.id)
    }

    @Test fun tagCandidatesOutOfRangeDoNotFallBackToKeywordMatches() {
        val farTagged = place("far", "西门驿站", northMeters = 5_000.0)
        val nearUntagged = place("keyword", "快递代收点", northMeters = 50.0)
        val places = mapOf("远处" to farTagged, "关键词" to nearUntagged)

        val lookup = FamiliarPlaceResolver.resolve(
            "PARCEL", origin, places, mapOf("远处" to setOf("PARCEL")),
        )

        assertTrue(lookup is FamiliarPlaceLookup.OutOfRange)
        assertEquals("far", (lookup as FamiliarPlaceLookup.OutOfRange).nearest.place.id)
    }

    @Test fun untaggedPlacesFallBackToNameKeywords() {
        val places = mapOf("东门快递站" to place("parcel", "菜鸟驿站"))
        val lookup = FamiliarPlaceResolver.resolve("PARCEL", origin, places, emptyMap())

        assertTrue(lookup is FamiliarPlaceLookup.Matched)
        assertEquals("parcel", (lookup as FamiliarPlaceLookup.Matched).match.place.id)
    }

    @Test fun tagPrecedenceSkipsKeywordMatchesOnUnrelatedTypes() {
        val places = mapOf(
            "打印店" to place("print", "文印中心"),
            "快递站" to place("parcel", "菜鸟驿站"),
        )
        val purposes = mapOf("打印店" to setOf("PRINT_SHOP"))

        val lookup = FamiliarPlaceResolver.resolve("PRINT_SHOP", origin, places, purposes)

        assertTrue(lookup is FamiliarPlaceLookup.Matched)
        assertEquals("print", (lookup as FamiliarPlaceLookup.Matched).match.place.id)
    }

    @Test fun distanceBoundaryIsThreeKilometers() {
        val inside = place("inside", "近处", northMeters = 2_999.0)
        val outside = place("outside", "远处", northMeters = 3_001.0)
        val purposes = mapOf("近处" to setOf("PARCEL"), "远处" to setOf("PARCEL"))

        assertTrue(FamiliarPlaceResolver.resolve(
            "PARCEL", origin, mapOf("近处" to inside), purposes,
        ) is FamiliarPlaceLookup.Matched)
        assertTrue(FamiliarPlaceResolver.resolve(
            "PARCEL", origin, mapOf("远处" to outside), purposes,
        ) is FamiliarPlaceLookup.OutOfRange)
    }

    @Test fun missingOriginDoesNotChooseAnyPlace() {
        val lookup = FamiliarPlaceResolver.resolve(
            "PARCEL", null, mapOf("快递站" to place("parcel", "菜鸟驿站")), emptyMap(),
        )
        assertEquals(FamiliarPlaceLookup.NoOrigin, lookup)
    }

    @Test fun indoorAndUnknownTypesAreNotApplicable() {
        val places = mapOf("快递站" to place("parcel", "菜鸟驿站"))
        assertEquals(
            FamiliarPlaceLookup.NotApplicable,
            FamiliarPlaceResolver.resolve("INDOOR", origin, places, emptyMap()),
        )
        assertEquals(
            FamiliarPlaceLookup.NotApplicable,
            FamiliarPlaceResolver.resolve("OTHER", origin, places, emptyMap()),
        )
    }

    @Test fun invalidCoordinatesAreFilteredBeforeDistanceSorting() {
        val invalid = Place("broken", "坏点", Double.NaN, 31.0)
        val valid = place("valid", "菜鸟驿站", northMeters = 100.0)
        val lookup = FamiliarPlaceResolver.resolve(
            "PARCEL", origin, mapOf("坏点" to invalid, "快递站" to valid), emptyMap(),
        )

        assertTrue(lookup is FamiliarPlaceLookup.Matched)
        assertEquals("valid", (lookup as FamiliarPlaceLookup.Matched).match.place.id)
    }

    @Test fun equalDistancesSortByAlias() {
        val first = place("a", "驿站一", northMeters = 100.0)
        val second = place("b", "驿站二", northMeters = 100.0)
        val lookup = FamiliarPlaceResolver.resolve(
            "PARCEL", origin, linkedMapOf("b" to second, "a" to first), emptyMap(),
        )

        assertEquals("a", (lookup as FamiliarPlaceLookup.Matched).match.alias)
    }

    @Test fun distanceUsesMeterScale() {
        val distance = FamiliarPlaceResolver.distanceMeters(31.0, 120.0, 31.001, 120.0)
        assertTrue(distance in 110.0..111.0)
    }
}
