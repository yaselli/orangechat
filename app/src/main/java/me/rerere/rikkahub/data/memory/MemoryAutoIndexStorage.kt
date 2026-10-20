package me.rerere.rikkahub.data.memory

import androidx.room.Entity
import androidx.room.Index

/** Durable attempted slice IDs prevent regeneration, process death or a failed call from billing the same batch again. */
@Entity(tableName = "memory_index_attempt", primaryKeys = ["owner", "namespace", "sliceId"],
    indices = [Index(value = ["owner", "createdAt"])])
data class MemoryIndexAttempt(
    val owner: String, val namespace: String, val sliceId: String, val batchId: String,
    val state: String, val detail: String, val createdAt: Long,
)
