package com.example.apigateway.config

import java.io.File
import scala.io.Source
import scala.util.Using

final case class DbConfig(
    host: String,
    port: Int,
    database: String,
    user: String,
    password: String,
    poolSize: Int
):
  def url: String = s"jdbc:postgresql://$host:$port/$database"

final case class AdminConfig(
    apiKey: String
)

final case class EurekaConfig(
    eurekaUrl: String,
    appName: String,
    instanceHost: String,
    instancePort: Int,
    registerWithEureka: Boolean = true,
    refreshIntervalSeconds: Int = 10,
    heartbeatIntervalSeconds: Int = 30,
    enabled: Boolean = true
)

final case class AuthConfig(
    jwtSecret: String,
    jwtIssuer: String
)

final case class RedisConfig(
    host: String,
    port: Int,
    enabled: Boolean
):
  def uri: String = s"redis://$host:$port"

final case class ResilienceConfig(
    circuitBreakerFailureThreshold: Int = 5,
    circuitBreakerOpenDurationMs: Long = 10000L,
    retryMaxAttempts: Int = 3,
    retryInitialDelayMs: Long = 100L
)

final case class AppConfig(
    db: DbConfig,
    admin: AdminConfig,
    eureka: EurekaConfig,
    auth: AuthConfig,
    redis: RedisConfig,
    resilience: ResilienceConfig
)

object AppConfig:

  /** Carrega variáveis de ambiente a partir de um arquivo .env se ele existir. */
  def loadDotEnv(file: File = new File(".env")): Map[String, String] =
    if file.exists() && file.isFile then
      Using(Source.fromFile(file, "UTF-8")) { source =>
        source
          .getLines()
          .map(_.trim)
          .filterNot(line => line.isEmpty || line.startsWith("#"))
          .flatMap { line =>
            val splitIdx = line.indexOf('=')
            if splitIdx > 0 then
              val key = line.substring(0, splitIdx).trim
              val rawValue = line.substring(splitIdx + 1).trim
              val value =
                if (rawValue.startsWith("\"") && rawValue.endsWith("\"") && rawValue.length >= 2) ||
                   (rawValue.startsWith("'") && rawValue.endsWith("'") && rawValue.length >= 2) then
                  rawValue.substring(1, rawValue.length - 1)
                else rawValue
              Some(key -> value)
            else None
          }
          .toMap
      }.getOrElse(Map.empty)
    else Map.empty

  def load(envFile: File = new File(".env")): AppConfig =
    val dotEnv = loadDotEnv(envFile)

    def getEnv(key: String): Option[String] =
      sys.env.get(key).filter(_.nonEmpty).orElse(dotEnv.get(key)).filter(_.nonEmpty)

    def requireEnv(key: String): String =
      getEnv(key).getOrElse(
        throw new IllegalStateException(
          s"Configuração sensível/obrigatória ausente: '$key'. Defina no arquivo .env ou como variável de ambiente do sistema."
        )
      )

    val dbHost = getEnv("DB_HOST").getOrElse("localhost")
    val dbPort = getEnv("DB_PORT").flatMap(_.toIntOption).getOrElse(5432)
    val dbName = getEnv("DB_NAME").getOrElse("apigateway")
    val dbUser = getEnv("DB_USER").getOrElse("postgres")
    val dbPass = requireEnv("DB_PASSWORD")
    val dbPool = getEnv("DB_POOL_SIZE").flatMap(_.toIntOption).getOrElse(10)

    val adminKey = requireEnv("ADMIN_API_KEY")

    val eurekaUrl = getEnv("EUREKA_URL").getOrElse("http://localhost:8761/eureka")
    val eurekaAppName = getEnv("EUREKA_APP_NAME").getOrElse("API-GATEWAY")
    val instanceHost = getEnv("INSTANCE_HOST").getOrElse("localhost")
    val instancePort = getEnv("PORT").orElse(getEnv("INSTANCE_PORT")).flatMap(_.toIntOption).getOrElse(8080)
    val eurekaRegister = getEnv("EUREKA_REGISTER").flatMap(_.toBooleanOption).getOrElse(true)
    val eurekaRefreshInterval = getEnv("EUREKA_REFRESH_INTERVAL").flatMap(_.toIntOption).getOrElse(10)
    val eurekaHeartbeatInterval = getEnv("EUREKA_HEARTBEAT_INTERVAL").flatMap(_.toIntOption).getOrElse(30)
    val eurekaEnabled = getEnv("EUREKA_ENABLED").flatMap(_.toBooleanOption).getOrElse(true)

    val jwtSecret = requireEnv("JWT_SECRET")
    val jwtIssuer = getEnv("JWT_ISSUER").getOrElse("apigateway")

    val redisHost = getEnv("REDIS_HOST").getOrElse("localhost")
    val redisPort = getEnv("REDIS_PORT").flatMap(_.toIntOption).getOrElse(6379)
    val redisEnabled = getEnv("REDIS_ENABLED").flatMap(_.toBooleanOption).getOrElse(true)

    val cbFailureThreshold = getEnv("CB_FAILURE_THRESHOLD").flatMap(_.toIntOption).getOrElse(5)
    val cbOpenDurationMs = getEnv("CB_OPEN_DURATION_MS").flatMap(_.toLongOption).getOrElse(10000L)
    val retryMaxAttempts = getEnv("RETRY_MAX_ATTEMPTS").flatMap(_.toIntOption).getOrElse(3)
    val retryInitialDelayMs = getEnv("RETRY_INITIAL_DELAY_MS").flatMap(_.toLongOption).getOrElse(100L)

    AppConfig(
      db = DbConfig(
        host = dbHost,
        port = dbPort,
        database = dbName,
        user = dbUser,
        password = dbPass,
        poolSize = dbPool
      ),
      admin = AdminConfig(
        apiKey = adminKey
      ),
      eureka = EurekaConfig(
        eurekaUrl = eurekaUrl,
        appName = eurekaAppName,
        instanceHost = instanceHost,
        instancePort = instancePort,
        registerWithEureka = eurekaRegister,
        refreshIntervalSeconds = eurekaRefreshInterval,
        heartbeatIntervalSeconds = eurekaHeartbeatInterval,
        enabled = eurekaEnabled
      ),
      auth = AuthConfig(
        jwtSecret = jwtSecret,
        jwtIssuer = jwtIssuer
      ),
      redis = RedisConfig(
        host = redisHost,
        port = redisPort,
        enabled = redisEnabled
      ),
      resilience = ResilienceConfig(
        circuitBreakerFailureThreshold = cbFailureThreshold,
        circuitBreakerOpenDurationMs = cbOpenDurationMs,
        retryMaxAttempts = retryMaxAttempts,
        retryInitialDelayMs = retryInitialDelayMs
      )
    )
