package com.example.parser

fun parseInts(s: String): List<Int> =
    s.split(",").mapNotNull { it.trim().toIntOrNull() }

fun tokenize(s: String): List<String> =
    s.split("\\s+".toRegex()).filter { it.isNotEmpty() }

data class Token(val kind: String, val value: String)

interface Parseable {
    fun parse(input: String): List<Token>
}

class SimpleParser : Parseable {
    override fun parse(input: String): List<Token> =
        tokenize(input).map { Token("word", it) }
}
