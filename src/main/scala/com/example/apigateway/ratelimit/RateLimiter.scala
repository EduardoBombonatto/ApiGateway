package com.example.apigateway.ratelimit

import cats.effect.{Async, Ref, Resource}
import cats.syntax.all._
import dev.profunktor.redis4cats.RedisCommands
import dev.profunktor.redis4cats.effects.ScriptOutputType

sealed trait RateLimitResult
object RateLimitResult:
  final case class Allowed(limit: Int, remaining: Int) extends RateLimitResult
  final case class Rejected(limit: Int, retryAfterSeconds: Long) extends RateLimitResult

/** Contrato funcional para limitadores de taxa (Rate Limiting). */
trait RateLimiter[F[_]]:
  def acquire(key: String, capacity: Int, refillRate: Int, requested: Int = 1): F[RateLimitResult]

object RateLimiter:

  private val LuaTokenBucketScript: String =
    """local key = KEYS[1]
      |local capacity = tonumber(ARGV[1])
      |local refill_rate = tonumber(ARGV[2])
      |local requested = tonumber(ARGV[3])
      |local now = tonumber(ARGV[4])
      |
      |local data = redis.call('HMGET', key, 'tokens', 'last_refill')
      |local current_tokens = tonumber(data[1])
      |local last_refill = tonumber(data[2])
      |
      |if current_tokens == nil or last_refill == nil then
      |  current_tokens = capacity
      |  last_refill = now
      |else
      |  local elapsed_seconds = math.max(0, (now - last_refill) / 1000.0)
      |  current_tokens = math.min(capacity, current_tokens + (elapsed_seconds * refill_rate))
      |  last_refill = now
      |end
      |
      |local allowed = 0
      |local remaining = current_tokens
      |local retry_after = 0
      |
      |if current_tokens >= requested then
      |  allowed = 1
      |  remaining = current_tokens - requested
      |  current_tokens = remaining
      |else
      |  allowed = 0
      |  local needed = requested - current_tokens
      |  retry_after = math.ceil(needed / refill_rate)
      |  if retry_after < 1 then
      |    retry_after = 1
      |  end
      |end
      |
      |redis.call('HMSET', key, 'tokens', current_tokens, 'last_refill', last_refill)
      |local ttl = math.max(60, math.ceil(capacity / refill_rate * 2))
      |redis.call('EXPIRE', key, ttl)
      |
      |return tostring(allowed) .. ":" .. tostring(math.floor(remaining)) .. ":" .. tostring(retry_after)
      |""".stripMargin

  /** Cria uma instância de RateLimiter em memória via Ref (ideal para testes unitários ou contingência). */
  def inMemory[F[_]: Async]: F[RateLimiter[F]] =
    Ref.of[F, Map[String, (Double, Long)]](Map.empty).map { ref =>
      new RateLimiter[F]:
        override def acquire(key: String, capacity: Int, refillRate: Int, requested: Int = 1): F[RateLimitResult] =
          Async[F].delay(System.currentTimeMillis()).flatMap { now =>
            ref.modify { state =>
              val (currentTokens, lastRefill) = state.getOrElse(key, (capacity.toDouble, now))
              val elapsedSeconds = Math.max(0.0, (now - lastRefill) / 1000.0)
              val refilledTokens = Math.min(capacity.toDouble, currentTokens + (elapsedSeconds * refillRate.toDouble))

              if refilledTokens >= requested.toDouble then
                val remaining = refilledTokens - requested.toDouble
                val updatedState = state.updated(key, (remaining, now))
                (updatedState, RateLimitResult.Allowed(capacity, remaining.toInt))
              else
                val needed = requested.toDouble - refilledTokens
                val retryAfter = Math.max(1L, Math.ceil(needed / refillRate.toDouble).toLong)
                val updatedState = state.updated(key, (refilledTokens, now))
                (updatedState, RateLimitResult.Rejected(capacity, retryAfter))
            }
          }
    }

  /** Cria uma instância de RateLimiter distribuído no Redis via script Lua atômico com suporte a fallback. */
  def redis[F[_]: Async](
      redisCommands: RedisCommands[F, String, String],
      fallback: Option[RateLimiter[F]] = None
  ): RateLimiter[F] = new RateLimiter[F]:
    override def acquire(key: String, capacity: Int, refillRate: Int, requested: Int = 1): F[RateLimitResult] =
      Async[F].delay(System.currentTimeMillis().toString).flatMap { nowStr =>
        val keys = List(key)
        val args = List(capacity.toString, refillRate.toString, requested.toString, nowStr)

        redisCommands
          .eval(LuaTokenBucketScript, ScriptOutputType.Value, keys, args)
          .map { raw =>
            val parts = raw.split(":")
            val allowed = parts(0) == "1"
            val remaining = parts(1).toInt
            val retryAfter = parts(2).toLong
            if allowed then RateLimitResult.Allowed(capacity, remaining)
            else RateLimitResult.Rejected(capacity, retryAfter)
          }
          .handleErrorWith { _ =>
            fallback match
              case Some(fb) => fb.acquire(key, capacity, refillRate, requested)
              case None     => (RateLimitResult.Allowed(capacity, capacity): RateLimitResult).pure[F]
          }
      }

  /** Cria um Resource gerenciado conectado ao Redis para o RateLimiter. */
  def resource[F[_]: Async](redisUri: String): Resource[F, RateLimiter[F]] =
    import dev.profunktor.redis4cats.Redis
    import dev.profunktor.redis4cats.effect.Log
    given Log[F] = Log.NoOp.instance[F]

    for {
      inMem <- Resource.eval(inMemory[F])
      redisCommands <- Redis[F].utf8(redisUri)
    } yield redis[F](redisCommands, Some(inMem))
