package com.onmyway.app

import org.junit.Assert.*
import org.junit.Test

class DestinationInputTest {
    @Test fun extractsExplicitDestinationsAndKeepsBareNames() {
        assertEquals("图书馆", destinationQuery("导航去图书馆。"))
        assertEquals("宿舍", destinationQuery("我现在准备回宿舍"))
        assertEquals("家", destinationQuery("我要回家"))
        assertEquals("星光酒吧", destinationQuery("星光酒吧"))
    }
    @Test fun refusesNegationFutureTasksAndEmptyDestinations() {
        listOf("", "去", "别去图书馆", "我不回宿舍", "提醒我明天去打印店", "取消导航", "去药店然后回家").forEach { assertNull(destinationQuery(it)) }
    }
    @Test fun parsesOnlyValidRouteCoordinates() {
        assertEquals(listOf(31.0 to 121.0), routePoints("121,31;bad;NaN,30;120,100"))
    }
}
