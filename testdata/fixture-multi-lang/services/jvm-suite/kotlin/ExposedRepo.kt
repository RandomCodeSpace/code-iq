package com.example.exposed

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

object Users : Table("users") {
    val id   = integer("id").autoIncrement()
    val name = varchar("name", 128)
    override val primaryKey = PrimaryKey(id)
}

fun insertUser(name: String) = transaction {
    Users.insert { it[Users.name] = name }
}

fun listUsers(): List<String> = transaction {
    Users.selectAll().map { it[Users.name] }
}
