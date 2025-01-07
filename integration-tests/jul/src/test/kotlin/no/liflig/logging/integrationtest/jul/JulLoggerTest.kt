package no.liflig.logging.integrationtest.jul

import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.serialization.Serializable
import no.liflig.logging.getLogger
import no.liflig.logging.rawJsonField
import no.liflig.logging.withLoggingContext
import org.junit.jupiter.api.Test

private val log = getLogger {}

/** Tests the slf4j-jdk14 implementation, which uses java.util.logging (commonly called JUL). */
class JulLoggerTest {
  @Test
  fun log() {
    @Serializable data class Event(val id: Long, val type: String)

    val event = Event(id = 1001, type = "ORDER_UPDATED")

    // java.util.logging logger outputs to stderr by default
    val output = captureStderr {
      // slf4j-jdk14 does not support MDC, which withLoggingContext uses.
      // But we still want to test that using withLoggingContext here does not affect the log.
      withLoggingContext(rawJsonField("contextField", """{"test":true}""")) {
        log.info {
          field("event", event)
          "Test"
        }
      }
    }

    val indexOfPackageName =
        withClue({ "Log output:\n${output}" }) {
          output.indexOf("no.liflig.logging") shouldNotBe -1 // -1 == not found
        }

    // Log output includes timestamp, which we can't test deterministically here - so we test output
    // from the package name (included in log output) up to the trailing newline
    output.substring(indexOfPackageName).removeSuffix("\n") shouldBe
        """
          no.liflig.logging.integrationtest.jul.JulLoggerTest log
          INFO: Test [event={"id":1001,"type":"ORDER_UPDATED"}]
        """
            .trimIndent()
  }

  @Test
  fun `Logback should not be on classpath`() {
    shouldThrowExactly<ClassNotFoundException> { Class.forName("ch.qos.logback.classic.Logger") }
    // We also want to make sure that logstash-logback-encoder is not loaded
    shouldThrowExactly<ClassNotFoundException> {
      Class.forName("net.logstash.logback.encoder.LogstashEncoder")
    }
  }
}

inline fun captureStderr(block: () -> Unit): String {
  val originalStderr = System.err

  // We redirect System.err to our own output stream, so we can capture the log output
  val outputStream = ByteArrayOutputStream()
  System.setErr(PrintStream(outputStream))

  try {
    block()
  } finally {
    System.setErr(originalStderr)
  }

  return outputStream.toString("UTF-8")
}
