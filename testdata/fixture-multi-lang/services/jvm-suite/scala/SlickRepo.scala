package com.example.slick

import slick.jdbc.H2Profile.api._
import slick.lifted.TableQuery

class UsersTable(tag: Tag) extends Table[(Int, String)](tag, "USERS") {
  def id    = column[Int]("ID", O.PrimaryKey)
  def name  = column[String]("NAME")
  def * = (id, name)
}

object SlickRepo {
  val users = TableQuery[UsersTable]

  def insertUser(id: Int, name: String) =
    users += (id, name)
}
