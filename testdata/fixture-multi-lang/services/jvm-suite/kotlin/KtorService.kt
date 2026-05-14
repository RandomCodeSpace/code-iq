package com.example.ktor

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun Application.module() {
    routing {
        route("/api") {
            get("/health") {
                call.respondText("OK")
            }
            post("/echo") {
                call.respondText("echo")
            }
        }
    }
}

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module).start(wait = true)
}
