package com.onmyway.app.data.local

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.onmyway.app.core.memory.timeBand

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `saved_trip` (`id` INTEGER NOT NULL, `requestJson` TEXT NOT NULL, `resultJson` TEXT NOT NULL, `started` INTEGER NOT NULL, PRIMARY KEY(`id`))")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tasks ADD COLUMN completedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tasks ADD COLUMN lastRemindedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tasks ADD COLUMN reminderCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tasks ADD COLUMN reminderPending INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE tasks SET updatedAt = createdAt")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE saved_trip ADD COLUMN sessionId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE saved_trip ADD COLUMN startedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE saved_trip ADD COLUMN plannedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE saved_trip SET sessionId = lower(hex(randomblob(16)))")
        db.execSQL("CREATE TABLE IF NOT EXISTS route_history (id TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, state TEXT NOT NULL, timestamp INTEGER NOT NULL, requestJson TEXT NOT NULL, resultJson TEXT NOT NULL)")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `suggestion_feedback` (`id` TEXT NOT NULL, `shownAt` INTEGER NOT NULL, `mode` TEXT NOT NULL, `offeredTaskCount` INTEGER NOT NULL, `offeredExtraSeconds` INTEGER NOT NULL, `decision` TEXT NOT NULL, `selectedTaskCount` INTEGER NOT NULL, `selectedExtraSeconds` INTEGER NOT NULL, `decidedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE suggestion_feedback ADD COLUMN timeBand TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE suggestion_feedback ADD COLUMN routeSessionId TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE TABLE IF NOT EXISTS agent_events (id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, occurredAt INTEGER NOT NULL, source TEXT NOT NULL, correlationId TEXT NOT NULL, routeSessionId TEXT NOT NULL, actionId TEXT NOT NULL, mode TEXT NOT NULL, timeBand TEXT NOT NULL, itemCount INTEGER NOT NULL, extraSeconds INTEGER NOT NULL)")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE saved_trip ADD COLUMN needsReplan INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS assistant_messages (id TEXT NOT NULL PRIMARY KEY, role TEXT NOT NULL, text TEXT NOT NULL, createdAt INTEGER NOT NULL, routeHistoryId TEXT, conversationId TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_assistant_messages_createdAt ON assistant_messages (createdAt)")
        db.execSQL("CREATE TABLE IF NOT EXISTS assistant_draft (id INTEGER NOT NULL PRIMARY KEY, action TEXT NOT NULL, title TEXT NOT NULL, placeType TEXT NOT NULL, deadline TEXT NOT NULL, destination TEXT NOT NULL, mode TEXT NOT NULL, knownPlaceId TEXT NOT NULL, knownPlaceName TEXT NOT NULL, knownLongitude REAL, knownLatitude REAL, knownAddress TEXT NOT NULL, savedAt INTEGER NOT NULL, expiresAt INTEGER NOT NULL, conversationId TEXT NOT NULL)")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS planning_draft (id INTEGER NOT NULL PRIMARY KEY, destination TEXT NOT NULL, mode TEXT NOT NULL, budget INTEGER NOT NULL, placeId TEXT NOT NULL, placeName TEXT NOT NULL, longitude REAL NOT NULL, latitude REAL NOT NULL, address TEXT NOT NULL, savedAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS planning_draft_tasks (id TEXT NOT NULL PRIMARY KEY, selected INTEGER NOT NULL)")
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE assistant_draft ADD COLUMN draftId TEXT NOT NULL DEFAULT 'legacy-draft'")
        db.execSQL("ALTER TABLE planning_draft ADD COLUMN draftId TEXT NOT NULL DEFAULT 'legacy-draft'")
    }
}

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf("saved_trip", "route_history", "assistant_draft", "planning_draft").forEach {
            db.execSQL("ALTER TABLE $it ADD COLUMN planId TEXT NOT NULL DEFAULT ''")
        }
        db.execSQL("UPDATE saved_trip SET planId = sessionId")
        db.execSQL("UPDATE assistant_draft SET planId = draftId")
        db.execSQL("UPDATE planning_draft SET planId = draftId")
        db.execSQL("CREATE TABLE IF NOT EXISTS assistant_clarification (id INTEGER NOT NULL PRIMARY KEY, draftId TEXT NOT NULL, planId TEXT NOT NULL, kind TEXT NOT NULL, prompt TEXT NOT NULL, destination TEXT NOT NULL, mode TEXT NOT NULL, savedAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS assistant_place_choices (id TEXT NOT NULL PRIMARY KEY, draftId TEXT NOT NULL, name TEXT NOT NULL, longitude REAL NOT NULL, latitude REAL NOT NULL, address TEXT NOT NULL, position INTEGER NOT NULL)")
    }
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // A planned day is independent of a deadline; never infer it for existing tasks.
        db.execSQL("ALTER TABLE tasks ADD COLUMN plannedDate TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE TABLE IF NOT EXISTS assistant_task_drafts (id TEXT NOT NULL PRIMARY KEY, planId TEXT NOT NULL, position INTEGER NOT NULL, version INTEGER NOT NULL, title TEXT NOT NULL, placeType TEXT NOT NULL, deadline TEXT NOT NULL, plannedDate TEXT NOT NULL, priority INTEGER NOT NULL, serviceMinutes INTEGER NOT NULL, placeJson TEXT NOT NULL)")
    }
}

val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Existing planned dates and deadlines never invent an explicit reminder time.
        db.execSQL("ALTER TABLE tasks ADD COLUMN reminderAt INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Assistant drafts can carry an explicit reminder proposed by the model.
        db.execSQL("ALTER TABLE assistant_draft ADD COLUMN reminderAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE assistant_task_drafts ADD COLUMN reminderAt INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Local-only outbound events. Old actions did not produce business events,
        // so the table starts empty rather than inventing historical observations.
        db.execSQL("CREATE TABLE IF NOT EXISTS `business_event_outbox` (`eventId` TEXT NOT NULL, `correlationId` TEXT NOT NULL, `kind` TEXT NOT NULL, `source` TEXT NOT NULL, `schemaVersion` INTEGER NOT NULL, `occurredAt` INTEGER NOT NULL, `validUntil` INTEGER, `evidenceJson` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `deliveredAt` INTEGER, `terminalAt` INTEGER, `attempts` INTEGER NOT NULL, `lastAttemptAt` INTEGER NOT NULL, `lastError` TEXT NOT NULL, PRIMARY KEY(`eventId`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_business_event_outbox_deliveredAt_terminalAt_occurredAt` ON `business_event_outbox` (`deliveredAt`, `terminalAt`, `occurredAt`)")
    }
}
