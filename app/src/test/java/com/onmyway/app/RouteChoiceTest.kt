package com.onmyway.app

import org.junit.Assert.*
import org.junit.Test

class RouteChoiceTest {
    private val tasks = listOf(Errand(id = "parcel", title = "取件", placeType = "PARCEL"), Errand(id = "print", title = "论文", placeType = "PRINT_SHOP"))
    @Test fun allSelectsEverySelectableTaskRatherThanOnlyRecommendations() {
        assertEquals(setOf("print", "parcel"), selectRouteTasks("都去", tasks, setOf("print")))
    }
    @Test fun directRemovesAllStops() {
        assertEquals(emptySet<String>(), selectRouteTasks("直接走", tasks, setOf("print", "parcel")))
    }
    @Test fun explicitSelectionUsesActualTaskIds() {
        assertEquals(setOf("parcel"), selectRouteTasks("只取快递", tasks, setOf("print")))
        assertEquals(setOf("print"), selectRouteTasks("不取快递了", tasks, setOf("print", "parcel")))
    }
    @Test fun unknownAndAmbiguousExpressionsDoNotConfirm() {
        assertNull(selectRouteTasks("不是都去", tasks, setOf("print")))
        assertNull(selectRouteTasks("只去药店", tasks, setOf("print")))
        assertNull(selectRouteTasks("如果都去呢", tasks, setOf("print")))
    }
}
