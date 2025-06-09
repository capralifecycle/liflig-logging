package no.liflig.logging

import ch.qos.logback.classic.Level as LogbackLevel
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.lang.reflect.Modifier
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaField
import kotlin.test.Test
import org.slf4j.event.Level as Slf4jLevel

internal class LogLevelTest {
  @Test
  fun `toString returns expected strings`() {
    LogLevel.ERROR.toString() shouldBe "ERROR"
    LogLevel.WARN.toString() shouldBe "WARN"
    LogLevel.INFO.toString() shouldBe "INFO"
    LogLevel.DEBUG.toString() shouldBe "DEBUG"
    LogLevel.TRACE.toString() shouldBe "TRACE"
  }

  @Test
  fun `companion object members on LogLevel contain all expected levels and no more`() {
    val properties = LogLevel.Companion::class.memberProperties

    val logLevels = properties.map { it.get(LogLevel.Companion) }
    logLevels.shouldContainExactlyInAnyOrder(
        LogLevel.ERROR,
        LogLevel.WARN,
        LogLevel.INFO,
        LogLevel.DEBUG,
        LogLevel.TRACE,
    )

    for (property in properties) {
      val field = property.javaField.shouldNotBeNull()
      // We want log levels to be represented as static fields on the JVM. Accessing a static field
      // should be faster than getting the companion object and then calling getter methods on it.
      Modifier.isStatic(field.modifiers).shouldBeTrue()
    }
  }

  @Test
  fun `LogLevel converts to expected SLF4J levels`() {
    LogLevel.ERROR.toSlf4j() shouldBe Slf4jLevel.ERROR
    LogLevel.WARN.toSlf4j() shouldBe Slf4jLevel.WARN
    LogLevel.INFO.toSlf4j() shouldBe Slf4jLevel.INFO
    LogLevel.DEBUG.toSlf4j() shouldBe Slf4jLevel.DEBUG
    LogLevel.TRACE.toSlf4j() shouldBe Slf4jLevel.TRACE
  }

  @Test
  fun `LogLevel converts to expected Logback levels`() {
    LogLevel.ERROR.toLogback() shouldBe LogbackLevel.ERROR
    LogLevel.WARN.toLogback() shouldBe LogbackLevel.WARN
    LogLevel.INFO.toLogback() shouldBe LogbackLevel.INFO
    LogLevel.DEBUG.toLogback() shouldBe LogbackLevel.DEBUG
    LogLevel.TRACE.toLogback() shouldBe LogbackLevel.TRACE
  }
}
