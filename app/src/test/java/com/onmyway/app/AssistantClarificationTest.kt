package com.onmyway.app

import org.junit.Assert.*
import org.junit.Test

class AssistantClarificationTest {
    private val library = Place("library", "校园图书馆", 120.0, 31.0, "校园内")
    private val shop = Place("shop", "校园超市", 120.1, 31.1, "生活区")
    private val gate = Place("gate", "校园东门", 120.2, 31.2, "东侧")
    private val choices = listOf(library, shop, gate)

    @Test fun explicitChineseAndArabicOrdinalsSelectTheirDisplayedPosition() {
        assertEquals(library, resolvePlaceReply("第一个", choices))
        assertEquals(shop, resolvePlaceReply("第2个", choices))
        assertEquals(gate, resolvePlaceReply("选第三个", choices))
        assertEquals(shop, resolvePlaceReply("2", choices))
        assertEquals(shop, resolvePlaceReply("选择 第二 个。", choices))
    }

    @Test fun exactFullNameSelectsButPartialOrNewDestinationDoesNot() {
        assertEquals(library, resolvePlaceReply(" 校园图书馆。 ", choices))
        assertNull(resolvePlaceReply("图书馆", choices))
        assertNull(resolvePlaceReply("换成市图书馆", choices))
        assertNull(resolvePlaceReply("我不去校园图书馆", choices))
    }

    @Test fun duplicateNamesRequireAnOrdinalEvenWhenFirstResultLooksPlausible() {
        val otherShop = shop.copy(id = "other-shop", longitude = 120.6)
        val alternatives = listOf(shop, otherShop)
        assertNull(resolvePlaceReply(shop.name, alternatives))
        assertEquals(otherShop, resolvePlaceReply("第二个", alternatives))
    }

    @Test fun missingOutOfBoundsAndOverflowAnswersNeverFallback() {
        listOf("", " ", "0", "第0个", "4", "-1", "1.5", "99999999999999999999").forEach {
            assertNull("Unexpected selection for $it", resolvePlaceReply(it, choices))
        }
        assertNull(resolvePlaceReply("第一个", emptyList()))
    }

    @Test fun ambiguousAndNegatedRepliesDoNotChooseForTheUser() {
        listOf("随便", "最近的", "都可以", "都去", "两个都行", "不要第二个", "第一个还是第二个", "选两个").forEach {
            assertNull("Unexpected selection for $it", resolvePlaceReply(it, choices))
        }
    }

    @Test fun ChineseTensFollowOrdinalsAndRejectMalformedNumerals() {
        val many = (1..21).map { Place("p$it", "地点$it", 120.0, 31.0) }
        assertEquals(many[9], resolvePlaceReply("第十个", many))
        assertEquals(many[10], resolvePlaceReply("第十一个", many))
        assertEquals(many[19], resolvePlaceReply("第二十个", many))
        assertEquals(many[20], resolvePlaceReply("选择第二十一个", many))
        assertNull(resolvePlaceReply("第二十二个", many))
        assertNull(resolvePlaceReply("第十十个", many))
        assertNull(resolvePlaceReply("第一二个", many))
    }

    @Test fun NumericNameConflictingWithOrdinalRemainsAmbiguous() {
        val numeric = library.copy(name = "2")
        assertNull(resolvePlaceReply("2", listOf(numeric, shop)))
        assertEquals(shop, resolvePlaceReply("第二个", listOf(numeric, shop)))
    }

    @Test fun persistedChoiceRetainsPlaceIdentityCoordinatesAndOrder() {
        val saved = shop.toClarificationChoice("draft-id", 2)
        assertEquals(shop, saved.toPlace())
        assertEquals("draft-id", saved.draftId)
        assertEquals(2, saved.position)
    }
}
