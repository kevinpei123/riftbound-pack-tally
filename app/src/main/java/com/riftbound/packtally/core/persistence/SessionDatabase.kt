package com.riftbound.packtally.core.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [BoxSessionEntity::class, PackSessionEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class SessionDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao

    companion object {
        fun create(context: Context): SessionDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                SessionDatabase::class.java,
                "session.db",
            )
                // Personal-use app — schema migrations skipped per user spec.
                .fallbackToDestructiveMigration()
                .build()
    }
}
