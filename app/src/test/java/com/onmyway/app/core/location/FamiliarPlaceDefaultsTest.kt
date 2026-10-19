package com.onmyway.app.core.location

import com.onmyway.app.core.model.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FamiliarPlaceDefaultsTest {
    private val parcel = Place("parcel", "菜鸟驿站", 120.0, 31.0)
    private val places = mapOf("快递站" to parcel)

    @Test fun resolvesSavedDefaultAlias() {
        assertEquals(parcel, defaultPlaceOrNull("快递站", places))
        assertNull(defaultPlaceOrNull(null, places))
        assertNull(defaultPlaceOrNull("", places))
        assertNull(defaultPlaceOrNull("不存在", places))
    }

    @Test fun removingTheDefaultClearsIt() {
        assertNull(defaultAliasAfterRemoval("快递站", "快递站"))
        assertEquals("家", defaultAliasAfterRemoval("家", "快递站"))
        assertNull(defaultAliasAfterRemoval(null, "快递站"))
    }

    @Test fun renamingTheDefaultMigratesIt() {
        assertEquals("东门驿站", defaultAliasAfterRename("快递站", "快递站", "东门驿站"))
        assertEquals("家", defaultAliasAfterRename("家", "快递站", "东门驿站"))
        assertNull(defaultAliasAfterRename(null, "快递站", "东门驿站"))
    }
}
