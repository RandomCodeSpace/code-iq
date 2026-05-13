package com.example.utils

fun add(a: Int, b: Int): Int = a + b

fun greet(name: String): String = "Hello, $name!"

fun <A> reverseList(xs: List<A>): List<A> = xs.reversed()

class Counter(initial: Int) {
    private var count = initial
    fun increment() { count++ }
    fun value(): Int = count
}

data class Pair<A, B>(val first: A, val second: B)
