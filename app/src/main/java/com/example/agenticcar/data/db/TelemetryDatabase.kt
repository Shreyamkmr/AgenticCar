package com.example.agenticcar.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TripEntity::class, SignalBlockEntity::class, EventEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class TelemetryDatabase : RoomDatabase() {
    abstract fun telemetryDao(): TelemetryDao

    companion object {
        const val NAME = "telemetry.db"

        fun build(context: Context): TelemetryDatabase =
            Room.databaseBuilder(context, TelemetryDatabase::class.java, NAME)
                // WAL lets the batch writer and MCP/UI readers run concurrently
                // without readers blocking on the writer's lock.
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                // Version 1 held only simulator test snapshots; nothing worth migrating.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
