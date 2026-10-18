package com.onmyway.app.feature.navigation

import com.onmyway.app.data.local.entity.Errand
import org.junit.Assert.*
import org.junit.Test

class NavigationSelectionTest {
    @Test fun voiceChoosesOnlyExplicitOrdinals() {
        assertEquals(0, spokenPlaceIndex("第一个"))
        assertEquals(1, spokenPlaceIndex("选择第二个。"))
        assertEquals(9, spokenPlaceIndex("第10个"))
        assertNull(spokenPlaceIndex("不要第一个"))
        assertNull(spokenPlaceIndex("第一个还是第二个"))
    }
    private val tasks = listOf(Errand(id = "p", title = "取快递", placeType = "PARCEL"),
        Errand(id = "d", title = "打印", placeType = "PRINT_SHOP"))
    @Test fun removesOnlyRequestedStop() {
        assertEquals(setOf("d"), navigationSelection("不取快递了", tasks))
        assertEquals(setOf("p"), navigationSelection("不打印了", tasks))
    }
    @Test fun directClearsStops() { assertEquals(emptySet<String>(), navigationSelection("直接去目的地", tasks)) }
    @Test fun unsupportedRequestsNeverRemoveStops() {
        assertNull(navigationSelection("附近买水", tasks))
        assertNull(navigationSelection("不是不取快递了", tasks))
        assertNull(navigationSelection("都去", tasks))
    }
}
