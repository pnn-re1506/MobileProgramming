package com.example.jetpacknavigatepeopleroomcrud

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Person::class], version = 1, exportSchema = false)
abstract class PeopleDatabase : RoomDatabase() {
    abstract fun personDao(): PersonDao

    companion object {
        @Volatile
        private var instance: PeopleDatabase? = null

        // Instrumented tests set this to a separate file before the activity starts.
        @Volatile
        var databaseName: String = "people.db"

        fun getInstance(context: Context): PeopleDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PeopleDatabase::class.java,
                    databaseName
                ).build().also { instance = it }
            }
        }
    }
}
