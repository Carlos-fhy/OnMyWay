package com.onmyway.app.feature.settings

import com.onmyway.app.data.sync.BackupCredential
import org.junit.Assert.*
import org.junit.Test

class SettingsConnectionTest {
    private val server = "http://127.0.0.1:8000"
    private val existing = BackupCredential(server, "synthetic-saved-token-".padEnd(32, 'x'))

    @Test fun unsavedAddressNeverReceivesOrReusesTheCredential() {
        assertThrows(IllegalArgumentException::class.java) {
            resolveSettingsCredential("http://other.example:8000", server, "synthetic-new-token-".padEnd(32, 'y'), existing)
        }
    }

    @Test fun nonemptyShortReplacementCannotSilentlyUseTheOldCredential() {
        assertThrows(IllegalArgumentException::class.java) {
            resolveSettingsCredential(server, server, "incomplete", existing)
        }
    }

    @Test fun blankInputUsesOnlyCredentialForTheSavedService() {
        assertEquals(existing, resolveSettingsCredential(" $server/ ", server, "", existing))
        assertThrows(IllegalArgumentException::class.java) {
            resolveSettingsCredential(server, server, "", existing.copy(server = "http://other.example:8000"))
        }
    }

    @Test fun explicitReplacementIsBoundToTheSavedDestination() {
        val token = "synthetic-replacement-token-".padEnd(32, 'z')
        assertEquals(BackupCredential(server, token), resolveSettingsCredential(server, server, token, existing))
    }
}
