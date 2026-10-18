package com.onmyway.app.core.location

import org.junit.Assert.assertEquals
import org.junit.Test

class FamiliarPlaceAliasTest {
    @Test fun resolvesPersonalAliases() {
        assertEquals("宿舍", familiarPlaceAlias(" 我的宿舍 "))
        assertEquals("家", familiarPlaceAlias("我家"))
        assertEquals("快递站", familiarPlaceAlias("我的快递站"))
        assertEquals("外卖点", familiarPlaceAlias("我的外卖点"))
    }
    @Test fun keepsSpecificDestinationsDistinct() {
        assertEquals("宿舍楼北门", familiarPlaceAlias("宿舍楼北门"))
        assertEquals("朋友家", familiarPlaceAlias("朋友家"))
        assertEquals("东门快递站", familiarPlaceAlias(" 东门快递站 "))
    }
}
