package no.liflig.logging

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private val log = Logger {}

class LoggingContextTest {
  @Test
  fun `field from logging context is included in log`() {
    val logFields = captureLogFields {
      withLoggingContext(
          field("key", "value"),
      ) {
        log.info { "Test" }
      }
    }

    logFields shouldBe
        """
          "key":"value"
        """
            .trimIndent()
  }

  @Test
  fun `logging context applies to all logs in scope`() {
    val logFields = arrayOfNulls<String>(2)

    withLoggingContext(
        field("key", "value"),
    ) {
      logFields[0] = captureLogFields { log.info { "Test" } }
      logFields[1] = captureLogFields { log.info { "Test 2" } }
    }

    logFields.forEach {
      it shouldBe
          """
            "key":"value"
          """
              .trimIndent()
    }
  }

  @Test
  fun `rawJsonField works with logging context`() {
    val userJson = """{"id":1,"name":"John Doe"}"""

    val logFields = captureLogFields {
      withLoggingContext(
          rawJsonField("user", userJson),
      ) {
        log.info { "Test" }
      }
    }

    logFields shouldBe
        """
          "user":${userJson}
        """
            .trimIndent()
  }

  @Test
  fun `logging context does not apply to logs outside scope`() {
    withLoggingContext(
        field("key", "value"),
    ) {
      log.info { "Inside scope" }
    }

    val logFields = captureLogFields { log.info { "Outside scope" } }
    logFields shouldBe ""
  }

  @Test
  fun `multiple context fields combined with log event field have expected order`() {
    val logFields = captureLogFields {
      withLoggingContext(
          field("contextField1", "value"),
          field("contextField2", "value"),
      ) {
        log.info {
          addField("logEventField", "value")
          "Test"
        }
      }
    }

    logFields shouldBe
        """
          "logEventField":"value","contextField1":"value","contextField2":"value"
        """
            .trimIndent()
  }

  @Test
  fun `duplicate context field keys only includes the newest fields`() {
    var fieldsFromInnerContext: String? = null
    // We want to verify that after exiting the inner logging context, the fields from the outer
    // context are used again
    var fieldsFromOuterContext: String? = null

    withLoggingContext(
        field("duplicateKey", "outer"),
    ) {
      withLoggingContext(
          field("duplicateKey", "inner1"),
          field("duplicateKey", "inner2"),
      ) {
        fieldsFromInnerContext = captureLogFields { log.info { "Test" } }
      }

      fieldsFromOuterContext = captureLogFields { log.info { "Test" } }
    }

    fieldsFromInnerContext shouldBe
        """
          "duplicateKey":"inner1"
        """
            .trimIndent()

    fieldsFromOuterContext shouldBe
        """
          "duplicateKey":"outer"
        """
            .trimIndent()
  }

  /**
   * Priority for duplicate keys in log fields is Log event -> Exception -> Context, so log event
   * field should override context field.
   */
  @Test
  fun `context field does not override duplicate log event field`() {
    val logFields = captureLogFields {
      withLoggingContext(
          field("duplicateKey", "from context"),
      ) {
        log.info {
          addField("duplicateKey", "from log event")
          "Test"
        }
      }
    }

    logFields shouldBe
        """
          "duplicateKey":"from log event"
        """
            .trimIndent()
  }
}
