package no.liflig.logging

import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

private val log = getLogger {}

internal class ExceptionWithLogFieldsTest {
  @Test
  fun `exception implementing WithLogFields has field included in log`() {
    val output = captureLogOutput {
      log.error(exceptionWithLogField("exceptionField", "value")) { "Test" }
    }

    output.logFields shouldBe
        """
          "exceptionField":"value"
        """
            .trimIndent()
  }

  @Test
  fun `ExceptionWithLogFields includes fields from logging context`() {
    val output = captureLogOutput {
      try {
        withLoggingContext(field("contextField", "value")) {
          throw exceptionWithLogField("exceptionField", "value")
        }
      } catch (e: Exception) {
        log.error(e) { "Test" }
      }
    }

    output.logFields shouldBe
        """
          "exceptionField":"value","contextField":"value"
        """
            .trimIndent()
    output.contextFields.shouldBeEmpty()
  }

  /**
   * The first test above verifies that logging context fields are included when the exception is
   * caught outside the context. We want to verify that this still works when the exception is
   * caught inside the context, and that this doesn't duplicate the fields from the context.
   */
  @Test
  fun `ExceptionWithLogFields still includes fields from logging context when caught within that context`() {
    val output = captureLogOutput {
      withLoggingContext(field("contextField", "value")) {
        try {
          throw exceptionWithLogField("exceptionField", "value")
        } catch (e: Exception) {
          log.error(e) { "Test" }
        }
      }
    }

    output.logFields shouldBe
        """
          "exceptionField":"value"
        """
            .trimIndent()
    output.contextFields shouldContainExactly mapOf("contextField" to JsonPrimitive("value"))
  }

  @Test
  fun `child exception that implements WithLogFields`() {
    val output = captureLogOutput {
      val exception =
          Exception(
              "Parent exception",
              exceptionWithLogField("childException", "value"),
          )
      log.error(exception) { "Test" }
    }

    output.logFields shouldBe
        """
          "childException":"value"
        """
            .trimIndent()
  }

  @Test
  fun `parent and child exceptions that both implement WithLogFields have their fields merged`() {
    val output = captureLogOutput {
      val exception =
          ExceptionWithLogFields(
              message = "Parent exception",
              logFields = listOf(field("parentField1", "value"), field("parentField2", "value")),
              cause = exceptionWithLogField("childField", "value"),
          )
      log.error(exception) { "Test" }
    }

    output.logFields shouldBe
        """
          "parentField1":"value","parentField2":"value","childField":"value"
        """
            .trimIndent()
  }

  @Test
  fun `exception log fields are placed between context and log event fields`() {
    val output = captureLogOutput {
      try {
        withLoggingContext(field("contextField", "value")) {
          throw exceptionWithLogField("exceptionField", "value")
        }
      } catch (e: Exception) {
        log.error(e) {
          field("logEventField", "value")
          "Test"
        }
      }
    }

    output.logFields shouldBe
        """
          "logEventField":"value","exceptionField":"value","contextField":"value"
        """
            .trimIndent()
  }

  @Test
  fun `exception with duplicate log fields only includes first field`() {
    val output = captureLogOutput {
      val exception =
          ExceptionWithLogFields(
              "Test",
              listOf(field("duplicateKey", "value1"), field("duplicateKey", "value2")),
              cause = exceptionWithLogField("duplicateKey", "value3"),
          )
      log.error(exception) { "Test" }
    }

    output.logFields shouldBe
        """
          "duplicateKey":"value1"
        """
            .trimIndent()
  }

  /**
   * Priority for duplicate keys in log fields is Log event -> Exception -> Context, so log event
   * field should override exception field.
   */
  @Test
  fun `exception log field does not override duplicate log event field`() {
    val output = captureLogOutput {
      log.error(exceptionWithLogField("duplicateKey", "from exception")) {
        field("duplicateKey", "from log event")
        "Test"
      }
    }

    output.logFields shouldBe
        """
          "duplicateKey":"from log event"
        """
            .trimIndent()
  }

  /**
   * Priority for duplicate keys in log fields is Log event -> Exception -> Context, so exception
   * log field should override context field.
   */
  @Test
  fun `exception log field overrides duplicate context field`() {
    val output = captureLogOutput {
      withLoggingContext(field("duplicateKey", "from context")) {
        log.error(exceptionWithLogField("duplicateKey", "from exception")) { "Test" }
      }
    }

    output.logFields shouldBe
        """
          "duplicateKey":"from exception"
        """
            .trimIndent()
  }

  @Test
  fun `serializable object field works on ExceptionWithLogFields`() {
    val event = Event(id = 1001, type = EventType.ORDER_PLACED)

    val exception =
        ExceptionWithLogFields(
            message = null,
            logFields = listOf(field("event", event)),
        )

    val output = captureLogOutput { log.error(exception) { "Test" } }

    output.logFields shouldBe
        """
          "event":{"id":1001,"type":"ORDER_PLACED"}
        """
            .trimIndent()
  }

  @Test
  fun `custom implementation of WithLogFields works`() {
    class CustomException : Exception(), WithLogFields {
      override val logFields =
          listOf(
              field("key1", "value1"),
              field("key2", "value2"),
          )
    }

    val output = captureLogOutput { log.error(cause = CustomException()) { "Test" } }

    output.logFields shouldBe
        """
          "key1":"value1","key2":"value2"
        """
            .trimIndent()
  }

  @Test
  fun `inheriting from ExceptionWithLogFields works`() {
    class CustomException :
        ExceptionWithLogFields(
            message = "Test",
            logFields = listOf(field("key", "value")),
        )

    val output = captureLogOutput { log.error(cause = CustomException()) { "Test" } }

    output.logFields shouldBe
        """
          "key":"value"
        """
            .trimIndent()
  }

  /** See comment in [LogBuilder.addFieldsFromCauseException]. */
  @Test
  fun `exception cause cycle should not cause infinite loop`() {
    class EvilException : Exception() {
      var settableCause: Throwable? = null

      override val cause: Throwable?
        get() = settableCause
    }

    val exception1 = EvilException()
    val exception2 = EvilException()
    val exception3 = EvilException()

    // Set up cause cycle
    exception1.settableCause = exception2
    exception2.settableCause = exception3
    exception3.settableCause = exception1

    log.info(exception1) { "Should not cause an infinite loop" }
  }
}

private fun exceptionWithLogField(key: String, value: String) =
    ExceptionWithLogFields(
        message = "Test exception",
        logFields = listOf(field(key, value)),
    )
