package com.onmyway.app

import org.junit.Assert.assertEquals
import org.junit.Test

class FamiliarPlaceAliasTest {
    @Test fun resolvesPersonalAliases() {
        assertEquals("宿舍", familiarPlaceAlias(" 我的宿舍 "))
        assertEquals("家", familiarPlaceAlias("我家"))
    }
    @Test fun keepsSpecificDestinationsDistinct() {
        assertEquals("宿舍楼北门", familiarPlaceAlias("宿舍楼北门"))
        assertEquals("朋友家", familiarPlaceAlias("朋友家"))
    }
}
