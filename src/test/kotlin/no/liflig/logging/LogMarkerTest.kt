package no.liflig.logging

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.math.BigDecimal
import java.net.URI
import java.net.URL
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Encoder
import org.junit.jupiter.api.Test

private val log = Logger {}

class LogMarkerTest {
  @Test
  fun `basic log marker test`() {
    val markers = captureLogMarkers {
      log.info {
        addMarker("key", "value")
        "Test"
      }
    }

    markers shouldBe
        """
          "key":"value"
        """
            .trimIndent()
  }

  @Test
  fun `log marker with Serializable object`() {
    @Serializable data class User(val id: Int, val name: String)

    val user = User(id = 1, name = "John Doe")

    val markers = captureLogMarkers {
      log.info {
        addMarker("user", user)
        "Test"
      }
    }

    markers shouldBe
        """
          "user":{"id":1,"name":"John Doe"}
        """
            .trimIndent()
  }

  @Test
  fun `multiple log markers`() {
    val markers = captureLogMarkers {
      log.info {
        addMarker("first", true)
        addMarker("second", listOf("value1", "value2"))
        addMarker("third", 10)
        "Test"
      }
    }

    markers shouldBe
        """
          "first":true,"second":["value1","value2"],"third":10
        """
            .trimIndent()
  }

  @Test
  fun `special-case types`() {
    val markers = captureLogMarkers {
      log.info {
        addMarker("instant", Instant.parse("2024-12-09T16:38:23Z"))
        addMarker("uri", URI.create("https://example.com"))
        addMarker("url", URL("https://example.com"))
        addMarker("uuid", UUID.fromString("3638dd04-d196-41ad-8b15-5188a22a6ba4"))
        addMarker("bigDecimal", BigDecimal("100.0"))
        "Test"
      }
    }

    markers shouldContain """"instant":"2024-12-09T16:38:23Z""""
    markers shouldContain """"uri":"https://example.com""""
    markers shouldContain """"url":"https://example.com""""
    markers shouldContain """"uuid":"3638dd04-d196-41ad-8b15-5188a22a6ba4""""
    markers shouldContain """"bigDecimal":"100.0""""
  }

  @Test
  fun `custom serializer`() {
    val prefixSerializer =
        object : SerializationStrategy<String> {
          override val descriptor = String.serializer().descriptor

          override fun serialize(encoder: Encoder, value: String) {
            encoder.encodeString("Prefix: ${value}")
          }
        }

    val markers = captureLogMarkers {
      log.info {
        addMarker("key", "value", serializer = prefixSerializer)
        "Test"
      }
    }

    markers shouldBe
        """
          "key":"Prefix: value"
        """
            .trimIndent()
  }

  @Test
  fun `non-serializable object falls back to toString`() {
    data class User(val id: Int, val name: String)

    val user = User(id = 1, name = "John Doe")

    val markers = captureLogMarkers {
      log.info {
        addMarker("user", user)
        "Test"
      }
    }

    markers shouldBe
        """
          "user":"User(id=1, name=John Doe)"
        """
            .trimIndent()
  }

  @Test
  fun `duplicate markers only includes the first marker`() {
    val markers = captureLogMarkers {
      log.info {
        addMarker("duplicateKey", "value1")
        addMarker("duplicateKey", "value2")
        addMarker("duplicateKey", "value3")
        "Test"
      }
    }

    markers shouldBe
        """
          "duplicateKey":"value1"
        """
            .trimIndent()
  }

  @Test
  fun `rawJsonMarker works for valid JSON`() {
    val userJson = """{"id":1,"name":"John Doe"}"""

    // The above JSON should work both for validJson = true and validJson = false
    for (assumeValidJson in listOf(true, false)) {
      withClue({ "assumeValidJson = ${assumeValidJson}" }) {
        val markers = captureLogMarkers {
          log.info {
            addRawJsonMarker("user", userJson, validJson = assumeValidJson)
            "Test"
          }
        }

        markers shouldBe
            """
              "user":${userJson}
            """
                .trimIndent()
      }
    }
  }

  @Test
  fun `rawJsonMarker escapes invalid JSON by default`() {
    val invalidJson = """{"id":1"""

    val markers = captureLogMarkers {
      log.info {
        addRawJsonMarker("user", invalidJson)
        "Test"
      }
    }

    markers shouldBe
        """
          "user":"{\"id\":1"
        """
            .trimIndent()
  }

  /**
   * When the user sets validJson = true on rawJsonMarker, they promise that the given JSON is
   * valid, so it should be passed on as-is. We therefore verify here that no validity checks are
   * made on the given JSON, although the user _should_ never pass invalid JSON to rawJsonMarker
   * like this.
   */
  @Test
  fun `rawJsonMarker does not escape invalid JSON when validJson is set to true`() {
    val invalidJson = """{"id":1"""

    val markers = captureLogMarkers {
      log.info {
        addRawJsonMarker("user", invalidJson, validJson = true)
        "Test"
      }
    }

    markers shouldBe
        """
          "user":${invalidJson}
        """
            .trimIndent()
  }

  @Test
  fun `rawJsonMarker re-encodes JSON when it contains newlines`() {
    val jsonWithNewlines =
        """
          {
            "id": 1,
            "name": "John Doe"
          }
        """
            .trimIndent()

    val markers = captureLogMarkers {
      log.info {
        addRawJsonMarker("user", jsonWithNewlines)
        "Test"
      }
    }

    markers shouldBe
        """
          "user":{"id":1,"name":"John Doe"}
        """
            .trimIndent()
  }

  @Test
  fun `addExistingMarker allows adding a previously constructed marker to the log`() {
    val existingLogMarker = marker("key", "value")

    val markers = captureLogMarkers {
      log.info {
        addExistingMarker(existingLogMarker)
        "Test"
      }
    }

    markers shouldBe
        """
          "key":"value"
        """
            .trimIndent()
  }
}
