package no.liflig.logging

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.test.Test
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import no.liflig.logging.testutils.Event
import no.liflig.logging.testutils.EventType
import no.liflig.logging.testutils.LogOutput
import no.liflig.logging.testutils.TestCase
import no.liflig.logging.testutils.captureLogOutput
import no.liflig.logging.testutils.captureStdoutAndStderr
import no.liflig.logging.testutils.createLoggingContext
import no.liflig.logging.testutils.loggingContextShouldBeEmpty
import no.liflig.logging.testutils.loggingContextShouldContainExactly
import no.liflig.logging.testutils.runTestCases

private val log = getLogger()

internal class LoggingContextTest {
  @Test
  fun `field from logging context is included in log`() {
    val output = captureLogOutput {
      withLoggingContext(
          field("key", "value"),
      ) {
        log.info { "Test" }
      }
    }

    output.contextFields shouldContainExactly mapOf("key" to "value")
  }

  @Test
  fun `logging context applies to all logs in scope`() {
    val outputs = arrayOfNulls<LogOutput>(2)

    withLoggingContext(
        field("key", "value"),
    ) {
      outputs[0] = captureLogOutput { log.info { "Test" } }
      outputs[1] = captureLogOutput { log.info { "Test 2" } }
    }

    outputs.forEach { output ->
      output.shouldNotBeNull()
      output.contextFields shouldContainExactly mapOf("key" to "value")
    }
  }

  @Test
  fun `rawJsonField works with logging context`() {
    val output = captureLogOutput {
      withLoggingContext(
          rawJsonField("event", """{"id":1000,"type":"ORDER_UPDATED"}"""),
      ) {
        log.info { "Test" }
      }
    }

    output.contextFields shouldContainExactly
        mapOf(
            "event" to
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(1000),
                        "type" to JsonPrimitive("ORDER_UPDATED"),
                    ),
                ),
        )
  }

  @Test
  fun `logging context does not apply to logs outside scope`() {
    withLoggingContext(
        field("key", "value"),
    ) {
      log.info { "Inside scope" }
    }

    val output = captureLogOutput { log.info { "Outside scope" } }
    output.contextFields.shouldBeEmpty()
  }

  @Test
  fun `nested logging contexts work`() {
    val output = captureLogOutput {
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

    output.contextFields shouldContainExactly
        mapOf(
            "nestedContext" to "value",
            "outerContext" to "value",
        )
  }

  private class TestException : RuntimeException()

  @Test
  fun `logging context is attached to exceptions`() {
    val output = captureLogOutput {
      try {
        withLoggingContext(
            field("key", "value"),
        ) {
          throw TestException()
        }
      } catch (e: TestException) {
        log.error(e) { "Test" }
      }
    }

    // We expect the exception context field to be in `logFields`, not `contextFields`, because it
    // has escaped its original context
    output.logFields shouldBe
        """
          "key":"value"
        """
            .trimIndent()
    output.contextFields.shouldBeEmpty()
  }

  @Test
  fun `exception propagating through multiple levels of logging context includes them all`() {
    val output = captureLogOutput {
      withLoggingContext(
          field("level1", "value"),
      ) {
        try {
          withLoggingContext(
              field("level2_key1", "value"),
              field("level2_key2", Event(id = 1, type = EventType.ORDER_UPDATED)),
          ) {
            withLoggingContext(
                field("level3", "value"),
            ) {
              throw TestException()
            }
          }
        } catch (e: TestException) {
          log.error(e) { "Test" }
        }
      }
    }

    // We expect these exception context fields to be in `logFields`, not `contextFields`, because
    // they have escaped their original context
    output.logFields shouldBe
        """
          "level3":"value","level2_key1":"value","level2_key2":{"id":1,"type":"ORDER_UPDATED"}
        """
            .trimIndent()
    // We expect the outermost logging context fields to still be in `contextFields`, because the
    // exception is caught inside of its scope
    output.contextFields shouldContainExactly mapOf("level1" to "value")
  }

  @Test
  fun `duplicate context field keys only includes the newest fields`() {
    val outputFromInnerContext: LogOutput
    // We want to verify that after exiting the inner logging context, the fields from the outer
    // context are restored
    val outputFromOuterContext: LogOutput

    withLoggingContext(
        field("duplicateKey", "outer"),
    ) {
      withLoggingContext(
          field("duplicateKey", "inner1"),
          field("duplicateKey", "inner2"),
      ) {
        outputFromInnerContext = captureLogOutput { log.info { "Test" } }
      }

      outputFromOuterContext = captureLogOutput { log.info { "Test" } }
    }

    outputFromInnerContext.contextFields shouldContainExactly mapOf("duplicateKey" to "inner1")

    outputFromOuterContext.contextFields shouldContainExactly mapOf("duplicateKey" to "outer")
  }

  /**
   * Priority for duplicate keys in log fields is Log event -> Exception -> Context, so log event
   * field should override context field.
   */
  @Test
  fun `log event field overrides context fields`() {
    val output1: LogOutput
    val output2: LogOutput

    withLoggingContext(
        field("duplicateKey1", "context value"),
        // Test that JSON context fields are also overridden and restored as expected
        field("duplicateKey2", Event(id = 1, type = EventType.ORDER_PLACED)),
    ) {
      output1 = captureLogOutput {
        log.info {
          field("duplicateKey1", "log event value 1")
          field("duplicateKey2", "log event value 2")
          // Test that another duplicate key on the log event does not interfere with context fields
          field("duplicateKey2", "log event value 3")
          "Test"
        }
      }

      // Test that logging context still applies here after
      output2 = captureLogOutput { log.info { "Test 2" } }
    }

    output1.logFields shouldBe
        """
          "duplicateKey1":"log event value 1","duplicateKey2":"log event value 2"
        """
            .trimIndent()
    output1.contextFields.shouldBeEmpty()

    output2.logFields.shouldBeEmpty()
    output2.contextFields shouldContainExactly
        mapOf(
            "duplicateKey1" to "context value",
            "duplicateKey2" to
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(1),
                        "type" to JsonPrimitive("ORDER_PLACED"),
                    ),
                ),
        )
  }

  @Test
  fun `exception context field overrides outer context field`() {
    val exceptionLogOutput: LogOutput
    val outputAfterException: LogOutput

    withLoggingContext(
        field("duplicateKey", "outer context value"),
    ) {
      try {
        // Let exception propagate through nested logging contexts, so we can verify that only the
        // innermost context value is included in the log
        withLoggingContext(
            field("duplicateKey", "middle context value"),
        ) {
          withLoggingContext(
              // Include 2 fields on the duplicate key here, to verify that only the first field is
              // included in the log
              field("duplicateKey", "inner context value 1"),
              field("duplicateKey", "inner context value 2"),
          ) {
            throw TestException()
          }
        }
      } catch (e: TestException) {
        exceptionLogOutput = captureLogOutput { log.error(e) { "Test" } }
      }

      outputAfterException = captureLogOutput { log.info { "Test 2" } }
    }

    // We expect the exception context field to be in `logFields`, not `contextFields`, because it
    // has escaped its original context
    exceptionLogOutput.logFields shouldBe
        """
          "duplicateKey":"inner context value 1"
        """
            .trimIndent()
    // The context fields should be empty, since the outer `duplicateKey` was overridden
    exceptionLogOutput.contextFields.shouldBeEmpty()

    // Check that the outer logging context is restored after logging the exception
    outputAfterException.logFields.shouldBeEmpty()
    outputAfterException.contextFields shouldContainExactly
        mapOf("duplicateKey" to "outer context value")
  }

  @Test
  fun `LoggingContextProvider is not added to exception if logging context is empty`() {
    /**
     * If there are fields in the logging context, we add a [LoggingContextProvider] suppressed
     * exception if an exception would escape the logging context. But if the logging context is
     * empty (which it may be if we e.g. build a log field collection conditionally), we want to
     * make sure that we don't add any such redundant suppressed exception.
     */
    val exception: TestException
    try {
      withLoggingContext(
          logFields = emptyList(),
      ) {
        throw TestException()
      }
    } catch (e: TestException) {
      exception = e
    }

    exception.suppressedExceptions.shouldBeEmpty()
  }

  @Test
  fun `nested logging context restores previous context fields on exit`() {
    val event1 = Event(id = 1000, type = EventType.ORDER_PLACED)
    val event2 = Event(id = 1001, type = EventType.ORDER_UPDATED)

    withLoggingContext(
        field("event", event1),
        field("stringField", "parentValue"),
        field("parentOnlyField", "value1"),
        field("fieldThatIsStringInParentButJsonInChild", "stringValue"),
    ) {
      val parentContext =
          mapOf(
              "event" to """{"id":1000,"type":"ORDER_PLACED"}""",
              "stringField" to "parentValue",
              "parentOnlyField" to "value1",
              "fieldThatIsStringInParentButJsonInChild" to "stringValue",
          )
      loggingContextShouldContainExactly(parentContext)

      withLoggingContext(
          field("event", event2),
          field("stringField", "childValue"),
          field("childOnlyField", "value2"),
          rawJsonField("fieldThatIsStringInParentButJsonInChild", """{"test":true}"""),
      ) {
        loggingContextShouldContainExactly(
            mapOf(
                "event" to """{"id":1001,"type":"ORDER_UPDATED"}""",
                "stringField" to "childValue",
                "parentOnlyField" to "value1",
                "childOnlyField" to "value2",
                "fieldThatIsStringInParentButJsonInChild" to """{"test":true}""",
            ),
        )
      }

      loggingContextShouldContainExactly(parentContext)
    }
  }

  @Test
  fun `withLoggingContext existingContext overload merges given context with existing fields`() {
    val existingContext =
        createLoggingContext(mapOf("fieldMap1" to "value", "fieldMap2" to "value"))

    withLoggingContext(field("existingField", "value")) {
      loggingContextShouldContainExactly(mapOf("existingField" to "value"))

      withLoggingContext(existingContext) {
        loggingContextShouldContainExactly(
            mapOf(
                "existingField" to "value",
                "fieldMap1" to "value",
                "fieldMap2" to "value",
            ),
        )
      }

      // Previous fields should be restored after
      loggingContextShouldContainExactly(mapOf("existingField" to "value"))
    }
  }

  @Test
  fun `passing a collection to withLoggingContext works`() {
    val logFields: Collection<LogField> =
        listOf(
            field("key1", "value1"),
            field("key2", "value2"),
        )

    val output = captureLogOutput {
      withLoggingContext(logFields = logFields) { log.info { "Test" } }
    }

    output.contextFields shouldContainExactly
        mapOf(
            "key1" to "value1",
            "key2" to "value2",
        )
  }

  /** See comment inside the [withLoggingContext] overload that takes a `Collection`. */
  @Test
  fun `mutating collection passed to withLoggingContext does not affect context fields`() {
    val logFields =
        mutableListOf(
            field("key1", "value1"),
            field("key2", Event(id = 1, type = EventType.ORDER_PLACED)),
        )

    // We want to test 2 different log outputs that may rely on the log field collection:
    // - A log made _inside_ the context scope should be unaffected by the mutation
    // - A log made with an exception thrown from inside the context (which will carry the context
    //   fields) should also be unaffected
    var logOutputInsideContext: LogOutput? = null
    val exceptionLogOutput: LogOutput

    try {
      withLoggingContext(logFields) {
        // Mutate the log field collection here inside the context scope
        logFields.removeFirst()

        logOutputInsideContext = captureLogOutput { log.info { "Test 1" } }

        throw TestException()
      }
    } catch (e: TestException) {
      exceptionLogOutput = captureLogOutput { log.error(e) { "Test 2" } }
    }

    // We want to verify that `withLoggingContext` properly cleaned up its context fields, despite
    // the collection being mutated before the context scope ended. So this log after the context
    // exited should not have any log fields
    val logOutputAfterContext = captureLogOutput { log.info { "Test 3" } }

    logOutputInsideContext.shouldNotBeNull()
    logOutputInsideContext.contextFields shouldContainExactly
        mapOf(
            "key1" to "value1",
            "key2" to
                JsonObject(
                    mapOf("id" to JsonPrimitive(1), "type" to JsonPrimitive("ORDER_PLACED")),
                ),
        )
    logOutputAfterContext.logFields.shouldBeEmpty()

    exceptionLogOutput.logFields shouldBe
        """
          "key1":"value1","key2":{"id":1,"type":"ORDER_PLACED"}
        """
            .trimIndent()
    exceptionLogOutput.contextFields.shouldBeEmpty()

    logOutputAfterContext.contextFields.shouldBeEmpty()
    logOutputAfterContext.logFields.shouldBeEmpty()
  }

  @Test
  fun `passing an empty collection to withLoggingContext works`() {
    val output = captureLogOutput {
      withLoggingContext(
          logFields = emptyList(),
      ) {
        log.info { "Test" }
      }
    }

    output.contextFields.shouldBeEmpty()
  }

  @Test
  fun `passing empty varargs to withLoggingContext works`() {
    val output = captureLogOutput { withLoggingContext { log.info { "Test" } } }

    output.contextFields.shouldBeEmpty()
  }

  @Test
  fun `non-local return works in withLoggingContext vararg overload`() {
    withLoggingContext(field("key", "value")) {
      // This won't compile if withLoggingContext isn't inline, and we want to verify that
      return
    }
  }

  @Test
  fun `non-local return works in withLoggingContext collection overload`() {
    withLoggingContext(listOf(field("key", "value"))) {
      // This won't compile if withLoggingContext isn't inline, and we want to verify that
      return
    }
  }

  @Test
  fun `non-local return works in withLoggingContext existingContext overload`() {
    withLoggingContext(getCopyOfLoggingContext()) {
      // This won't compile if withLoggingContext isn't inline, and we want to verify that
      return
    }
  }

  @Test
  fun `lambda uses EXACTLY_ONCE contract in withLoggingContext vararg overload`() {
    val uninitialized: String

    withLoggingContext(field("key", "value")) { uninitialized = "Initialized" }

    // This won't compile unless `withLoggingContext` uses `callsInPlace` contract with
    // `InvocationKind.EXACTLY_ONCE`
    useString(uninitialized)
  }

  @Test
  fun `lambda uses EXACTLY_ONCE contract in withLoggingContext collection overload`() {
    val uninitialized: String

    withLoggingContext(listOf(field("key", "value"))) { uninitialized = "Initialized" }

    // This won't compile unless `withLoggingContext` uses `callsInPlace` contract with
    // `InvocationKind.EXACTLY_ONCE`
    useString(uninitialized)
  }

  @Test
  fun `lambda uses EXACTLY_ONCE contract in withLoggingContext existingContext overload`() {
    val uninitialized: String

    withLoggingContext(getCopyOfLoggingContext()) { uninitialized = "Initialized" }

    // This won't compile unless `withLoggingContext` uses `callsInPlace` contract with
    // `InvocationKind.EXACTLY_ONCE`
    useString(uninitialized)
  }

  /**
   * The exception message for [LoggingContextProvider] shouldn't normally show up in the logs
   * (since we exclude it in our [CustomLogbackThrowableProxy]). But it may still show up when an
   * exception is logged outside of our library (e.g. by JUnit), and in that case, we want to verify
   * that the exception message is as we expect.
   *
   * If the fields from [LoggingContextProvider] have not been added to a log, then the fields
   * should be included in the exception message (see [getLoggingContextProviderMessage] for more on
   * this).
   */
  @Test
  fun `LoggingContextProvider has expected exception message and empty stack trace`() {
    val loggingContextProvider =
        LoggingContextProvider(
            contextFields =
                arrayOf(
                    field("key1", "value"),
                    field("key2", Event(id = 1, type = EventType.ORDER_PLACED)),
                ),
        )

    // Here, the `LoggingContextProvider` fields have not been added to a log yet, so we expect them
    // to be included in the exception message
    loggingContextProvider.message shouldBe
        """
          Fields from exception logging context:
          		key1: value
          		key2: {"id":1,"type":"ORDER_PLACED"}
        """
            .trimIndent()

    log.info(loggingContextProvider) { "Test" }

    // Now, the `LoggingContextProvider` has been logged, so we expect a message without fields
    loggingContextProvider.message shouldBe "Added log fields from exception logging context"
  }

  /**
   * We override `fillInStackTrace` on [LoggingContextProvider] to be a no-op (see its docstring for
   * why), so we expect the output of `printStackTrace` to contain only the exception class name and
   * message (and a newline, since `printStackTrace` adds a trailing newline).
   */
  @Test
  fun `LoggingContextProvider has empty stack trace`() {
    val loggingContextProvider = LoggingContextProvider(arrayOf(field("key", "value")))
    // Log the `LoggingContextProvider`, so we get an exception message without fields
    log.info(loggingContextProvider) { "Test" }

    val stackTraceOutput = captureStdoutAndStderr { loggingContextProvider.printStackTrace() }
    stackTraceOutput shouldBe
        "no.liflig.logging.LoggingContextProvider: Added log fields from exception logging context\n"
  }

  @Test
  fun `getCopyOfLoggingContext allows passing logging context between threads`() {
    val event = Event(id = 1000, type = EventType.ORDER_PLACED)

    val lock = ReentrantLock()
    // Used to wait for the child thread to complete its log
    val latch = CountDownLatch(1)

    val output = captureLogOutput {
      // Aquire a lock around the outer withLoggingContext in the parent thread, to test that
      // the logging context works in the child thread even when the outer context has exited
      lock.withLock {
        withLoggingContext(field("event", event)) {
          // Get the parent logging context (the one we just entered)
          val parentContext = getCopyOfLoggingContext()

          thread {
            // Acquire the lock here in the child thread - this will block until the outer
            // logging context has exited
            lock.withLock {
              // Use the parent logging context here in the child thread
              withLoggingContext(parentContext) { log.error { "Test" } }
              latch.countDown()
            }
          }
        }
      }

      latch.await() // Waits until completed
    }

    output.contextFields shouldContainExactly
        mapOf(
            "event" to
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(1000),
                        "type" to JsonPrimitive("ORDER_PLACED"),
                    ),
                ),
        )
  }

  /**
   * [inheritLoggingContext] wraps an [ExecutorService], forwarding calls to the wrapped executor.
   * We want to verify that all these methods forward appropriately, so we make a test case for each
   * executor method, and run [runTestCases] in our executor tests to run each test on every
   * executor method.
   */
  class ExecutorTestCase(
      override val name: String,
      /**
       * `invokeAll` and `invokeAny` on [ExecutorService] block the calling thread. This affects how
       * we run our tests, so we set this flag to true for those cases.
       */
      val isBlocking: Boolean = false,
      val runTask: (ExecutorService, () -> Unit) -> Unit,
  ) : TestCase

  val executorTestCases =
      listOf(
          ExecutorTestCase("execute") { executor, task -> executor.execute(Runnable(task)) },
          ExecutorTestCase("submit with Callable") { executor, task ->
            executor.submit(Callable(task))
          },
          ExecutorTestCase("submit with Runnable") { executor, task ->
            executor.submit(Runnable(task))
          },
          ExecutorTestCase("submit with Runnable and result") { executor, task ->
            executor.submit(Runnable(task), "Result")
          },
          ExecutorTestCase("invokeAll", isBlocking = true) { executor, task ->
            executor.invokeAll(listOf(Callable(task)))
          },
          ExecutorTestCase("invokeAll with timeout", isBlocking = true) { executor, task ->
            executor.invokeAll(listOf(Callable(task)), 1, TimeUnit.MINUTES)
          },
          ExecutorTestCase("invokeAny", isBlocking = true) { executor, task ->
            executor.invokeAny(listOf(Callable(task)))
          },
          ExecutorTestCase("invokeAny with timeout", isBlocking = true) { executor, task ->
            executor.invokeAny(listOf(Callable(task)), 1, TimeUnit.MINUTES)
          },
      )

  @Test
  fun `ExecutorService with inheritLoggingContext allows passing logging context between threads`() {
    runTestCases(executorTestCases) { test ->
      val executor = Executors.newSingleThreadExecutor().inheritLoggingContext()
      val lock = ReentrantLock()
      val latch = CountDownLatch(1) // Used to wait for the child thread to complete its log

      val output = captureLogOutput {
        // Aquire a lock around the outer withLoggingContext in the parent thread, to test that
        // the logging context works in the child thread even when the outer context has exited.
        // Only relevant if the executor method is non-blocking (see ExecutorTestCase.isBlocking).
        lock.conditionallyLock(!test.isBlocking) {
          withLoggingContext(field("fieldFromParentThread", "value")) {
            test.runTask(executor) {
              // Acquire the lock here in the child thread - this will block until the outer
              // logging context has exited
              lock.conditionallyLock(!test.isBlocking) { log.error { "Test" } }
              latch.countDown()
            }
          }
        }

        latch.await() // Waits until child thread calls countDown()
      }

      output.contextFields shouldContainExactly mapOf("fieldFromParentThread" to "value")
    }
  }

  /**
   * In [ExecutorServiceWithInheritedLoggingContext], we only call [withLoggingContext] if there are
   * fields in the logging context. Otherwise, we just forward the tasks directly - we want to test
   * that that works.
   */
  @Test
  fun `ExecutorService with inheritLoggingContext works when there are no fields in the context`() {
    runTestCases(executorTestCases) { test ->
      val executor = Executors.newSingleThreadExecutor().inheritLoggingContext()

      // Verify that there are no fields in parent thread context
      loggingContextShouldBeEmpty()

      val latch = CountDownLatch(1) // Used to wait for the child thread to complete its log
      val executed = AtomicBoolean(false)

      test.runTask(executor) {
        // Verify that there are no fields in child thread context
        loggingContextShouldBeEmpty()
        executed.set(true)
        latch.countDown()
      }

      latch.await()
      executed.get().shouldBeTrue()
    }
  }

  @Test
  fun `ExecutorService with inheritLoggingContext does not affect parent thread context`() {
    val executor = Executors.newSingleThreadExecutor().inheritLoggingContext()

    // Used for synchronization points between the two threads in our test
    val barrier = CyclicBarrier(2)

    val parentField = field("parentField", "value")
    val childField = field("childField", "value")

    withLoggingContext(parentField) {
      loggingContextShouldContainExactly(mapOf("parentField" to "value"))

      executor.execute {
        loggingContextShouldContainExactly(mapOf("parentField" to "value"))

        withLoggingContext(childField) {
          loggingContextShouldContainExactly(
              mapOf("parentField" to "value", "childField" to "value"),
          )

          // 1st synchronization point: The parent thread will reach this after we've added to the
          // logging context here in the child thread, so we can verify that the parent's context is
          // unchanged
          barrier.await()
          // 2nd synchronization point: We want to keep this thread running while the parent thread
          // tests its logging context fields, to keep the child thread context alive
          barrier.await()
        }
      }

      barrier.await() // 1st synchronization point
      loggingContextShouldContainExactly(mapOf("parentField" to "value"))
      barrier.await() // 2nd synchronization point
    }
  }

  // Dummy method for contract tests
  private fun useString(string: String): Int {
    return string.length
  }
}

/**
 * Acquires the lock around the given block if the given condition is true - otherwise, just calls
 * the block directly.
 */
private inline fun Lock.conditionallyLock(condition: Boolean, block: () -> Unit) {
  if (condition) {
    this.withLock(block)
  } else {
    block()
  }
}
