package no.liflig.logging.integrationtest.logback

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.serialization.Serializable
import no.liflig.logging.getLogger
import no.liflig.logging.rawJsonField
import no.liflig.logging.withLoggingContext
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class LogbackLoggerTest {
  @Order(1) // Run this first, to test constructing a JSON log field without Logback on classpath
  @Test
  fun log() {
    /**
     * We want to test that constructing a JSON field before loading a logger works (see docstring
     * on `ADD_JSON_SUFFIX_TO_LOGGING_CONTEXT_KEYS` in the library).
     */
    val jsonField = rawJsonField("contextField", """{"test":true}""")

    @Serializable data class Event(val id: Long, val type: String)

    val event = Event(id = 1001, type = "ORDER_UPDATED")

    val log = getLogger {}

    val output = captureStdout {
      withLoggingContext(jsonField) {
        log.info {
          field("event", event)
          "Test"
        }
      }
    }

    output shouldContain """"level":"INFO""""
    output shouldContain """"message":"Test""""
    output shouldContain """"event":{"id":1001,"type":"ORDER_UPDATED"}"""
    output shouldContain """"contextField":{"test":true}"""
  }

  @Order(2)
  @Test
  fun `Logback should be on classpath`() {
    shouldNotThrowAny { Class.forName("ch.qos.logback.classic.Logger") }
    // We also want to make sure that logstash-logback-encoder is loaded
    shouldNotThrowAny { Class.forName("net.logstash.logback.encoder.LogstashEncoder") }
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
