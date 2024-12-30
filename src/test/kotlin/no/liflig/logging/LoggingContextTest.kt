package no.liflig.logging

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.junit.jupiter.api.Test

private val log = getLogger {}

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
  fun `nested logging contexts work`() {
    val logFields = captureLogFields {
      withLoggingContext(
          field("outerContext", "value"),
      ) {
        withLoggingContext(
            field("nestedContext", "value"),
        ) {
          log.info { "Test" }
        }
      }
    }

    logFields shouldBe
        """
          "nestedContext":"value","outerContext":"value"
        """
            .trimIndent()
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

  @Test
  fun `passing a list to withLoggingContext works`() {
    val logFields = captureLogFields {
      withLoggingContext(
          logFields =
              listOf(
                  field("key1", "value1"),
                  field("key2", "value2"),
              ),
      ) {
        log.info { "Test" }
      }
    }

    logFields shouldBe
        """
          "key1":"value1","key2":"value2"
        """
            .trimIndent()
  }

  @Test
  fun `passing an empty list to withLoggingContext works`() {
    val logFields = captureLogFields {
      withLoggingContext(
          logFields = emptyList(),
      ) {
        log.info { "Test" }
      }
    }

    logFields shouldBe ""
  }

  @Test
  fun `non-local return works in withLoggingContext`() {
    withLoggingContext(field("key", "value")) {
      // This won't compile if withLoggingContext isn't inline, and we want to verify that
      return
    }
  }

  @Test
  fun `getLoggingContext allows passing logging context between threads`() {
    val executor = Executors.newSingleThreadExecutor()
    val lock = ReentrantLock()

    val logFields = captureLogFields {
      // Get the future from ExecutorService.submit, so we can wait until the log has completed
      val future =
          // Aquire a lock around the outer withLoggingContext in the parent thread, to test that
          // the logging context works in the child thread even when the outer context has exited
          lock.withLock {
            withLoggingContext(field("fieldFromParentThread", "value")) {
              // Get the parent logging context (the one we just entered)
              val loggingContext = getLoggingContext()

              executor.submit {
                // Acquire the lock here in the child thread - this will block until the outer
                // logging context has exited
                lock.withLock {
                  // Use the parent logging context here in the child thread
                  withLoggingContext(loggingContext) { log.error { "Test" } }
                }
              }
            }
          }

      future.get() // Waits until completed
    }

    logFields shouldBe
        """
          "fieldFromParentThread":"value"
        """
            .trimIndent()
  }

  /**
   * Verifies that the logic explained in the docstrings on [LoggingContext],
   * [LoggingContext.addFields] and [LoggingContext.popFields] works as expected.
   */
  @Test
  fun `logging context field array has expected state in various edge-cases`() {
    // Before calling withLoggingContext: array should be null
    LoggingContext.getFieldArray().shouldBeNull()

    // Outer context
    withLoggingContext(field("key1", "value")) {
      LoggingContext.getFieldArray() shouldBe arrayOf(field("key1", "value"))

      // First nested context: should add fields to beginning of array
      withLoggingContext(
          field("key2", "value"),
          field("key3", "value"),
      ) {
        LoggingContext.getFieldArray() shouldBe
            arrayOf(
                field("key2", "value"),
                field("key3", "value"),
                field("key1", "value"),
            )
      }

      val arrayAfterFirstNestedContext = LoggingContext.getFieldArray()
      // Key 2 and 3 should be set to null, since we exited their context. But array should not have
      // been shrunk (which would require a re-allocation) or freed, since we haven't exited the
      // outer context yet.
      arrayAfterFirstNestedContext shouldBe arrayOf(null, null, field("key1", "value"))

      // Second nested context: Should use available space in existing array
      withLoggingContext(field("key4", "value")) {
        val arrayInsideSecondNestedContext = LoggingContext.getFieldArray()
        arrayInsideSecondNestedContext shouldBe
            arrayOf(
                // New field should be placed right in front of existing fields, and leave empty
                // space at the front
                null,
                field("key4", "value"),
                field("key1", "value"),
            )
        // The same array instance should be re-used when there is available space
        arrayInsideSecondNestedContext shouldBeSameInstanceAs arrayAfterFirstNestedContext

        // Third nested context: This adds 2 extra fields to the context, exceeding the current
        // capacity. This should give us a new array with the new fields + existing fields, and
        // importantly _without_ the null element that the context had in its empty space.
        withLoggingContext(
            field("key5", "value"),
            field("key6", "value"),
        ) {
          LoggingContext.getFieldArray() shouldBe
              arrayOf(
                  field("key5", "value"),
                  field("key6", "value"),
                  field("key4", "value"),
                  field("key1", "value"),
              )
        }
      }

      // We still haven't exited the outer context, and since our last context had 4 total fields,
      // there should now be 3 empty spaces + the field from the outer context
      LoggingContext.getFieldArray() shouldBe arrayOf(null, null, null, field("key1", "value"))
    }

    // Outer context has now exited, so field array should be set to null to free the allocation
    LoggingContext.getFieldArray().shouldBeNull()
  }

  /**
   * Verify that logging context does not redundantly copy input array, to optimize performance
   * (explained in the second point on the docstring of [LoggingContext]).
   */
  @Test
  fun `logging context field array should not copy array from withLoggingContext`() {
    val fields = arrayOf(field("key", "value"))

    withLoggingContextInternal(fields) {
      LoggingContext.getFieldArray() shouldBeSameInstanceAs fields
    }
  }

  /**
   * Sanity check that varargs make a copy of a given array (an important assumption for us, as
   * explained on [withLoggingContextInternal]).
   */
  @Test
  fun `existing array is copied when passed as vararg`() {
    val fields = arrayOf(field("key", "value"))

    // Test both named param and spread operator (*) syntax, for good measure
    withLoggingContext(logFields = fields) {
      LoggingContext.getFieldArray() shouldNotBeSameInstanceAs fields
    }

    withLoggingContext(*fields) { LoggingContext.getFieldArray() shouldNotBeSameInstanceAs fields }
  }
}
