package com.example.jetpacknavigatepeopleroomcrud

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonDao {
    @Query("SELECT * FROM people ORDER BY id")
    fun getAll(): Flow<List<Person>>

    @Query("SELECT * FROM people WHERE id = :personId")
    suspend fun getById(personId: Int): Person?

    @Query("SELECT COUNT(*) FROM people")
    suspend fun count(): Int

    @Insert
    suspend fun insertAll(people: List<Person>)

    @Insert
    suspend fun insert(person: Person)

    @Delete
    suspend fun delete(person: Person)

    @Update
    suspend fun update(person: Person)

    @Query("DELETE FROM people")
    suspend fun deleteAll()
}
