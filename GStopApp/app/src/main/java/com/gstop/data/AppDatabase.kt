package com.gstop.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SettingsEntity::class,
        SleepWindowEntity::class,
        ScheduledStopEntity::class,
        HistoryEventEntity::class,
        ObservationEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun settingsDao(): SettingsDao
    abstract fun sleepWindowDao(): SleepWindowDao
    abstract fun scheduledStopDao(): ScheduledStopDao
    abstract fun historyDao(): HistoryDao
    abstract fun observationDao(): ObservationDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /**
         * Observations arrive. Migrated rather than rebuilt: the log and the settings on an
         * installed copy are the user's own practice record and must survive an update.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS observations (" +
                        "stopId INTEGER NOT NULL PRIMARY KEY, " +
                        "endedAtMs INTEGER NOT NULL, " +
                        "movement TEXT, " +
                        "feeling TEXT, " +
                        "thinking TEXT, " +
                        "hasVoiceNote INTEGER NOT NULL DEFAULT 0, " +
                        "notedAtMs INTEGER)"
                )
                db.execSQL(
                    "ALTER TABLE settings ADD COLUMN photosEnabled INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        /**
         * The Activity field arrives: the outer context a stop broke into, beside the three inner
         * registers already recorded. Added, not rebuilt, so an installed practice record survives.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE observations ADD COLUMN activity TEXT")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "gstop.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
    }
}
