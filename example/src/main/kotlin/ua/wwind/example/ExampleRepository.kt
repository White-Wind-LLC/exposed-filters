package ua.wwind.example

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table

object Users : Table("users") {
    val id: Column<Int> = integer("id").autoIncrement()
    val name: Column<String> = varchar("name", 100)
    val age: Column<Int> = integer("age")

    override val primaryKey = PrimaryKey(id)
}

data class User(val id: Int?, val name: String, val age: Int)
