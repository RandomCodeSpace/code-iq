package com.example.akka

import akka.actor.{Actor, ActorSystem, Props}
import akka.actor.ActorRef

class GreetActor extends Actor {
  def receive: Receive = {
    case msg: String => println(s"Got: $msg")
  }
}

object AkkaService {
  def main(args: Array[String]): Unit = {
    val system = ActorSystem("demo")
    val ref: ActorRef = system.actorOf(Props[GreetActor](), "greeter")
    ref ! "hello"
  }
}
