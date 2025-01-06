package no.liflig.logging.integrationtest.log4j

import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.serialization.Serializable
import no.liflig.logging.getLogger
import no.liflig.logging.rawJsonField
import no.liflig.logging.withLoggingContext
import org.junit.jupiter.api.Test

private val log = getLogger {}

class Log4jLoggerTest {
  @Test
  fun log() {
    @Serializable data class User(val id: Long, val name: String)

    val user = User(id = 1, name = "John Doe")

    val output = captureStdout {
      withLoggingContext(rawJsonField("contextField", """{"test":true}""")) {
        log.info {
          field("user", user)
          "Test"
        }
      }
    }

    output shouldContain """"log.level":"INFO""""
    output shouldContain """"message":"Test [user={\"id\":1,\"name\":\"John Doe\"}]""""

    // JSON fields in logging context adds a (json) suffix, so we can identify these fields and
    // write them as raw JSON when using Logback. But we don't have such an implementation for
    // Log4j, so these fields will be written with the key suffix and escaped JSON value.
    output shouldContain """"contextField (json)":"{\"test\":true}""""
  }

  @Test
  fun `Logback should not be on classpath`() {
    shouldThrowExactly<ClassNotFoundException> { Class.forName("ch.qos.logback.classic.Logger") }
    // We also want to make sure that logstash-logback-encoder is not loaded
    shouldThrowExactly<ClassNotFoundException> {
      Class.forName("net.logstash.logback.composite.loggingevent.mdc.MdcEntryWriter")
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
