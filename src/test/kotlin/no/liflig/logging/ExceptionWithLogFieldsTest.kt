package no.liflig.logging

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private val log = getLogger {}

class ExceptionWithLogFieldsTest {
  @Test
  fun `exception implementing WithLogFields has field included in log`() {
    val logFields = captureLogFields {
      log.error {
        cause = exceptionWithLogField(field("errorCode", 60))
        "Test"
      }
    }

    logFields shouldBe
        """
          "errorCode":60
        """
            .trimIndent()
  }

  @Test
  fun `ExceptionWithLogFields includes fields from logging context`() {
    val logFields = captureLogFields {
      try {
        withLoggingContext(field("contextField1", "value"), field("contextField2", "value")) {
          throw exceptionWithLogField(field("exceptionField", "value"))
        }
      } catch (e: Exception) {
        log.error {
          cause = e
          "Test"
        }
      }
    }

    logFields shouldBe
        """
          "exceptionField":"value","contextField1":"value","contextField2":"value"
        """
            .trimIndent()
  }

  /**
   * The first test above verifies that logging context fields are included when the exception is
   * caught outside the context. We want to verify that this still works when the exception is
   * caught inside the context, and that this doesn't duplicate the fields from the context.
   */
  @Test
  fun `ExceptionWithLogFields still includes fields from logging context when caught within that context`() {
    val logFields = captureLogFields {
      withLoggingContext(field("contextField", "value")) {
        try {
          throw exceptionWithLogField(field("exceptionField", "value"))
        } catch (e: Exception) {
          log.error {
            cause = e
            "Test"
          }
        }
      }
    }

    logFields shouldBe
        """
          "exceptionField":"value","contextField":"value"
        """
            .trimIndent()
  }

  @Test
  fun `child exception that implements WithLogFields`() {
    val logFields = captureLogFields {
      log.error {
        cause =
            Exception(
                "Parent exception",
                exceptionWithLogField(field("childException", true)),
            )
        "Test"
      }
    }

    logFields shouldBe
        """
          "childException":true
        """
            .trimIndent()
  }

  @Test
  fun `parent and child exceptions that both implement WithLogFields have their fields merged`() {
    val logFields = captureLogFields {
      log.error {
        cause =
            ExceptionWithLogFields(
                message = "Parent exception",
                logFields =
                    listOf(
                        field("parentField1", "value"),
                        field("parentField2", "value"),
                    ),
                cause = exceptionWithLogField(field("childField", "value")),
            )
        "Test"
      }
    }

    logFields shouldBe
        """
          "parentField1":"value","parentField2":"value","childField":"value"
        """
            .trimIndent()
  }

  @Test
  fun `exception log fields are placed between context and log event fields`() {
    val logFields = captureLogFields {
      withLoggingContext(field("contextField", "value")) {
        log.error {
          cause = exceptionWithLogField(field("exceptionField", "value"))
          addField("logEventField", "value")
          "Test"
        }
      }
    }

    logFields shouldBe
        """
          "logEventField":"value","exceptionField":"value","contextField":"value"
        """
            .trimIndent()
  }

  @Test
  fun `exception with duplicate log fields only includes first field`() {
    val logFields = captureLogFields {
      log.error {
        cause =
            ExceptionWithLogFields(
                "Test",
                listOf(
                    field("duplicateKey", "value1"),
                    field("duplicateKey", "value2"),
                ),
                cause = exceptionWithLogField(field("duplicateKey", "value3")),
            )
        "Test"
      }
    }

    logFields shouldBe
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
    val logFields = captureLogFields {
      log.error {
        cause = exceptionWithLogField(field("duplicateKey", "from exception"))
        addField("duplicateKey", "from log event")
        "Test"
      }
    }

    logFields shouldBe
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
    val logFields = captureLogFields {
      withLoggingContext(field("duplicateKey", "from context")) {
        log.error {
          cause = exceptionWithLogField(field("duplicateKey", "from exception"))
          "Test"
        }
      }
    }

    logFields shouldBe
        """
          "duplicateKey":"from exception"
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

    val logFields = captureLogFields {
      log.error {
        cause = CustomException()
        "Test"
      }
    }

    logFields shouldBe
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

    val logFields = captureLogFields {
      log.error {
        cause = CustomException()
        "Test"
      }
    }

    logFields shouldBe
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

    log.info {
      cause = exception1
      "Should not cause an infinite loop"
    }
  }
}

private fun exceptionWithLogField(field: LogField) =
    ExceptionWithLogFields(
        message = "Test exception",
        logFields = listOf(field),
    )
