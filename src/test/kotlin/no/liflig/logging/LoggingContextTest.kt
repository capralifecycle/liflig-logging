package no.liflig.logging

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

private val log = getLogger {}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoggingContextTest {
  @Test
  fun `field from logging context is included in log`() {
    val output = captureLogOutput {
      withLoggingContext(
          field("key", "value"),
      ) {
        log.info { "Test" }
      }
    }

    output.contextFields shouldContainExactly mapOf("key" to JsonPrimitive("value"))
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
      output.contextFields shouldContainExactly mapOf("key" to JsonPrimitive("value"))
    }
  }

  @Test
  fun `rawJsonField works with logging context`() {
    val output = captureLogOutput {
      withLoggingContext(
          rawJsonField("user", """{"id":1,"name":"John Doe"}"""),
      ) {
        log.info { "Test" }
      }
    }

    output.contextFields shouldContainExactly
        mapOf(
            "user" to
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(1),
                        "name" to JsonPrimitive("John Doe"),
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
            "nestedContext" to JsonPrimitive("value"),
            "outerContext" to JsonPrimitive("value"),
        )
  }

  @Test
  fun `nested logging context restores previous context fields on exit`() {
    val user1 = User(id = 1, name = "John Doe")
    val user2 = User(id = 2, name = "Jane Doe")

    withLoggingContext(
        field("user", user1),
        field("stringField", "parentValue"),
        field("parentOnlyField", "value1"),
        field("fieldThatIsStringInParentButJsonInChild", "stringValue"),
    ) {
      val parentContext =
          mapOf(
              "user${LoggingContext.JSON_FIELD_KEY_SUFFIX}" to """{"id":1,"name":"John Doe"}""",
              "stringField" to "parentValue",
              "parentOnlyField" to "value1",
              "fieldThatIsStringInParentButJsonInChild" to "stringValue",
          )
      LoggingContext shouldContainExactly parentContext

      withLoggingContext(
          field("user", user2),
          field("stringField", "childValue"),
          field("childOnlyField", "value2"),
          rawJsonField("fieldThatIsStringInParentButJsonInChild", """{"test":true}"""),
      ) {
        LoggingContext shouldContainExactly
            mapOf(
                "user${LoggingContext.JSON_FIELD_KEY_SUFFIX}" to """{"id":2,"name":"Jane Doe"}""",
                "stringField" to "childValue",
                "parentOnlyField" to "value1",
                "childOnlyField" to "value2",
                "fieldThatIsStringInParentButJsonInChild${LoggingContext.JSON_FIELD_KEY_SUFFIX}" to
                    """{"test":true}""",
            )
      }

      LoggingContext shouldContainExactly parentContext
    }
  }

  @Test
  fun `duplicate context field keys only includes the newest fields`() {
    var outputFromInnerContext: LogOutput? = null
    // We want to verify that after exiting the inner logging context, the fields from the outer
    // context are restored
    var outputFromOuterContext: LogOutput? = null

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

    outputFromInnerContext!!.contextFields shouldContainExactly
        mapOf("duplicateKey" to JsonPrimitive("inner1"))

    outputFromOuterContext!!.contextFields shouldContainExactly
        mapOf("duplicateKey" to JsonPrimitive("outer"))
  }

  /**
   * Priority for duplicate keys in log fields is Log event -> Exception -> Context, so log event
   * field should override context field.
   */
  @Test
  fun `context field does not override duplicate log event field`() {
    val output = captureLogOutput {
      withLoggingContext(
          field("duplicateKey", "from context"),
      ) {
        log.info {
          field("duplicateKey", "from log event")
          "Test"
        }
      }
    }

    output.logFields shouldBe
        """
          "duplicateKey":"from log event"
        """
            .trimIndent()

    // We would prefer for log event fields to override context fields, but logstash-logback-encoder
    // does not have support for that at the time of writing. If the library adds support for that
    // at some point, we can uncomment this line to test that the context fields are overwritten.
    // output.contextFields.shouldBeEmpty()
  }

  @Test
  fun `passing a list to withLoggingContext works`() {
    val output = captureLogOutput {
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

    output.contextFields shouldContainExactly
        mapOf(
            "key1" to JsonPrimitive("value1"),
            "key2" to JsonPrimitive("value2"),
        )
  }

  @Test
  fun `passing an empty list to withLoggingContext works`() {
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
  fun `non-local return works in withLoggingContext`() {
    withLoggingContext(field("key", "value")) {
      // This won't compile if withLoggingContext isn't inline, and we want to verify that
      return
    }
  }

  @Test
  fun `getLoggingContext allows passing logging context between threads`() {
    val user = User(id = 1, name = "John Doe")

    val lock = ReentrantLock()
    // Used to wait for the child thread to complete its log
    val latch = CountDownLatch(1)

    val output = captureLogOutput {
      // Aquire a lock around the outer withLoggingContext in the parent thread, to test that
      // the logging context works in the child thread even when the outer context has exited
      lock.withLock {
        withLoggingContext(field("user", user)) {
          // Get the parent logging context (the one we just entered)
          val loggingContext = getLoggingContext()

          thread {
            // Acquire the lock here in the child thread - this will block until the outer
            // logging context has exited
            lock.withLock {
              // Use the parent logging context here in the child thread
              withLoggingContext(loggingContext) { log.error { "Test" } }
              latch.countDown()
            }
          }
        }
      }

      latch.await() // Waits until completed
    }

    output.contextFields shouldContainExactly
        mapOf(
            "user" to
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(1),
                        "name" to JsonPrimitive("John Doe"),
                    ),
                ),
        )
  }

  /**
   * [inheritLoggingContext] wraps an [ExecutorService], forwarding calls to the wrapped executor.
   * We want to verify that all these methods forward appropriately, so we make a test case for each
   * executor method, and use this as a [MethodSource] on our executor tests to run each test on
   * every executor method.
   */
  class ExecutorTestCase(
      private val name: String,
      /**
       * `invokeAll` and `invokeAny` on [ExecutorService] block the calling thread. This affects how
       * we run our tests, so we set this flag to true for those cases.
       */
      val isBlocking: Boolean = false,
      val runTask: (ExecutorService, () -> Unit) -> Unit,
  ) {
    override fun toString() = name
  }

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

  @ParameterizedTest
  @MethodSource("getExecutorTestCases")
  fun `ExecutorService with inheritLoggingContext allows passing logging context between threads`(
      test: ExecutorTestCase
  ) {
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

    output.contextFields shouldContainExactly
        mapOf("fieldFromParentThread" to JsonPrimitive("value"))
  }

  /**
   * In [ExecutorServiceWithInheritedLoggingContext], we only call [withLoggingContextInternal] if
   * there are fields in the logging context. Otherwise, we just forward the tasks directly - we
   * want to test that that works.
   */
  @ParameterizedTest
  @MethodSource("getExecutorTestCases")
  fun `ExecutorService with inheritLoggingContext works when there are no fields in the context`(
      test: ExecutorTestCase
  ) {
    val executor = Executors.newSingleThreadExecutor().inheritLoggingContext()

    // Verify that there are no fields in parent thread context
    LoggingContext.shouldBeEmpty()

    val latch = CountDownLatch(1) // Used to wait for the child thread to complete its log
    val executed = AtomicBoolean(false)

    test.runTask(executor) {
      // Verify that there are no fields in child thread context
      LoggingContext.shouldBeEmpty()
      executed.set(true)
      latch.countDown()
    }

    latch.await()
    executed.get().shouldBeTrue()
  }

  @Test
  fun `ExecutorService with inheritLoggingContext does not affect parent thread context`() {
    val executor = Executors.newSingleThreadExecutor().inheritLoggingContext()

    // Used for synchronization points between the two threads in our test
    val barrier = CyclicBarrier(2)

    val parentField = field("parentField", "value")
    val childField = field("childField", "value")

    withLoggingContext(parentField) {
      LoggingContext shouldContainExactly mapOf("parentField" to "value")

      executor.execute {
        LoggingContext shouldContainExactly mapOf("parentField" to "value")

        withLoggingContext(childField) {
          LoggingContext shouldContainExactly
              mapOf("parentField" to "value", "childField" to "value")

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
      LoggingContext shouldContainExactly mapOf("parentField" to "value")
      barrier.await() // 2nd synchronization point
    }
  }

  @Test
  fun `LoggingContext withFieldMap merges given map with existing fields`() {
    withLoggingContext(field("existingField", "value")) {
      LoggingContext shouldContainExactly mapOf("existingField" to "value")

      LoggingContext.withFieldMap(
          mapOf("fieldMap1" to "value", "fieldMap2" to "value"),
      ) {
        LoggingContext shouldContainExactly
            mapOf(
                "existingField" to "value",
                "fieldMap1" to "value",
                "fieldMap2" to "value",
            )
      }

      // Previous fields should be restored after
      LoggingContext shouldContainExactly mapOf("existingField" to "value")
    }
  }
}

private infix fun LoggingContext.shouldContainExactly(map: Map<String, String>) {
  this.getFieldMap().shouldNotBeNull().shouldContainExactly(map)
}

private fun LoggingContext.shouldBeEmpty() {
  this.getFieldList().shouldBeEmpty()
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
