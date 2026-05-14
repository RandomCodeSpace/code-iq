package com.example.cats

import cats.effect.{IO, IOApp}
import cats.effect.ExitCode

object CatsEffectApp extends IOApp {
  def run(args: List[String]): IO[ExitCode] =
    IO.println("hello cats").as(ExitCode.Success)

  def fetchData(url: String): IO[String] =
    IO.delay(s"response from $url")
}
