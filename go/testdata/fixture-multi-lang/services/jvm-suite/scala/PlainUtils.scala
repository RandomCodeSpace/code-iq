package com.example.utils

object PlainUtils {
  def add(a: Int, b: Int): Int = a + b

  def greet(name: String): String = s"Hello, $name!"

  def reverseList[A](xs: List[A]): List[A] = xs.reverse
}

class Counter(initial: Int) {
  private var count = initial
  def increment(): Unit = { count += 1 }
  def value: Int = count
}
