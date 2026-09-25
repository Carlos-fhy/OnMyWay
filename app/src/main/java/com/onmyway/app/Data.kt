package com.onmyway.app

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.UUID

data class Place(val id: String, val name: String, val longitude: Double, val latitude: Double, val address: String = "") {
    fun json() = JSONObject().put("id", id).put("name", name).put("longitude", longitude).put("latitude", latitude).put("address", address)
    companion object {
        fun from(j: JSONObject) = Place(j.getString("id"), j.getString("name"), j.getDouble("longitude"), j.getDouble("latitude"), j.optString("address"))
    }
}

@Entity(tableName = "tasks")
data class Errand(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val priority: Int = 2,
    val serviceMinutes: Int = 5,
    val deadline: String = "",
    val placeJson: String = "",
    val placeType: String = "SUPERMARKET",
    val status: String = "pending",
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = createdAt,
    @ColumnInfo(defaultValue = "0") val completedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val lastRemindedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val reminderCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val reminderPending: Boolean = false,
) {
    fun place(): Place? = if (placeJson.isBlank()) null else Place.from(JSONObject(placeJson))
    fun json(): JSONObject = JSONObject().put("id", id).put("title", title).put("priority", priority)
        .put("service_minutes", serviceMinutes).put("deadline", if (deadline.isBlank()) JSONObject.NULL else deadline)
        .put("place", place()?.json() ?: JSONObject.NULL)
        .put("place_type", if (placeJson.isBlank()) placeType else JSONObject.NULL)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC") fun observe(): Flow<List<Errand>>
    @Upsert suspend fun save(task: Errand)
    @Delete suspend fun delete(task: Errand)
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun get(id: String): Errand?
    @Query("SELECT * FROM tasks WHERE status = 'pending' AND (CASE WHEN lastRemindedAt > 0 THEN lastRemindedAt ELSE createdAt END) <= :cutoff")
    suspend fun dueForReminder(cutoff: Long): List<Errand>
    @Query("UPDATE tasks SET reminderPending = 0") suspend fun acknowledgeReminders()
}

@Entity(tableName = "saved_trip")
data class SavedTrip(@PrimaryKey val id: Int = 1, val requestJson: String, val resultJson: String, val started: Boolean = false,
    @ColumnInfo(defaultValue = "''") val sessionId: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "0") val startedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val plannedAt: Long = System.currentTimeMillis())

@Entity(tableName = "route_history")
data class RouteHistory(@PrimaryKey val id: String = UUID.randomUUID().toString(), val sessionId: String,
    val state: String, val timestamp: Long, val requestJson: String, val resultJson: String)

/**
 * Privacy-minimal trace of one route suggestion. No transcript, coordinates, or task titles.
 * PENDING and SUPERSEDED entries are excluded from acceptance statistics.
 */
@Entity(tableName = "suggestion_feedback")
data class SuggestionFeedback(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val shownAt: Long = System.currentTimeMillis(),
    val mode: String,
    val offeredTaskCount: Int,
    val offeredExtraSeconds: Int,
    val decision: String = "PENDING",
    val selectedTaskCount: Int = 0,
    val selectedExtraSeconds: Int = 0,
    val decidedAt: Long = 0,
)

@Dao
interface SuggestionFeedbackDao {
    @Insert suspend fun show(feedback: SuggestionFeedback)
    @Query("UPDATE suggestion_feedback SET decision = :decision, selectedTaskCount = :selectedTaskCount, selectedExtraSeconds = :selectedExtraSeconds, decidedAt = :decidedAt WHERE id = :id AND decision = 'PENDING'")
    suspend fun decide(id: String, decision: String, selectedTaskCount: Int, selectedExtraSeconds: Int, decidedAt: Long): Int
    @Query("SELECT * FROM suggestion_feedback WHERE decision != 'PENDING' AND decision != 'SUPERSEDED' ORDER BY decidedAt DESC LIMIT 50")
    suspend fun recentDecisions(): List<SuggestionFeedback>
}

@Dao
interface TripDao {
    @Query("SELECT * FROM saved_trip WHERE id = 1") suspend fun load(): SavedTrip?
    @Upsert suspend fun save(trip: SavedTrip)
    @Query("DELETE FROM saved_trip") suspend fun clear()
    @Insert suspend fun record(event: RouteHistory)
    @Query("SELECT * FROM route_history ORDER BY timestamp DESC, rowid DESC LIMIT 50") suspend fun history(): List<RouteHistory>
    @Query("SELECT * FROM route_history WHERE id = :id LIMIT 1") suspend fun historyById(id: String): RouteHistory?
}

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

@Database(entities = [Errand::class, SavedTrip::class, RouteHistory::class, SuggestionFeedback::class], version = 5, exportSchema = true)
abstract class TaskDatabase : RoomDatabase() {
    abstract fun tasks(): TaskDao
    abstract fun trips(): TripDao
    abstract fun suggestionFeedback(): SuggestionFeedbackDao
    companion object {
        @Volatile private var instance: TaskDatabase? = null
        fun get(context: Context): TaskDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, TaskDatabase::class.java, "onmyway.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build().also { instance = it }
        }
    }
}

val placeTypes = linkedMapOf("SUPERMARKET" to "超市", "PRINT_SHOP" to "打印店", "PHARMACY" to "药店", "PARCEL" to "取快递", "TAKEOUT" to "取外卖")
fun placeTypeIcon(type: String): Int = when (type) {
    "SUPERMARKET" -> R.drawable.ic_supermarket
    "PARCEL" -> R.drawable.ic_parcel
    "TAKEOUT" -> R.drawable.ic_takeout
    "PHARMACY" -> R.drawable.ic_pharmacy
    "PRINT_SHOP" -> R.drawable.ic_print
    else -> R.drawable.ic_tasks
}
fun requiresFixedPlace(type: String) = type == "PARCEL" || type == "TAKEOUT"
fun displayTimestamp(value: Long): String = java.time.Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(inputTime)
val priorities = mapOf(1 to "普通", 2 to "较重要", 3 to "重要")
val inputTime: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)
fun parseTime(value: String): String? = if (value.isBlank()) null else LocalDateTime.parse(value.trim(), inputTime)
    .atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
fun displayTime(value: String): String = java.time.OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).format(inputTime)

fun formatDuration(seconds: Int): String {
    val totalMinutes = kotlin.math.ceil(seconds / 60.0).toInt().coerceAtLeast(0)
    return if (totalMinutes >= 60) {
        val hours = totalMinutes / 60
        val rem = totalMinutes % 60
        if (rem == 0) "${hours} 小时" else "${hours} 小时 ${rem} 分钟"
    } else {
        "$totalMinutes 分钟"
    }
}

fun formatDistance(meters: Int): String {
    val safeMeters = meters.coerceAtLeast(0)
    return if (safeMeters >= 1000) {
        val km = safeMeters / 1000.0
        String.format(java.util.Locale.CHINA, "%.1f 公里", km)
    } else {
        "$safeMeters 米"
    }
}
