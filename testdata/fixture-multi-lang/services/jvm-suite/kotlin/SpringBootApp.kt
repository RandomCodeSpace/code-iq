package com.example.spring

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@SpringBootApplication
class SpringBootApp

fun main(args: Array<String>) {
    runApplication<SpringBootApp>(*args)
}

@RestController
class GreetController {
    @GetMapping("/hello")
    fun hello(): String = "Hello, Spring!"
}
