package com.example.parser

object PlainParser {
  def parseInts(s: String): List[Int] =
    s.split(",").toList.flatMap { tok =>
      tok.trim.toIntOption.toList
    }

  def tokenize(s: String): List[String] =
    s.split("\\s+").toList.filter(_.nonEmpty)
}

case class Token(kind: String, value: String)

trait Parseable {
  def parse(input: String): List[Token]
}
