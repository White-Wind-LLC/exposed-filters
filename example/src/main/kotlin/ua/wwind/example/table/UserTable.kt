package ua.wwind.example.table

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import ua.wwind.example.type.UserId
import ua.wwind.example.type.userId

object UserTable : Table("users") {
    val id: Column<UserId> = userId("id").autoIncrement()
    val name: Column<String> = varchar("name", 100)
    val age: Column<Int> = integer("age")

    override val primaryKey = PrimaryKey(id)
}

data class User(val id: UserId?, val name: String, val age: Int)
