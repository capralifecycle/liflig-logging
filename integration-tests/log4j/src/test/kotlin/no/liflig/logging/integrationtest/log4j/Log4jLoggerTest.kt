package no.liflig.logging.integrationtest.log4j

import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlinx.serialization.Serializable
import no.liflig.logging.getLogger
import no.liflig.logging.rawJsonField
import no.liflig.logging.withLoggingContext

private val log = getLogger()

class Log4jLoggerTest {
  @Test
  fun log() {
    @Serializable data class Event(val id: Long, val type: String)

    val event = Event(id = 1001, type = "ORDER_UPDATED")

    val output = captureStdout {
      withLoggingContext(rawJsonField("contextField", """{"test":true}""")) {
        log.info {
          field("event", event)
          "Test"
        }
      }
    }

    output shouldContain """"log.level":"INFO""""
    output shouldContain """"message":"Test [event={\"id\":1001,\"type\":\"ORDER_UPDATED\"}]""""

    // When using Logback with logstash-logback-encoder, we provide a JsonContextFieldWriter for
    // writing objects in withLoggingContext as actual JSON. But we don't have any equivalent
    // implementation for Log4j, so the JSON will be escaped.
    output shouldContain """"contextField":"{\"test\":true}""""
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
