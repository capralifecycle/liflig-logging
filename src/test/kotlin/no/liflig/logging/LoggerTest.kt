@file:Suppress("UsePropertyAccessSyntax")

package no.liflig.logging

import ch.qos.logback.classic.Level as LogbackLevel
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import ch.qos.logback.core.read.ListAppender
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.date.shouldBeBetween
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import no.liflig.logging.testutils.Event
import no.liflig.logging.testutils.EventAwareSlf4jLogger
import no.liflig.logging.testutils.EventType
import no.liflig.logging.testutils.LocationAwareSlf4jLogger
import no.liflig.logging.testutils.PlainSlf4jLogger
import no.liflig.logging.testutils.TestCase
import no.liflig.logging.testutils.loggerInOtherFile
import no.liflig.logging.testutils.runTestCases
import org.slf4j.LoggerFactory as Slf4jLoggerFactory
import org.slf4j.event.KeyValuePair

/**
 * We want to test all the different logger methods, on a variety of different loggers in different
 * platforms. In order to share as much common code as possible, we define this `LoggerTestCase`
 * class for testing expected log output. Each platform then defines:
 * - A list of [loggerTestCases]
 * - A [verifyLogOutput] function to verify the platform-specific log output
 * - A [getTestLogger] function for getting a logger that only enables logs at a given level
 * - A [resetLoggerTest] function for resetting any state between tests
 *
 * We use this here in the common [LoggerTest] class, so that we can define all common tests once,
 * delegating to platform-specific implementations when necessary.
 */
internal data class LoggerTestCase(
    override val name: String,
    val logger: Logger,
    val expectedMessage: String = LoggerTest.TestInput.MESSAGE,
    val expectedCause: Throwable? = LoggerTest.TestInput.CAUSE,
    val expectedFields: List<LogField> =
        listOf(
            LogField(
                key = LoggerTest.TestInput.FIELD_KEY_1,
                value = LoggerTest.TestInput.FIELD_VALUE_1,
                isJson = false,
            ),
            LogField(
                key = LoggerTest.TestInput.FIELD_KEY_2,
                value = """{"id":1000,"type":"ORDER_PLACED"}""",
                isJson = true,
            ),
        ),
    val shouldHaveCorrectFileLocation: Boolean = true,
) : TestCase

internal val loggerTestCases =
    listOf(
        LoggerTestCase(
            "Logback logger",
            logger = Logger(LoggerTest.logbackLogger),
        ),
        LoggerTestCase(
            "Event-aware SLF4J logger",
            logger = Logger(EventAwareSlf4jLogger(LoggerTest.logbackLogger)),
        ),
        LoggerTestCase(
            "Location-aware SLF4J logger",
            logger = Logger(LocationAwareSlf4jLogger(LoggerTest.logbackLogger)),
            expectedMessage =
                """Test message [key1=value1, key2={"id":1000,"type":"ORDER_PLACED"}]""",
            expectedFields = emptyList(),
        ),
        LoggerTestCase(
            "Plain SLF4J logger",
            logger = Logger(PlainSlf4jLogger(LoggerTest.logbackLogger)),
            expectedMessage =
                """Test message [key1=value1, key2={"id":1000,"type":"ORDER_PLACED"}]""",
            expectedFields = emptyList(),
            // The plain SLF4J logger does not implement location-aware logging, so we don't
            // expect it to have correct file location
            shouldHaveCorrectFileLocation = false,
        ),
    )

internal fun LoggerTestCase.verifyLogOutput(expectedLogLevel: LogLevel, block: () -> Unit) {
  val timeBefore = Instant.now()
  block()
  val timeAfter = Instant.now()

  LoggerTest.logAppender.list shouldHaveSize 1
  val logEvent = LoggerTest.logAppender.list.first()

  logEvent.loggerName shouldBe this.logger.underlyingLogger.name
  logEvent.message shouldBe this.expectedMessage
  logEvent.level shouldBe expectedLogLevel.toLogback()
  logEvent.instant.shouldBeBetween(timeBefore, timeAfter)

  if (this.expectedCause == null) {
    logEvent.throwableProxy.shouldBeNull()
  } else {
    val cause: Throwable =
        when (val throwableProxy = logEvent.throwableProxy) {
          is CustomLogbackThrowableProxy -> throwableProxy.throwable
          is ThrowableProxy -> throwableProxy.throwable
          else ->
              throw IllegalStateException(
                  "Unexpected ThrowableProxy type '${throwableProxy::class.qualifiedName}'",
              )
        }
    cause shouldBe this.expectedCause
  }

  if (this.expectedFields.isEmpty()) {
    logEvent.keyValuePairs.shouldBeNull()
  } else {
    logEvent.keyValuePairs.shouldContainExactly(
        this.expectedFields.map { field ->
          val expectedValue =
              if (field.isJson) {
                ValidRawJson(field.value)
              } else {
                field.value
              }
          KeyValuePair(field.key, expectedValue)
        },
    )
  }
}

/**
 * Returns a logger that only enables logs at or above the given level. If [level] is `null`, all
 * logs should be disabled.
 */
internal fun getTestLogger(level: LogLevel?): Logger {
  LoggerTest.logbackLogger.level = level?.toLogback() ?: LogbackLevel.OFF
  return Logger(LoggerTest.logbackLogger)
}

internal fun resetLoggerTest() {
  LoggerTest.logAppender.list.clear()
  LoggerTest.logbackLogger.level = LogbackLevel.TRACE
}

internal class LoggerTest {
  private val loggerInsideClass = getLogger()

  companion object {
    private val loggerOnCompanionObject = getLogger()

    /** We use a ListAppender from Logback here so we can inspect log events after logging. */
    val logAppender = ListAppender<ILoggingEvent>()
    val logbackLogger = Slf4jLoggerFactory.getLogger("LoggerTest") as LogbackLogger

    init {
      logAppender.start()
      logbackLogger.addAppender(logAppender)
      logbackLogger.level = LogbackLevel.TRACE
    }
  }

  /** Input passed to the [loggerTestCases] in the tests on this class. */
  object TestInput {
    const val MESSAGE: String = "Test message"
    const val FIELD_KEY_1: String = "key1"
    const val FIELD_VALUE_1: String = "value1"
    const val FIELD_KEY_2: String = "key2"
    val FIELD_VALUE_2: Event = Event(id = 1000, type = EventType.ORDER_PLACED)
    val CAUSE: Throwable = Exception("Something went wrong")
  }

  @AfterTest
  fun reset() {
    resetLoggerTest()
  }

  @Test
  fun `info log`() {
    runTestCases(loggerTestCases, afterEach = ::resetLoggerTest) { test ->
      test.verifyLogOutput(expectedLogLevel = LogLevel.INFO) {
        test.logger.info(cause = TestInput.CAUSE) {
          field(TestInput.FIELD_KEY_1, TestInput.FIELD_VALUE_1)
          field(TestInput.FIELD_KEY_2, TestInput.FIELD_VALUE_2)
          TestInput.MESSAGE
        }
      }
    }
  }

  @Test
  fun `warn log`() {
    runTestCases(loggerTestCases, afterEach = ::resetLoggerTest) { test ->
      test.verifyLogOutput(expectedLogLevel = LogLevel.WARN) {
        test.logger.warn(cause = TestInput.CAUSE) {
          field(TestInput.FIELD_KEY_1, TestInput.FIELD_VALUE_1)
          field(TestInput.FIELD_KEY_2, TestInput.FIELD_VALUE_2)
          TestInput.MESSAGE
        }
      }
    }
  }

  @Test
  fun `error log`() {
    runTestCases(loggerTestCases, afterEach = ::resetLoggerTest) { test ->
      test.verifyLogOutput(expectedLogLevel = LogLevel.ERROR) {
        test.logger.error(cause = TestInput.CAUSE) {
          field(TestInput.FIELD_KEY_1, TestInput.FIELD_VALUE_1)
          field(TestInput.FIELD_KEY_2, TestInput.FIELD_VALUE_2)
          TestInput.MESSAGE
        }
      }
    }
  }

  @Test
  fun `debug log`() {
    runTestCases(loggerTestCases, afterEach = ::resetLoggerTest) { test ->
      test.verifyLogOutput(expectedLogLevel = LogLevel.DEBUG) {
        test.logger.debug(cause = TestInput.CAUSE) {
          field(TestInput.FIELD_KEY_1, TestInput.FIELD_VALUE_1)
          field(TestInput.FIELD_KEY_2, TestInput.FIELD_VALUE_2)
          TestInput.MESSAGE
        }
      }
    }
  }

  @Test
  fun `trace log`() {
    runTestCases(loggerTestCases, afterEach = ::resetLoggerTest) { test ->
      test.verifyLogOutput(expectedLogLevel = LogLevel.TRACE) {
        test.logger.trace(cause = TestInput.CAUSE) {
          field(TestInput.FIELD_KEY_1, TestInput.FIELD_VALUE_1)
          field(TestInput.FIELD_KEY_2, TestInput.FIELD_VALUE_2)
          TestInput.MESSAGE
        }
      }
    }
  }

  @Test
  fun `info log using 'at' method`() {
    runTestCases(loggerTestCases, afterEach = ::resetLoggerTest) { test ->
      test.verifyLogOutput(expectedLogLevel = LogLevel.INFO) {
        test.logger.at(LogLevel.INFO, cause = TestInput.CAUSE) {
          field(TestInput.FIELD_KEY_1, TestInput.FIELD_VALUE_1)
          field(TestInput.FIELD_KEY_2, TestInput.FIELD_VALUE_2)
          TestInput.MESSAGE
        }
      }
    }
  }

  @Test
  fun `warn log using 'at' method`() {
    runTestCases(loggerTestCases, afterEach = ::resetLoggerTest) { test ->
      test.verifyLogOutput(expectedLogLevel = LogLevel.WARN) {
        test.logger.at(LogLevel.WARN, cause = TestInput.CAUSE) {
          field(TestInput.FIELD_KEY_1, TestInput.FIELD_VALUE_1)
          field(TestInput.FIELD_KEY_2, TestInput.FIELD_VALUE_2)
          TestInput.MESSAGE
        }
      }
    }
  }

  @Test
  fun `error log using 'at' method`() {
    runTestCases(loggerTestCases, afterEach = ::resetLoggerTest) { test ->
      test.verifyLogOutput(expectedLogLevel = LogLevel.ERROR) {
        test.logger.at(LogLevel.ERROR, cause = TestInput.CAUSE) {
          field(TestInput.FIELD_KEY_1, TestInput.FIELD_VALUE_1)
          field(TestInput.FIELD_KEY_2, TestInput.FIELD_VALUE_2)
          TestInput.MESSAGE
        }
      }
    }
  }

  @Test
  fun `debug log using 'at' method`() {
    runTestCases(loggerTestCases, afterEach = ::resetLoggerTest) { test ->
      test.verifyLogOutput(expectedLogLevel = LogLevel.DEBUG) {
        test.logger.at(LogLevel.DEBUG, cause = TestInput.CAUSE) {
          field(TestInput.FIELD_KEY_1, TestInput.FIELD_VALUE_1)
          field(TestInput.FIELD_KEY_2, TestInput.FIELD_VALUE_2)
          TestInput.MESSAGE
        }
      }
    }
  }

  @Test
  fun `trace log using 'at' method`() {
    runTestCases(loggerTestCases, afterEach = ::resetLoggerTest) { test ->
      test.verifyLogOutput(expectedLogLevel = LogLevel.TRACE) {
        test.logger.at(LogLevel.TRACE, cause = TestInput.CAUSE) {
          field(TestInput.FIELD_KEY_1, TestInput.FIELD_VALUE_1)
          field(TestInput.FIELD_KEY_2, TestInput.FIELD_VALUE_2)
          TestInput.MESSAGE
        }
      }
    }
  }

  /**
   * We test logs with field + cause exception above, but we also want to make sure that just
   * logging a message by itself works.
   */
  @Test
  fun `log with no fields or exceptions`() {
    runTestCases(loggerTestCases, afterEach = ::resetLoggerTest) { test ->
      val updatedTest =
          test.copy(
              expectedMessage = "Test",
              expectedCause = null,
              expectedFields = emptyList(),
          )
      updatedTest.verifyLogOutput(expectedLogLevel = LogLevel.INFO) {
        updatedTest.logger.info { "Test" }
      }
    }
  }

  @Test
  fun `getLogger with name parameter`() {
    val testName = "LoggerWithCustomName"
    val logger = getLogger(name = testName)
    logger.underlyingLogger.getName() shouldBe testName
  }

  @Test
  fun `getLogger with function parameter`() {
    // All loggers in this file should have this name (since file name and class name here are the
    // same), whether it's constructed inside the class, outside, or on a companion object.
    val expectedName = "no.liflig.logging.LoggerTest"
    loggerInsideClass.underlyingLogger.getName() shouldBe expectedName
    loggerOutsideClass.underlyingLogger.getName() shouldBe expectedName
    loggerOnCompanionObject.underlyingLogger.getName() shouldBe expectedName

    // Logger created in separate file should be named after that file.
    loggerInOtherFile.underlyingLogger.getName() shouldBe
        "no.liflig.logging.testutils.LoggerInOtherFile"
  }

  @Test
  fun `getLogger with class parameter`() {
    val logger = getLogger(LoggerTest::class)
    logger.underlyingLogger.getName() shouldBe "no.liflig.logging.LoggerTest"
  }

  @Test
  fun `getLogger strips away Kt suffix`() {
    val logger = getLogger(LoggerNameTestKt::class)
    logger.underlyingLogger.getName() shouldBe "no.liflig.logging.LoggerNameTest"
  }

  @Test
  fun `getLogger only removes Kt if it is a suffix`() {
    val logger = getLogger(ClassWithKtInName::class)
    logger.underlyingLogger.getName() shouldBe "no.liflig.logging.ClassWithKtInName"
  }

  @Test
  fun `log builder does not get called if log level is disabled`() {
    val failingLogBuilder: LogBuilder.() -> String = {
      throw Exception("This function should not get called when log level is disabled")
    }

    // Incrementally disable log levels, and verify that the log builder does not get called for
    // disabled levels
    getTestLogger(LogLevel.DEBUG).trace(null, failingLogBuilder)
    getTestLogger(LogLevel.INFO).debug(null, failingLogBuilder)
    getTestLogger(LogLevel.WARN).info(null, failingLogBuilder)
    getTestLogger(LogLevel.ERROR).warn(null, failingLogBuilder)
    getTestLogger(level = null).error(null, failingLogBuilder)
  }

  @Test
  fun `isEnabled methods return expected results for enabled and disabled log levels`() {
    // Incrementally raise the log level, and verify that the isEnabled methods return expected
    val traceLogger = getTestLogger(LogLevel.TRACE)
    traceLogger.isTraceEnabled.shouldBeTrue()
    traceLogger.isEnabledFor(LogLevel.TRACE).shouldBeTrue()

    val debugLogger = getTestLogger(LogLevel.DEBUG)
    debugLogger.isTraceEnabled.shouldBeFalse()
    debugLogger.isEnabledFor(LogLevel.TRACE).shouldBeFalse()
    debugLogger.isDebugEnabled.shouldBeTrue()
    debugLogger.isEnabledFor(LogLevel.DEBUG).shouldBeTrue()

    val infoLogger = getTestLogger(LogLevel.INFO)
    infoLogger.isDebugEnabled.shouldBeFalse()
    infoLogger.isEnabledFor(LogLevel.DEBUG).shouldBeFalse()
    infoLogger.isInfoEnabled.shouldBeTrue()
    infoLogger.isEnabledFor(LogLevel.INFO).shouldBeTrue()

    val warnLogger = getTestLogger(LogLevel.WARN)
    warnLogger.isInfoEnabled.shouldBeFalse()
    warnLogger.isEnabledFor(LogLevel.INFO).shouldBeFalse()
    warnLogger.isWarnEnabled.shouldBeTrue()
    warnLogger.isEnabledFor(LogLevel.WARN).shouldBeTrue()

    val errorLogger = getTestLogger(LogLevel.ERROR)
    errorLogger.isWarnEnabled.shouldBeFalse()
    errorLogger.isEnabledFor(LogLevel.WARN).shouldBeFalse()
    errorLogger.isErrorEnabled.shouldBeTrue()
    errorLogger.isEnabledFor(LogLevel.ERROR).shouldBeTrue()

    val disabledLogger = getTestLogger(level = null)
    disabledLogger.isErrorEnabled.shouldBeFalse()
    disabledLogger.isEnabledFor(LogLevel.ERROR).shouldBeFalse()
  }

  @Test
  fun `lambda arguments to logger methods are inlined`() {
    val log = getLogger()

    // Non-local returns in lambdas are only allowed if the enclosing function is inline.
    // So this will only compile if Logger's methods are inline, which is what we want to test here.
    log.info {
      return
    }
    log.warn {
      return
    }
    log.error {
      return
    }
    log.debug {
      return
    }
    log.trace {
      return
    }
  }

  @Test
  fun `LOGGER_CLASS_NAME has expected value`() {
    val expectedName: String = Logger::class.qualifiedName.shouldNotBeNull()
    LOGGER_CLASS_NAME shouldBe expectedName
  }

  @Test
  fun `log has expected file location`() {
    runTestCases(loggerTestCases, afterEach = ::reset) { test ->
      test.logger.info { "Test" }

      logAppender.list shouldHaveSize 1
      val logEvent = logAppender.list.first()
      logEvent.callerData.shouldNotBeEmpty()
      val caller = logEvent.callerData.first()

      if (test.shouldHaveCorrectFileLocation) {
        /**
         * We don't test line number here, as the logger methods will have wrong line numbers due to
         * being inline functions (see [Logger.info]).
         */
        caller.fileName shouldBe "LoggerTest.kt"
        caller.className shouldBe "no.liflig.logging.LoggerTest"
        caller.methodName shouldBe "log has expected file location"
      }
    }
  }

  @Test
  fun `Logback is loaded in tests`() {
    LOGBACK_IS_ON_CLASSPATH shouldBe true
  }
}

private val loggerOutsideClass = getLogger()

/**
 * Used to test that the `Kt` suffix is stripped away from classes passed to `getLogger`. This is
 * the suffix used for the synthetic classes that Kotlin generates for the top-level of files.
 */
private object LoggerNameTestKt

/**
 * Used to test that the logic used for [LoggerNameTestKt] only applies to classes with `Kt` as a
 * suffix, not when it has `Kt` in the middle of the name like this.
 */
private object ClassWithKtInName
