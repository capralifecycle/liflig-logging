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

private val log = getLogger {}

class LogFieldTest {
  @Test
  fun `basic log field test`() {
    val logFields = captureLogFields {
      log.info {
        addField("key", "value")
        "Test"
      }
    }

    logFields shouldBe
        """
          "key":"value"
        """
            .trimIndent()
  }

  @Test
  fun `log field with Serializable object`() {
    @Serializable data class User(val id: Int, val name: String)

    val user = User(id = 1, name = "John Doe")

    val logFields = captureLogFields {
      log.info {
        addField("user", user)
        "Test"
      }
    }

    logFields shouldBe
        """
          "user":{"id":1,"name":"John Doe"}
        """
            .trimIndent()
  }

  @Test
  fun `multiple log fields`() {
    val logFields = captureLogFields {
      log.info {
        addField("first", true)
        addField("second", listOf("value1", "value2"))
        addField("third", 10)
        "Test"
      }
    }

    logFields shouldBe
        """
          "first":true,"second":["value1","value2"],"third":10
        """
            .trimIndent()
  }

  @Test
  fun `special-case types`() {
    val logFields = captureLogFields {
      log.info {
        addField("instant", Instant.parse("2024-12-09T16:38:23Z"))
        addField("uri", URI.create("https://example.com"))
        addField("url", URL("https://example.com"))
        addField("uuid", UUID.fromString("3638dd04-d196-41ad-8b15-5188a22a6ba4"))
        addField("bigDecimal", BigDecimal("100.0"))
        "Test"
      }
    }

    logFields shouldContain """"instant":"2024-12-09T16:38:23Z""""
    logFields shouldContain """"uri":"https://example.com""""
    logFields shouldContain """"url":"https://example.com""""
    logFields shouldContain """"uuid":"3638dd04-d196-41ad-8b15-5188a22a6ba4""""
    logFields shouldContain """"bigDecimal":"100.0""""
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

    val logFields = captureLogFields {
      log.info {
        addField("key", "value", serializer = prefixSerializer)
        "Test"
      }
    }

    logFields shouldBe
        """
          "key":"Prefix: value"
        """
            .trimIndent()
  }

  @Test
  fun `non-serializable object falls back to toString`() {
    data class User(val id: Int, val name: String)

    val user = User(id = 1, name = "John Doe")

    val logFields = captureLogFields {
      log.info {
        addField("user", user)
        "Test"
      }
    }

    logFields shouldBe
        """
          "user":"User(id=1, name=John Doe)"
        """
            .trimIndent()
  }

  @Test
  fun `duplicate field keys only includes the first field`() {
    val logFields = captureLogFields {
      log.info {
        addField("duplicateKey", "value1")
        addField("duplicateKey", "value2")
        addField("duplicateKey", "value3")
        "Test"
      }
    }

    logFields shouldBe
        """
          "duplicateKey":"value1"
        """
            .trimIndent()
  }

  @Test
  fun `null field value is allowed`() {
    val nullValue: String? = null

    val logFields = captureLogFields {
      log.info {
        addField("key", nullValue)
        "Test"
      }
    }

    logFields shouldBe
        """
          "key":null
        """
            .trimIndent()
  }

  @Test
  fun `addRawJsonField works for valid JSON`() {
    val userJson = """{"id":1,"name":"John Doe"}"""

    // The above JSON should work both for validJson = true and validJson = false
    for (assumeValidJson in listOf(true, false)) {
      withClue({ "assumeValidJson = ${assumeValidJson}" }) {
        val logFields = captureLogFields {
          log.info {
            addRawJsonField("user", userJson, validJson = assumeValidJson)
            "Test"
          }
        }

        logFields shouldBe
            """
              "user":${userJson}
            """
                .trimIndent()
      }
    }
  }

  @Test
  fun `addRawJsonField escapes invalid JSON by default`() {
    val invalidJson = """{"id":1"""

    val logFields = captureLogFields {
      log.info {
        addRawJsonField("user", invalidJson)
        "Test"
      }
    }

    logFields shouldBe
        """
          "user":"{\"id\":1"
        """
            .trimIndent()
  }

  /**
   * When the user sets validJson = true on addRawJsonField, they promise that the given JSON is
   * valid, so it should be passed on as-is. We therefore verify here that no validity checks are
   * made on the given JSON, although the user _should_ never pass invalid JSON to addRawJsonField
   * like this.
   */
  @Test
  fun `addRawJsonField does not escape invalid JSON when validJson is set to true`() {
    val invalidJson = """{"id":1"""

    val logFields = captureLogFields {
      log.info {
        addRawJsonField("user", invalidJson, validJson = true)
        "Test"
      }
    }

    logFields shouldBe
        """
          "user":${invalidJson}
        """
            .trimIndent()
  }

  @Test
  fun `addRawJsonField re-encodes JSON when it contains newlines`() {
    val jsonWithNewlines =
        """
          {
            "id": 1,
            "name": "John Doe"
          }
        """
            .trimIndent()

    val logFields = captureLogFields {
      log.info {
        addRawJsonField("user", jsonWithNewlines)
        "Test"
      }
    }

    logFields shouldBe
        """
          "user":{"id":1,"name":"John Doe"}
        """
            .trimIndent()
  }

  @Test
  fun `addPreconstructedField allows adding a previously constructed field to the log`() {
    val existingField = field("key", "value")

    val logFields = captureLogFields {
      log.info {
        addPreconstructedField(existingField)
        "Test"
      }
    }

    logFields shouldBe
        """
          "key":"value"
        """
            .trimIndent()
  }
}
