package com.example.agenticcar.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TripEntity::class, SignalBlockEntity::class, EventEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class TelemetryDatabase : RoomDatabase() {
    abstract fun telemetryDao(): TelemetryDao

    companion object {
        const val NAME = "telemetry.db"

        /** Adds trips.pids_per_request; existing trips keep their data with the column left null. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN pids_per_request INTEGER")
            }
        }

        fun build(context: Context): TelemetryDatabase =
            Room.databaseBuilder(context, TelemetryDatabase::class.java, NAME)
                // WAL lets the batch writer and MCP/UI readers run concurrently
                // without readers blocking on the writer's lock.
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_2_3)
                // Version 1 held only simulator test snapshots; nothing worth migrating.
                .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
                .build()
    }
}
