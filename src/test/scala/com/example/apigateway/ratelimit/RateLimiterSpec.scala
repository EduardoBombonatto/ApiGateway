package com.example.apigateway.ratelimit

import cats.effect.IO
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.effect.Log
import munit.CatsEffectSuite

class RateLimiterSpec extends CatsEffectSuite:

  given Log[IO] = Log.NoOp.instance[IO]

  test("RateLimiter.inMemory deve permitir requisições até a capacidade e bloquear o excedente com 429") {
    for {
      limiter <- RateLimiter.inMemory[IO]
      key = "test-key-inmem"
      // Capacidade 3, recarga de 1 token por segundo
      r1 <- limiter.acquire(key, capacity = 3, refillRate = 1)
      r2 <- limiter.acquire(key, capacity = 3, refillRate = 1)
      r3 <- limiter.acquire(key, capacity = 3, refillRate = 1)
      r4 <- limiter.acquire(key, capacity = 3, refillRate = 1)
    } yield {
      assertEquals(r1, RateLimitResult.Allowed(3, 2))
      assertEquals(r2, RateLimitResult.Allowed(3, 1))
      assertEquals(r3, RateLimitResult.Allowed(3, 0))
      assert(r4.isInstanceOf[RateLimitResult.Rejected])
      val rejected = r4.asInstanceOf[RateLimitResult.Rejected]
      assertEquals(rejected.limit, 3)
      assert(rejected.retryAfterSeconds >= 1L)
    }
  }

  test("RateLimiter.redis deve executar o script Lua atômico no Redis real") {
    val redisUri = "redis://localhost:6379"
    Redis[IO].utf8(redisUri).use { redisCmds =>
      val limiter = RateLimiter.redis[IO](redisCmds)
      val key = s"test-redis-bucket-${System.currentTimeMillis()}"

      for {
        r1 <- limiter.acquire(key, capacity = 2, refillRate = 1)
        r2 <- limiter.acquire(key, capacity = 2, refillRate = 1)
        r3 <- limiter.acquire(key, capacity = 2, refillRate = 1)
      } yield {
        assertEquals(r1, RateLimitResult.Allowed(2, 1))
        assertEquals(r2, RateLimitResult.Allowed(2, 0))
        assert(r3.isInstanceOf[RateLimitResult.Rejected])
      }
    }
  }
