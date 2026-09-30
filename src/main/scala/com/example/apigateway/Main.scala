package com.example.apigateway

import cats.effect.{IO, IOApp}

object Main extends IOApp.Simple:
  val run = ApigatewayServer.run[IO]
