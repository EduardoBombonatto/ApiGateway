package com.example.apigateway.config

import munit.FunSuite
import java.io.{File, PrintWriter}
import java.nio.file.Files

class AppConfigSpec extends FunSuite:

  test("AppConfig.loadDotEnv deve parsear corretamente variáveis, comentários e aspas") {
    val tempFile = Files.createTempFile("test_env_", ".env").toFile
    tempFile.deleteOnExit()

    val writer = new PrintWriter(tempFile)
    try {
      writer.println("# Comentário a ser ignorado")
      writer.println("")
      writer.println("VAR_SIMPLES=valor1")
      writer.println("VAR_COM_ASPAS=\"valor com espaco\"")
      writer.println("VAR_ASPAS_SIMPLES='valor_simples'")
      writer.println("VAR_COM_IGUAL=segredo=com=sinais=de=igual")
    } finally {
      writer.close()
    }

    val result = AppConfig.loadDotEnv(tempFile)

    assertEquals(result.get("VAR_SIMPLES"), Some("valor1"))
    assertEquals(result.get("VAR_COM_ASPAS"), Some("valor com espaco"))
    assertEquals(result.get("VAR_ASPAS_SIMPLES"), Some("valor_simples"))
    assertEquals(result.get("VAR_COM_IGUAL"), Some("segredo=com=sinais=de=igual"))
    assertEquals(result.get("NAO_EXISTE"), None)
  }

  test("AppConfig.load deve falhar quando variáveis sensíveis obrigatórias estiverem ausentes") {
    val emptyEnvFile = Files.createTempFile("empty_", ".env").toFile
    emptyEnvFile.deleteOnExit()

    // Sem DB_PASSWORD, ADMIN_API_KEY ou JWT_SECRET
    val ex = intercept[IllegalStateException] {
      AppConfig.load(emptyEnvFile)
    }

    assert(ex.getMessage.contains("Configuração sensível/obrigatória ausente"))
  }

  test("AppConfig.load deve carregar com sucesso quando arquivo .env contém todas as variáveis necessárias") {
    val tempEnvFile = Files.createTempFile("valid_", ".env").toFile
    tempEnvFile.deleteOnExit()

    val writer = new PrintWriter(tempEnvFile)
    try {
      writer.println("DB_PASSWORD=my_test_password")
      writer.println("ADMIN_API_KEY=my_admin_key")
      writer.println("JWT_SECRET=my_jwt_secret_value")
      writer.println("DB_PORT=5433")
    } finally {
      writer.close()
    }

    val cfg = AppConfig.load(tempEnvFile)

    assertEquals(cfg.db.password, "my_test_password")
    assertEquals(cfg.db.port, 5433)
    assertEquals(cfg.admin.apiKey, "my_admin_key")
    assertEquals(cfg.auth.jwtSecret, "my_jwt_secret_value")
  }
