@file:OptIn(kotlin.time.ExperimentalTime::class)
package ua.wwind.example

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.timestamp

object Events : Table("events") {
    val id: Column<Int> = integer("id").autoIncrement()
    val title: Column<String> = varchar("title", length = 100)
    val day = date("day")
    val occurredAt = timestamp("occurred_at")
    override val primaryKey = PrimaryKey(id)
}
