package no.liflig.logging

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test
import no.liflig.logging.testutils.Event
import no.liflig.logging.testutils.EventType
import no.liflig.logging.testutils.captureLogOutput

private val log = getLogger()

internal class ExceptionWithLoggingContextTest {
  @Test
  fun `ExceptionWithLoggingContext has its fields included in log`() {
    val output = captureLogOutput {
      log.error(
          cause = ExceptionWithLoggingContext(field("exceptionField", "value")),
      ) {
        "Test"
      }
    }

    output.logFields shouldBe
        """
        "exceptionField":"value"
        """
            .trimIndent()
  }

  @Test
  fun `ExceptionWithLoggingContext includes fields from logging context`() {
    val output = captureLogOutput {
      try {
        withLoggingContext(field("contextField", "value")) {
          throw ExceptionWithLoggingContext(field("exceptionField", "value"))
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
  fun `ExceptionWithLoggingContext still includes fields from logging context when caught within that context`() {
    val output = captureLogOutput {
      withLoggingContext(field("contextField", "value")) {
        try {
          throw ExceptionWithLoggingContext(field("exceptionField", "value"))
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
    output.contextFields shouldContainExactly mapOf("contextField" to "value")
  }

  @Test
  fun `ExceptionWithLoggingContext includes all fields from multiple levels of logging context`() {
    val output = captureLogOutput {
      try {
        // Let exception propagate through 2 levels of withLoggingContext, to test that fields from
        // all contexts are included
        withLoggingContext(
            field("context1", "value1"),
        ) {
          withLoggingContext(
              field("context2_key1", "value2"),
              field("context2_key2", "value3"),
          ) {
            throw ExceptionWithLoggingContext(field("exceptionField", "value4"))
          }
        }
      } catch (e: Exception) {
        log.error(e) { "Test" }
      }
    }

    output.logFields shouldBe
        """
        "exceptionField":"value4","context2_key1":"value2","context2_key2":"value3","context1":"value1"
        """
            .trimIndent()
    output.contextFields.shouldBeEmpty()
  }

  @Test
  fun `fields from nested ExceptionWithLoggingContext are still included on log`() {
    val output = captureLogOutput {
      val exception =
          Exception(
              "Parent exception",
              ExceptionWithLoggingContext(field("childException", "value")),
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
  fun `fields from suppressed ExceptionWithLoggingContext are still included on log`() {
    val output = captureLogOutput {
      val exception = Exception("Parent exception")
      exception.addSuppressed(ExceptionWithLoggingContext(field("suppressedException", "value")))

      log.error(exception) { "Test" }
    }

    output.logFields shouldBe
        """
        "suppressedException":"value"
        """
            .trimIndent()
  }

  @Test
  fun `parent and child exceptions that are both ExceptionWithLoggingContext have their fields merged`() {
    val output = captureLogOutput {
      val exception =
          ExceptionWithLoggingContext(
              "Parent exception",
              field("parentField1", "value"),
              field("parentField2", "value"),
              cause = ExceptionWithLoggingContext(field("childField", "value")),
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
          throw ExceptionWithLoggingContext(field("exceptionField", "value"))
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
          ExceptionWithLoggingContext(
              "Test",
              field("duplicateKey", "value1"),
              field("duplicateKey", "value2"),
              cause = ExceptionWithLoggingContext(field("duplicateKey", "value3")),
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
      log.error(ExceptionWithLoggingContext(field("duplicateKey", "from exception"))) {
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
        log.error(
            cause = ExceptionWithLoggingContext(field("duplicateKey", "from exception")),
        ) {
          "Test"
        }
      }
    }

    output.logFields shouldBe
        """
        "duplicateKey":"from exception"
        """
            .trimIndent()
  }

  @Test
  fun `serializable object field works on ExceptionWithLoggingContext`() {
    val event = Event(id = 1000, type = EventType.ORDER_PLACED)

    val output = captureLogOutput {
      log.error(
          cause = ExceptionWithLoggingContext(field("event", event)),
      ) {
        "Test"
      }
    }

    output.logFields shouldBe
        """
        "event":{"id":1000,"type":"ORDER_PLACED"}
        """
            .trimIndent()
  }

  @Test
  fun `custom implementation of HasLoggingContext works`() {
    class CustomException : Exception(), HasLoggingContext {
      override val logFields: Collection<LogField> =
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
  fun `inheriting from ExceptionWithLoggingContext works`() {
    class CustomException :
        ExceptionWithLoggingContext(
            "Test",
            field("key", "value"),
        )

    val output = captureLogOutput { log.error(cause = CustomException()) { "Test" } }

    output.logFields shouldBe
        """
        "key":"value"
        """
            .trimIndent()
  }

  @Test
  fun `vararg overload works`() {
    val cause = Exception("Cause")
    val exception =
        ExceptionWithLoggingContext(
            "Test",
            field("key1", "value1"),
            field("key2", "value2"),
            cause = cause,
        )

    exception.message shouldBe "Test"
    exception.logFields() shouldBe listOf(field("key1", "value1"), field("key2", "value2"))
    exception.cause shouldBe cause
  }

  @Test
  fun `overload without message works`() {
    val cause = IllegalArgumentException("Invalid input")
    val exception =
        ExceptionWithLoggingContext(
            listOf(field("key", "value")),
            cause,
        )

    exception.message shouldBe "IllegalArgumentException: Invalid input"
    exception.logFields() shouldBe listOf(field("key", "value"))
    exception.cause shouldBe cause
  }

  @Test
  fun `vararg overload without message works`() {
    val cause = IllegalArgumentException("Invalid input")
    val exception =
        ExceptionWithLoggingContext(
            field("key1", "value1"),
            field("key2", "value2"),
            cause = cause,
        )

    exception.message shouldBe "IllegalArgumentException: Invalid input"
    exception.logFields() shouldBe listOf(field("key1", "value1"), field("key2", "value2"))
    exception.cause shouldBe cause
  }

  @Test
  fun `overload without message, cause or log fields works`() {
    val exception = ExceptionWithLoggingContext()
    exception.message.shouldBeNull()
    exception.cause.shouldBeNull()
    exception.logFields().shouldBeEmpty()
  }

  @Test
  fun `cause exception without message works`() {
    val cause = IllegalArgumentException()
    val exception = ExceptionWithLoggingContext(cause = cause)

    exception.message shouldBe "IllegalArgumentException"
    exception.cause shouldBe cause
  }

  @Test
  fun `cause exception without class name works`() {
    val cause = object : Exception() {}
    val exception = ExceptionWithLoggingContext(cause = cause)

    exception.message.shouldBeNull()
    exception.cause shouldBe cause
  }

  @Test
  fun `cause exception without class name but with message works`() {
    val cause = object : Exception("Something went wrong") {}
    val exception = ExceptionWithLoggingContext(cause = cause)

    exception.message shouldBe "Something went wrong"
    exception.cause shouldBe cause
  }

  @Test
  fun `extending ExceptionWithLoggingContext and overriding message works`() {
    class CustomException : ExceptionWithLoggingContext(listOf(field("key", "value"))) {
      override val message = "Custom message"
    }

    val exception = CustomException()
    exception.message shouldBe "Custom message"
    exception.logFields() shouldBe listOf(field("key", "value"))
  }

  @Test
  fun `extending ExceptionWithLoggingContext with varargs and overriding message works`() {
    class CustomException :
        ExceptionWithLoggingContext(
            field("key1", "value1"),
            field("key2", "value2"),
        ) {
      override val message = "Custom message"
    }

    val exception = CustomException()
    exception.message shouldBe "Custom message"
    exception.logFields() shouldBe listOf(field("key1", "value1"), field("key2", "value2"))
  }

  /** See comments in [traverseExceptionTree]. */
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

    /**
     * We test that both logging the exception and calling `traverseExceptionTree` do not cause
     * infinite loops (we want to test both, since we use a different exception traversal mechanism
     * when using Logback, see [LogEvent.handlesExceptionTreeTraversal]).
     */
    log.info(exception1) { "Should not cause an infinite loop" }

    val traversed = mutableSetOf<Throwable>()
    traverseExceptionTree(root = exception1) { exception -> traversed.add(exception) }

    traversed shouldContainExactly listOf(exception1, exception2, exception3)
  }

  /** See comments in [traverseExceptionTree]. */
  @Test
  fun `cycle in cause and suppressed exceptions should not cause infinite loop`() {
    class EvilException(val number: Int) : Exception() {
      override fun toString() = number.toString()

      var settableCause: Throwable? = null

      override val cause: Throwable?
        get() = settableCause
    }

    val exception1 = EvilException(1)
    val exception2 = EvilException(2)
    val exception3 = EvilException(3)

    // Set up cycle
    exception1.settableCause = exception2
    exception1.addSuppressed(exception2)
    exception1.addSuppressed(exception2)
    exception1.addSuppressed(exception1)

    exception2.addSuppressed(exception3)

    exception3.settableCause = exception1
    exception3.addSuppressed(exception2)
    exception3.addSuppressed(exception1)

    /**
     * We test that both logging the exception and calling `traverseExceptionTree` do not cause
     * infinite loops (we want to test both, since we use a different exception traversal mechanism
     * when using Logback, see [LogEvent.handlesExceptionTreeTraversal]).
     */
    log.info(exception1) { "Should not cause an infinite loop" }

    val traversed = mutableSetOf<Throwable>()
    traverseExceptionTree(root = exception1) { exception -> traversed.add(exception) }

    traversed shouldContainExactly listOf(exception1, exception2, exception3)
  }

  @Test
  fun `Throwable withLoggingContext extension function works`() {
    val originalException = IllegalArgumentException("Something went wrong")
    val exceptionWithLoggingContext =
        originalException.withLoggingContext(
            field("key1", "value1"),
            field("key2", "value2"),
        )

    // `withLoggingContext` should not wrap the original exception
    exceptionWithLoggingContext.shouldBeInstanceOf<IllegalArgumentException>()
    exceptionWithLoggingContext.shouldBeSameInstanceAs(originalException)

    val output = captureLogOutput { log.error(exceptionWithLoggingContext) { "Test" } }
    output.logFields.shouldBe(
        """
        "key1":"value1","key2":"value2"
        """
            .trimIndent(),
    )
  }

  @Test
  fun `Throwable withLoggingContext extension function overload with Collection works`() {
    val originalException = Throwable("Something went wrong")
    val logFields: Collection<LogField> =
        listOf(
            field("key1", "value1"),
            field("key2", "value2"),
        )
    val exceptionWithLoggingContext = originalException.withLoggingContext(logFields)

    // `withLoggingContext` should not wrap the original exception
    exceptionWithLoggingContext.shouldBeInstanceOf<Throwable>()
    exceptionWithLoggingContext.shouldBeSameInstanceAs(originalException)

    val output = captureLogOutput { log.error(exceptionWithLoggingContext) { "Test" } }
    output.logFields.shouldBe(
        """
        "key1":"value1","key2":"value2"
        """
            .trimIndent(),
    )
  }

  @Test
  fun `calling Throwable withLoggingContext on ExceptionWithLoggingContext does not add suppressed LoggingContextProvider`() {
    val exception =
        ExceptionWithLoggingContext("Something went wrong", field("key1", "value1"))
            .withLoggingContext(field("key2", "value2"))

    exception.suppressedExceptions.shouldBeEmpty()

    val output = captureLogOutput { log.error(exception) { "Test" } }
    output.logFields.shouldBe(
        """
        "key1":"value1","key2":"value2"
        """
            .trimIndent(),
    )
  }
}

private fun ExceptionWithLoggingContext.logFields(): Collection<LogField> {
  /** See [ExceptionWithLoggingContext.logFields] for why these casts are safe. */
  @Suppress("UNCHECKED_CAST")
  return when (val logFields = logFields) {
    is Array<*> -> (logFields as Array<out LogField>).asList()
    is Collection<*> -> logFields as Collection<LogField>
    else ->
        throw IllegalStateException(
            "Unexpected type of 'logFields' on 'ExceptionWithLoggingContext': ${logFields}",
        )
  }
}
