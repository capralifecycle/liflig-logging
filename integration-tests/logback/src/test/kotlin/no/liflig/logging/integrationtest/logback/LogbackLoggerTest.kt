package no.liflig.logging.integrationtest.logback

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.serialization.Serializable
import no.liflig.logging.getLogger
import org.junit.jupiter.api.Test

private val log = getLogger {}

class LogbackLoggerTest {
  @Test
  fun log() {
    @Serializable data class User(val id: Long, val name: String)

    val user = User(id = 1, name = "John Doe")

    val output = captureStdout {
      log.info {
        addField("user", user)
        "Test"
      }
    }

    output shouldContain """"level":"INFO""""
    output shouldContain """"message":"Test""""
    output shouldContain """"user":{"id":1,"name":"John Doe"}"""
  }

  @Test
  fun `Logback should be on classpath`() {
    shouldNotThrowAny { Class.forName("ch.qos.logback.classic.Logger") }
  }
}

inline fun captureStdout(block: () -> Unit): String {
  val originalStdout = System.out

  // We redirect System.err to our own output stream, so we can capture the log output
  val outputStream = ByteArrayOutputStream()
  System.setOut(PrintStream(outputStream))

  try {
    block()
  } finally {
    System.setOut(originalStdout)
  }

  return outputStream.toString("UTF-8")
}
