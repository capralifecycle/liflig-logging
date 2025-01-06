package no.liflig.logging

import io.kotest.assertions.withClue
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.math.BigDecimal
import java.net.URI
import java.net.URL
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.Test

private val log = getLogger {}

class LogFieldTest {
  @Test
  fun `basic log field test`() {
    val output = captureLogOutput {
      log.info {
        field("key", "value")
        "Test"
      }
    }

    output.logFields shouldBe
        """
          "key":"value"
        """
            .trimIndent()
  }

  @Test
  fun `log field with Serializable object`() {
    val user = User(id = 1, name = "John Doe")

    val output = captureLogOutput {
      log.info {
        field("user", user)
        "Test"
      }
    }

    output.logFields shouldBe
        """
          "user":{"id":1,"name":"John Doe"}
        """
            .trimIndent()
  }

  @Test
  fun `multiple log fields`() {
    val output = captureLogOutput {
      log.info {
        field("first", true)
        field("second", listOf("value1", "value2"))
        field("third", 10)
        "Test"
      }
    }

    output.logFields shouldBe
        """
          "first":true,"second":["value1","value2"],"third":10
        """
            .trimIndent()
  }

  @Test
  fun `special-case types`() {
    val output = captureLogOutput {
      log.info {
        field("instant", Instant.parse("2024-12-09T16:38:23Z"))
        field("uri", URI.create("https://example.com"))
        field("url", URL("https://example.com"))
        field("uuid", UUID.fromString("3638dd04-d196-41ad-8b15-5188a22a6ba4"))
        field("bigDecimal", BigDecimal("100.0"))
        "Test"
      }
    }

    output.logFields shouldContain """"instant":"2024-12-09T16:38:23Z""""
    output.logFields shouldContain """"uri":"https://example.com""""
    output.logFields shouldContain """"url":"https://example.com""""
    output.logFields shouldContain """"uuid":"3638dd04-d196-41ad-8b15-5188a22a6ba4""""
    output.logFields shouldContain """"bigDecimal":"100.0""""
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

    val output = captureLogOutput {
      log.info {
        field("key", "value", serializer = prefixSerializer)
        "Test"
      }
    }

    output.logFields shouldBe
        """
          "key":"Prefix: value"
        """
            .trimIndent()
  }

  /**
   * On [field] and [LogBuilder.field], we place a non-nullable `Any` bound on the `ValueT` type
   * parameter, and type the `value` parameter as `ValueT?`. This is to support passing in a custom
   * serializer for a type, but still allow passing in `null` for the value (since this is handled
   * before checking the serializer in [encodeFieldValue]). This test checks that this works.
   */
  @Test
  fun `custom serializer with nullable value`() {
    val user: User? = null

    val output = captureLogOutput {
      withLoggingContext(
          // Test `field` in context and on log, since these are different functions
          field("userInContext", user, User.serializer()),
      ) {
        log.info {
          field("user", user, User.serializer())
          "Test"
        }
      }
    }

    output.logFields shouldBe
        """
          "user":null
        """
            .trimIndent()
    output.contextFields shouldContainExactly mapOf("userInContext" to JsonNull)
  }

  @Test
  fun `non-serializable object falls back to toString`() {
    data class NonSerializableUser(val id: Long, val name: String)

    val user = NonSerializableUser(id = 1, name = "John Doe")

    val output = captureLogOutput {
      log.info {
        field("user", user)
        "Test"
      }
    }

    output.logFields shouldBe
        """
          "user":"NonSerializableUser(id=1, name=John Doe)"
        """
            .trimIndent()
  }

  @Test
  fun `duplicate field keys only includes the first field`() {
    val output = captureLogOutput {
      log.info {
        field("duplicateKey", "value1")
        field("duplicateKey", "value2")
        field("duplicateKey", "value3")
        "Test"
      }
    }

    output.logFields shouldBe
        """
          "duplicateKey":"value1"
        """
            .trimIndent()
  }

  @Test
  fun `null field value is allowed`() {
    val nullValue: String? = null

    val output = captureLogOutput {
      log.info {
        field("key", nullValue)
        "Test"
      }
    }

    output.logFields shouldBe
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
        val output = captureLogOutput {
          log.info {
            rawJsonField("user", userJson, validJson = assumeValidJson)
            "Test"
          }
        }

        output.logFields shouldBe
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

    val output = captureLogOutput {
      log.info {
        rawJsonField("user", invalidJson)
        "Test"
      }
    }

    output.logFields shouldBe
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

    val output = captureLogOutput {
      log.info {
        rawJsonField("user", invalidJson, validJson = true)
        "Test"
      }
    }

    output.logFields shouldBe
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

    val output = captureLogOutput {
      log.info {
        rawJsonField("user", jsonWithNewlines)
        "Test"
      }
    }

    output.logFields shouldBe
        """
          "user":{"id":1,"name":"John Doe"}
        """
            .trimIndent()
  }

  @Test
  fun `addPreconstructedField allows adding a previously constructed field to the log`() {
    val existingField = field("key", "value")

    val output = captureLogOutput {
      log.info {
        existingField(existingField)
        "Test"
      }
    }

    output.logFields shouldBe
        """
          "key":"value"
        """
            .trimIndent()
  }
}
