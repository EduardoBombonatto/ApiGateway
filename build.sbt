val Http4sVersion = "1.0.0-M29"
val MunitVersion = "0.7.29"
val LogbackVersion = "1.2.6"
val MunitCatsEffectVersion = "1.0.6"
val DoobieVersion = "1.0.0-RC5"
val FlywayVersion = "9.22.3"
val PostgresVersion = "42.7.3"
val CirceVersion = "0.14.3"

lazy val root = (project in file("."))
  .settings(
    organization := "com.example",
    name := "api-gateway",
    version := "0.0.1-SNAPSHOT",
    scalaVersion := "3.3.3",
    Compile / run / fork := true,
    libraryDependencies ++= Seq(
      "org.http4s"      %% "http4s-ember-server" % Http4sVersion,
      "org.http4s"      %% "http4s-ember-client" % Http4sVersion,
      "org.http4s"      %% "http4s-circe"        % Http4sVersion,
      "org.http4s"      %% "http4s-dsl"          % Http4sVersion,
      "io.circe"        %% "circe-generic"       % CirceVersion,
      "io.circe"        %% "circe-parser"        % CirceVersion,
      "org.tpolecat"    %% "doobie-core"         % DoobieVersion,
      "org.tpolecat"    %% "doobie-hikari"       % DoobieVersion,
      "org.tpolecat"    %% "doobie-postgres"     % DoobieVersion,
      "org.postgresql"  %  "postgresql"          % PostgresVersion,
      "org.flywaydb"    %  "flyway-core"         % FlywayVersion,
      "org.scalameta"   %% "munit"               % MunitVersion           % Test,
      "org.typelevel"   %% "munit-cats-effect-3" % MunitCatsEffectVersion % Test,
      "ch.qos.logback"  %  "logback-classic"     % LogbackVersion,
      "com.auth0"       %  "java-jwt"            % "4.4.0",
      "dev.profunktor"  %% "redis4cats-effects"  % "1.5.2",
      "io.prometheus"   %  "simpleclient"        % "0.16.0",
      "io.prometheus"   %  "simpleclient_common" % "0.16.0",
    ),
    testFrameworks += new TestFramework("munit.Framework")
  )
