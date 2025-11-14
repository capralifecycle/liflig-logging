package no.liflig.logging

import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.net.URI
import java.net.URL
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import no.liflig.logging.testutils.Event
import no.liflig.logging.testutils.EventType
import no.liflig.logging.testutils.captureLogOutput
import no.liflig.logging.testutils.runTestCases
import tools.jackson.databind.ObjectMapper

private val log = getLogger()

/**
 * This library provides 2 ways to construct log fields:
 * - The [field] top-level function, returning a [LogField] (designed for [withLoggingContext])
 * - The [LogBuilder.field] method, which constructs the field in-place on the log event
 *
 * We want to test both of these, with a variety of different inputs. To do this systematically, we
 * make each log field test a parameterized test (see [runTestCases]), so every test runs with both
 * ways of creating log fields.
 */
internal enum class LogFieldTestCase {
  LOGBUILDER_METHOD,
  TOP_LEVEL_CONSTRUCTOR;

  inline fun <reified ValueT> addField(logBuilder: LogBuilder, key: String, value: ValueT) {
    when (this) {
      LOGBUILDER_METHOD -> {
        logBuilder.field(key, value)
      }
      TOP_LEVEL_CONSTRUCTOR -> {
        logBuilder.addField(field(key, value))
      }
    }
  }

  fun <ValueT : Any> addFieldWithSerializer(
      logBuilder: LogBuilder,
      key: String,
      value: ValueT?,
      serializer: SerializationStrategy<ValueT>,
  ) {
    when (this) {
      LOGBUILDER_METHOD -> {
        logBuilder.field(key, value, serializer)
      }
      TOP_LEVEL_CONSTRUCTOR -> {
        logBuilder.addField(field(key, value, serializer))
      }
    }
  }
}

/**
 * Same as [LogFieldTestCase], but for [rawJsonField]/[LogBuilder.rawJsonField], and we also want to
 * test the [rawJson] function for creating log field values from raw JSON.
 */
internal enum class RawJsonTestCase {
  LOGBUILDER_METHOD,
  TOP_LEVEL_CONSTRUCTOR,
  RAW_JSON_VALUE_FUNCTION;

  fun addRawJsonField(logBuilder: LogBuilder, key: String, json: String, validJson: Boolean) {
    when (this) {
      LOGBUILDER_METHOD -> {
        logBuilder.rawJsonField(key, json, validJson)
      }
      TOP_LEVEL_CONSTRUCTOR -> {
        logBuilder.addField(rawJsonField(key, json, validJson))
      }
      RAW_JSON_VALUE_FUNCTION -> {
        logBuilder.addField(field(key, rawJson(json, validJson)))
      }
    }
  }
}

internal class LogFieldTest {
  @Test
  fun `basic log field test`() {
    runTestCases(LogFieldTestCase.entries) { test ->
      val output = captureLogOutput {
        log.info {
          test.addField(this, "key", "value")
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

  @Test
  fun `log field with Serializable object`() {
    runTestCases(LogFieldTestCase.entries) { test ->
      val event = Event(id = 1000, type = EventType.ORDER_PLACED)

      val output = captureLogOutput {
        log.info {
          test.addField(this, "event", event)
          "Test"
        }
      }

      output.logFields shouldBe
          """
          "event":{"id":1000,"type":"ORDER_PLACED"}
          """
              .trimIndent()
    }
  }

  @Test
  fun `multiple log fields`() {
    runTestCases(LogFieldTestCase.entries) { test ->
      val output = captureLogOutput {
        log.info {
          test.addField(this, "first", true)
          test.addField(this, "second", listOf("value1", "value2"))
          test.addField(this, "third", 10)
          "Test"
        }
      }

      output.logFields shouldBe
          """
          "first":true,"second":["value1","value2"],"third":10
          """
              .trimIndent()
    }
  }

  @Test
  fun `explicit serializer`() {
    runTestCases(LogFieldTestCase.entries) { test ->
      val prefixSerializer =
          object : SerializationStrategy<String> {
            override val descriptor = String.serializer().descriptor

            override fun serialize(encoder: Encoder, value: String) {
              encoder.encodeString("Prefix: ${value}")
            }
          }

      val output = captureLogOutput {
        log.info {
          test.addFieldWithSerializer(this, "key", "value", serializer = prefixSerializer)
          "Test"
        }
      }

      output.logFields shouldBe
          """
          "key":"Prefix: value"
          """
              .trimIndent()
    }
  }

  /**
   * On [field] and [LogBuilder.field], we place a non-nullable `Any` bound on the `ValueT` type
   * parameter, and type the `value` parameter as `ValueT?`. This is to support passing in a custom
   * serializer for a type, but still allow passing in `null` for the value (since this is handled
   * before checking the serializer). This test checks that this works.
   */
  @Test
  fun `explicit serializer with nullable value`() {
    runTestCases(LogFieldTestCase.entries) { test ->
      val event: Event? = null

      val output = captureLogOutput {
        log.info {
          test.addFieldWithSerializer(this, "event", event, Event.serializer())
          "Test"
        }
      }

      output.logFields shouldBe
          """
          "event":null
          """
              .trimIndent()
    }
  }

  @Test
  fun `explicit serializer falls back to toString on exception`() {
    runTestCases(LogFieldTestCase.entries) { test ->
      val alwaysFailingSerializer =
          object : SerializationStrategy<Event> {
            override val descriptor = Event.serializer().descriptor

            override fun serialize(encoder: Encoder, value: Event) {
              throw Exception("Serialization failed")
            }
          }

      val event = Event(id = 1234, type = EventType.ORDER_PLACED)

      val output = captureLogOutput {
        log.info {
          test.addFieldWithSerializer(this, "event", event, alwaysFailingSerializer)
          "Test"
        }
      }

      output.logFields shouldBe
          """
          "event":"Event(id=1234, type=ORDER_PLACED)"
          """
              .trimIndent()
    }
  }

  /**
   * When calling [field]/[LogBuilder.field] with a serializer, the serializer should not be invoked
   * if the log field value is `null`. We can verify that by making a serializer that always fails
   * if invoked.
   */
  @Test
  fun `explicit serializer with nullable value does not call serializer`() {
    runTestCases(LogFieldTestCase.entries) { test ->
      val alwaysFailingSerializer =
          object : SerializationStrategy<String> {
            override val descriptor = String.serializer().descriptor

            override fun serialize(encoder: Encoder, value: String) {
              // Use Throwable, so it will not be caught and use toString fallback
              throw Throwable("Should not be called")
            }
          }

      val value: String? = null

      val output = captureLogOutput {
        log.info {
          test.addFieldWithSerializer(this, "key", value, alwaysFailingSerializer)
          "Test"
        }
      }

      output.logFields shouldBe
          """
          "key":null
          """
              .trimIndent()
    }
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
    runTestCases(LogFieldTestCase.entries) { test ->
      fun <T> genericLogFunction(obj: T, serializer: SerializationStrategy<T>) {
        log.info {
          test.addFieldWithSerializer(this, "object", obj, serializer)
          "Test"
        }
      }

      val output = captureLogOutput {
        genericLogFunction(Event(id = 1000, type = EventType.ORDER_PLACED), Event.serializer())
      }

      output.logFields shouldBe
          """
          "object":{"id":1000,"type":"ORDER_PLACED"}
          """
              .trimIndent()
    }
  }

  @Test
  fun `non-serializable object falls back to toString`() {
    runTestCases(LogFieldTestCase.entries) { test ->
      data class NonSerializableEvent(val id: Long, val type: String)

      val event = NonSerializableEvent(id = 1000, type = "ORDER_UPDATED")

      val output = captureLogOutput {
        log.info {
          test.addField(this, "event", event)
          "Test"
        }
      }

      output.logFields shouldBe
          """
          "event":"NonSerializableEvent(id=1000, type=ORDER_UPDATED)"
          """
              .trimIndent()
    }
  }

  @Test
  fun `duplicate field keys only includes the first field`() {
    runTestCases(LogFieldTestCase.entries) { test ->
      val output = captureLogOutput {
        log.info {
          test.addField(this, "duplicateKey", "value1")
          test.addField(this, "duplicateKey", "value2")
          test.addField(this, "duplicateKey", "value3")
          "Test"
        }
      }

      output.logFields shouldBe
          """
          "duplicateKey":"value1"
          """
              .trimIndent()
    }
  }

  @Test
  fun `null field value is allowed`() {
    runTestCases(LogFieldTestCase.entries) { test ->
      val nullValue: String? = null

      val output = captureLogOutput {
        log.info {
          test.addField(this, "key", nullValue)
          "Test"
        }
      }

      output.logFields shouldBe
          """
          "key":null
          """
              .trimIndent()
    }
  }

  @Test
  fun `raw JSON field works for valid JSON`() {
    runTestCases(RawJsonTestCase.entries) { test ->
      val eventJson = """{"id":1000,"type":"ORDER_UPDATED"}"""

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
    runTestCases(RawJsonTestCase.entries) { test ->
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
    runTestCases(RawJsonTestCase.entries) { test ->
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
    runTestCases(RawJsonTestCase.entries) { test ->
      val jsonWithNewlines =
          """
          {
            "id": 1000,
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
          "event":{"id":1000,"type":"ORDER_UPDATED"}
          """
              .trimIndent()
    }
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
    runTestCases(validJsonTestCases) { validJson ->
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
    runTestCases(invalidJsonTestCases) { invalidJson ->
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

  /**
   * [kotlinx.serialization.json.JsonUnquotedLiteral], which we use in [rawJson], throws if given a
   * literal "null" string. So we have to check for "null" and instead return [JsonNull] in that
   * case - we want to test that this works.
   */
  @Test
  fun `passing a JSON null literal to rawJson works`() {
    val rawJsonNull = rawJson("null")
    val serializedValue = Json.encodeToString(rawJsonNull)
    serializedValue.shouldBe("null")
  }

  @Test
  fun `RawJson toString implementations work as expected`() {
    val validRawJson = rawJson("""["value1","value2","value3"]""")
    validRawJson.shouldBeInstanceOf<ValidRawJson>()
    validRawJson.toString().shouldBe("""["value1","value2","value3"]""")

    val invalidJson = rawJson("""["missing closing bracket",""")
    invalidJson.shouldBeInstanceOf<NotValidJson>()
    invalidJson.toString().shouldBe("""["missing closing bracket",""")
  }

  @Test
  fun `addFields allows adding a collection of previously constructed fields to the log`() {
    val existingFields: Collection<LogField> =
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

    val objectField = field("key", Event(id = 1000, type = EventType.ORDER_PLACED))

    stringField.equals(objectField).shouldBeFalse()
    stringField.hashCode() shouldNotBe objectField.hashCode()

    val objectAsStringField = field("key", """{"id":1000,"type":"ORDER_PLACED"}""")

    objectField.equals(objectAsStringField).shouldBeTrue()
    objectField.hashCode() shouldBe objectAsStringField.hashCode()
    objectField.toString() shouldBe """key={"id":1000,"type":"ORDER_PLACED"}"""
    objectField.toString() shouldBe objectAsStringField.toString()
  }

  @Test
  fun `special-case types`() {
    runTestCases(LogFieldTestCase.entries) { test ->
      val output = captureLogOutput {
        log.info {
          test.addField(this, "instant", Instant.parse("2024-12-09T16:38:23Z"))
          test.addField(this, "uri", URI.create("https://example.com"))
          @Suppress("DEPRECATION") test.addField(this, "url", URL("https://example.com"))
          test.addField(this, "uuid", UUID.fromString("3638dd04-d196-41ad-8b15-5188a22a6ba4"))
          test.addField(this, "bigDecimal", BigDecimal("100.0"))
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

  private val jacksonObjectMapper = ObjectMapper()

  @Test
  fun `ValidRawJson is serializable with Jackson`() {
    val validRawJson = rawJson("""{"test":true}""")
    validRawJson.shouldBeInstanceOf<ValidRawJson>()

    val serializedValue = jacksonObjectMapper.writeValueAsString(validRawJson)
    serializedValue.shouldBe("""{"test":true}""")
  }

  @Test
  fun `NotValidJson is serializable with Jackson`() {
    val invalidJson = rawJson("""{"key":valueWithoutQuotes}""")
    invalidJson.shouldBeInstanceOf<NotValidJson>()

    val serializedValue = jacksonObjectMapper.writeValueAsString(invalidJson)
    serializedValue.shouldBe(
        """
        "{\"key\":valueWithoutQuotes}"
        """
            .trimIndent(),
    )
  }
}
