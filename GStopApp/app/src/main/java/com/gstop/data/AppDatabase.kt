package com.gstop.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SettingsEntity::class,
        SleepWindowEntity::class,
        ScheduledStopEntity::class,
        HistoryEventEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun settingsDao(): SettingsDao
    abstract fun sleepWindowDao(): SleepWindowDao
    abstract fun scheduledStopDao(): ScheduledStopDao
    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "gstop.db"
                ).build().also { instance = it }
            }
    }
}
