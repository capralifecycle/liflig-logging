package no.liflig.logging

import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.math.BigDecimal
import java.net.URI
import java.net.URL
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonNull
import no.liflig.logging.testutils.Event
import no.liflig.logging.testutils.EventType
import no.liflig.logging.testutils.TestCase
import no.liflig.logging.testutils.captureLogOutput
import no.liflig.logging.testutils.parameterizedTest

private val log = getLogger()

internal class LogFieldTest {
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
    val event = Event(id = 1001, type = EventType.ORDER_PLACED)

    val output = captureLogOutput {
      log.info {
        field("event", event)
        "Test"
      }
    }

    output.logFields shouldBe
        """
          "event":{"id":1001,"type":"ORDER_PLACED"}
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
    val event: Event? = null

    val output = captureLogOutput {
      withLoggingContext(
          // Test `field` in context and on log, since these are different functions
          field("eventInContext", event, Event.serializer()),
      ) {
        log.info {
          field("event", event, Event.serializer())
          "Test"
        }
      }
    }

    output.logFields shouldBe
        """
          "event":null
        """
            .trimIndent()
    output.contextFields shouldContainExactly mapOf("eventInContext" to JsonNull)
  }

  /**
   * Previously, we allowed passing an explicit serializer to log field functions as an optional
   * third parameter. But since these log field functions take reified type parameters, they would
   * not work in non-inline generic contexts. However, we don't need reified type parameters when
   * the user passes their own serializer, so this was a needless restriction. We solved it by using
   * a separate function overload for the custom serializer variant instead of an optional
   * parameter.
   */
  @Test
  fun `custom serializer without reified type parameter`() {
    fun <T> genericLogFunction(obj: T, serializer: SerializationStrategy<T>) {
      log.info {
        field("object", obj, serializer)
        "Test"
      }
    }

    val output = captureLogOutput {
      genericLogFunction(Event(id = 1001, type = EventType.ORDER_PLACED), Event.serializer())
    }

    output.logFields shouldBe
        """
          "object":{"id":1001,"type":"ORDER_PLACED"}
        """
            .trimIndent()
  }

  @Test
  fun `non-serializable object falls back to toString`() {
    data class NonSerializableEvent(val id: Long, val type: String)

    val event = NonSerializableEvent(id = 1001, type = "ORDER_UPDATED")

    val output = captureLogOutput {
      log.info {
        field("event", event)
        "Test"
      }
    }

    output.logFields shouldBe
        """
          "event":"NonSerializableEvent(id=1001, type=ORDER_UPDATED)"
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

  /**
   * We want to test handling of raw JSON by:
   * - [LogBuilder.rawJsonField] method for adding log field to a log
   * - [rawJsonField] function for creating log fields (for `withLoggingContext`)
   * - [rawJson] function for creating a `JsonElement` from a raw JSON string
   *
   * So we create a test case for each of these, and run each test case through every test of raw
   * JSON handling.
   */
  class RawJsonTestCase(
      override val name: String,
      val addRawJsonField:
          (logBuilder: LogBuilder, key: String, json: String, validJson: Boolean) -> Unit,
  ) : TestCase

  val rawJsonTestCases: List<RawJsonTestCase> =
      listOf(
          RawJsonTestCase("rawJsonField LogBuilder method") { logBuilder, key, json, validJson ->
            logBuilder.rawJsonField(key, json, validJson)
          },
          RawJsonTestCase("rawJsonField function") { logBuilder, key, json, validJson ->
            val field = rawJsonField(key, json, validJson)
            logBuilder.addField(field)
          },
          RawJsonTestCase("rawJson function") { logBuilder, key, json, validJson ->
            logBuilder.field(key, value = rawJson(json, validJson))
          },
      )

  @Test
  fun `raw JSON field works for valid JSON`() {
    parameterizedTest(rawJsonTestCases) { test ->
      val eventJson = """{"id":1001,"type":"ORDER_UPDATED"}"""

      // The above JSON should work both for validJson = true and validJson = false
      for (assumeValidJson in listOf(true, false)) {
        withClue({ "assumeValidJson = ${assumeValidJson}" }) {
          val output = captureLogOutput {
            log.info {
              test.addRawJsonField(this, "event", eventJson, assumeValidJson)
              "Test"
            }
          }

          output.logFields shouldBe
              """
              "event":${eventJson}
            """
                  .trimIndent()
        }
      }
    }
  }

  @Test
  fun `raw JSON field escapes invalid JSON by default`() {
    parameterizedTest(rawJsonTestCases) { test ->
      val invalidJson = """{"id":1"""

      val output = captureLogOutput {
        log.info {
          test.addRawJsonField(this, "event", invalidJson, false)
          "Test"
        }
      }

      output.logFields shouldBe
          """
          "event":"{\"id\":1"
        """
              .trimIndent()
    }
  }

  /**
   * When the user sets validJson = true on rawJsonField, they promise that the given JSON is valid,
   * so it should be passed on as-is. We therefore verify here that no validity checks are made on
   * the given JSON, although the user _should_ never pass invalid JSON to rawJsonField like this.
   */
  @Test
  fun `raw JSON field does not escape invalid JSON when validJson is set to true`() {
    parameterizedTest(rawJsonTestCases) { test ->
      val invalidJson = """{"id":1"""

      val output = captureLogOutput {
        log.info {
          test.addRawJsonField(this, "event", invalidJson, true)
          "Test"
        }
      }

      output.logFields shouldBe
          """
          "event":${invalidJson}
        """
              .trimIndent()
    }
  }

  @Test
  fun `raw JSON field re-encodes JSON when it contains newlines`() {
    parameterizedTest(rawJsonTestCases) { test ->
      val jsonWithNewlines =
          """
          {
            "id": 1001,
            "type": "ORDER_UPDATED"
          }
        """
              .trimIndent()

      val output = captureLogOutput {
        log.info {
          test.addRawJsonField(this, "event", jsonWithNewlines, false)
          "Test"
        }
      }

      output.logFields shouldBe
          """
          "event":{"id":1001,"type":"ORDER_UPDATED"}
        """
              .trimIndent()
    }
  }

  /**
   * [kotlinx.serialization.json.JsonUnquotedLiteral], which we use in [rawJson], throws if given a
   * literal "null" string. So we have to check for "null" and instead return [JsonNull] in that
   * case - we want to test that this works.
   */
  @Test
  fun `passing a JSON literal null to rawJson works`() {
    val value = rawJson("null")
    value shouldBe JsonNull
  }

  val validJsonTestCases =
      listOf(
          // Valid literals
          "\"string\"",
          "true",
          "false",
          "null",
          // Valid numbers (as per the JSON spec: https://json.org)
          "0",
          "1",
          "123456789",
          "0.0123456789",
          "123456789.0123456789",
          "0e2",
          "0E2",
          "2e2",
          "2E2",
          "0.1e2",
          "0.1E2",
          "2e+5",
          "2e-5",
          "9e123456789",
      )

  @Test
  fun `validateRawJson accepts valid JSON`() {
    parameterizedTest(validJsonTestCases) { validJson ->
      val isValid: Boolean =
          validateRawJson(
              validJson,
              isValid = false,
              onValidJson = { true },
              onInvalidJson = { false },
          )
      isValid.shouldBeTrue()
    }
  }

  val invalidJsonTestCases =
      listOf(
          // Unquoted string
          "test",
          // Object with unquoted string field value
          """{"test":test}""",
          // Unquoted string with digits
          "1test1",
          // Blank string
          "",
          // All-whitespace string
          "     ",
      )

  @Test
  fun `validateRawJson rejects invalid JSON`() {
    parameterizedTest(invalidJsonTestCases) { invalidJson ->
      val isValid: Boolean =
          validateRawJson(
              invalidJson,
              isValid = false,
              onValidJson = { true },
              onInvalidJson = { false },
          )
      isValid.shouldBeFalse()
    }
  }

  @Test
  fun `addField allows adding a previously constructed field to the log`() {
    val existingField = field("key", "value")

    val output = captureLogOutput {
      log.info {
        addField(existingField)
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
  fun `addFields allows adding a list of previously constructed fields to the log`() {
    val existingFields =
        listOf(
            field("key1", "value1"),
            field("key2", "value2"),
        )

    val output = captureLogOutput {
      log.info {
        addFields(existingFields)
        "Test"
      }
    }

    output.logFields shouldBe
        """
          "key1":"value1","key2":"value2"
        """
            .trimIndent()
  }

  @Suppress("ReplaceCallWithBinaryOperator") // We want to use .equals explicitly here
  @Test
  fun `LogField equals, toString and hashCode work as expected`() {
    val stringField = field("key", "value")
    val stringField2 = field("key", "value")

    stringField.equals(stringField2).shouldBeTrue()
    stringField.hashCode() shouldBe stringField2.hashCode()
    stringField.toString() shouldBe "key=value"
    stringField.toString() shouldBe stringField2.toString()

    val objectField = field("key", Event(id = 1001, type = EventType.ORDER_PLACED))

    stringField.equals(objectField).shouldBeFalse()
    stringField.hashCode() shouldNotBe objectField.hashCode()

    val objectAsStringField = field("key", """{"id":1001,"type":"ORDER_PLACED"}""")

    objectField.equals(objectAsStringField).shouldBeTrue()
    objectField.hashCode() shouldBe objectAsStringField.hashCode()
    objectField.toString() shouldBe """key={"id":1001,"type":"ORDER_PLACED"}"""
    objectField.toString() shouldBe objectAsStringField.toString()
  }

  @Test
  fun `special-case types`() {
    val output = captureLogOutput {
      log.info {
        field("instant", Instant.parse("2024-12-09T16:38:23Z"))
        field("uri", URI.create("https://example.com"))
        @Suppress("DEPRECATION") field("url", URL("https://example.com"))
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
}
