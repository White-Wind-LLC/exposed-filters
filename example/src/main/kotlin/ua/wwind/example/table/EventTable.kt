@file:OptIn(ExperimentalTime::class)

package ua.wwind.example.table

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.time.ExperimentalTime

object EventTable : Table("events") {
    val id: Column<Int> = integer("id").autoIncrement()
    val title: Column<String> = varchar("title", length = 100)
    val day = date("day")
    val occurredAt = timestamp("occurred_at")
    override val primaryKey = PrimaryKey(id)
}
