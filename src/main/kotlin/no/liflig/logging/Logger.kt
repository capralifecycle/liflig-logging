@file:Suppress(
    // We want `getLogger()` to be inline, so that `lookupClass()` is called in the caller's scope
    "NOTHING_TO_INLINE",
    // We want to use method syntax on the SLF4J Logger, to make it clear what we're calling
    "UsePropertyAccessSyntax",
)

package no.liflig.logging

import java.lang.invoke.MethodHandles
import kotlin.reflect.KClass
import org.slf4j.Logger as Slf4jLogger
import org.slf4j.LoggerFactory as Slf4jLoggerFactory

/**
 * A logger provides methods for logging at various log levels ([info], [warn], [error], [debug] and
 * [trace]). It has a logger name, typically the same as the class that the logger is attached to
 * (e.g. `com.example.Example`). The name is included in the log output, and can be used to
 * enable/disable log levels for loggers based on their package names, or query for logs from a
 * specific class.
 *
 * The easiest way to construct a logger is by calling [getLogger] with zero arguments. This
 * automatically gives the logger the name of its containing class (or file, if defined at the top
 * level). See the "Implementation" section on [getLogger]'s docstring for how this works.
 *
 * ```
 * // In file Example.kt
 * package com.example
 *
 * import no.liflig.logging.getLogger
 *
 * // Gets the name "com.example.Example"
 * private val log = getLogger()
 *
 * fun example() {
 *   log.info { "Example message" }
 * }
 * ```
 *
 * Alternatively, you can provide a custom name to `getLogger`. The name should follow fully
 * qualified class name format, like `com.example.Example`, to allow you to enable/disable log
 * levels based on the package.
 *
 * ```
 * private val log = getLogger(name = "com.example.Example")
 * ```
 *
 * You can also pass a class to `getLogger`, to give the logger the name of that class:
 * ```
 * package com.example
 *
 * class Example {
 *   companion object {
 *     // Gets the name "com.example.Example"
 *     private val log = getLogger(Example::class)
 *   }
 * }
 * ```
 */
@JvmInline // Inline value class, to avoid redundant indirection when we just wrap an SLF4J logger
public value class Logger
internal constructor(
    @PublishedApi internal val underlyingLogger: Slf4jLogger,
) {
  /**
   * Calls the given lambda to build a log message, and logs it at [LogLevel.INFO], if enabled.
   *
   * If the log was caused by an exception, you can attach it to the log with the optional [cause]
   * parameter before the lambda.
   *
   * In the scope of the [buildLog] lambda, you can call [LogBuilder.field] to add structured
   * key-value data to the log.
   *
   * ### Example
   *
   * ```
   * private val log = getLogger()
   *
   * fun example(event: Event) {
   *   log.info {
   *     field("event", event)
   *     "Processing event"
   *   }
   * }
   * ```
   *
   * ### Note on line numbers
   *
   * If you include file location information in your log encoder (such as enabling
   * `includeCallerData` in `logstash-logback-encoder`), then the log will show an incorrect line
   * number. This happens because [Logger]'s methods are `inline`, to avoid allocating a function
   * object for [buildLog]. Inline functions give incorrect line numbers, but we prioritize the
   * performance gain in this case. File, class and method names will still be correct.
   *
   * @param cause Optional cause exception. Pass this in parentheses before the lambda.
   * @param buildLog Returns the message to log. Will only be called if the log level is enabled, so
   *   you don't pay for string concatenation if it's not logged.
   *
   *   The [LogBuilder] receiver lets you call [field][LogBuilder.field] in the scope of the lambda,
   *   to add structured key-value data to the log.
   *
   *   We mark the lambda as `crossinline`, so you don't accidentally do a non-local return in it,
   *   which would drop the log.
   */
  public inline fun info(
      cause: Throwable? = null,
      crossinline buildLog: LogBuilder.() -> String,
  ) {
    if (underlyingLogger.isInfoEnabled()) {
      log(LogLevel.INFO, cause, buildLog)
    }
  }

  /**
   * Calls the given lambda to build a log message, and logs it at [LogLevel.WARN], if enabled.
   *
   * If the log was caused by an exception, you can attach it to the log with the optional [cause]
   * parameter before the lambda.
   *
   * In the scope of the [buildLog] lambda, you can call [LogBuilder.field] to add structured
   * key-value data to the log.
   *
   * ### Example
   *
   * ```
   * private val log = getLogger()
   *
   * fun example(event: Event) {
   *   try {
   *     publishEvent(event)
   *   } catch (e: Exception) {
   *     log.warn(e) {
   *       field("event", event)
   *       "Failed to publish event, retrying"
   *     }
   *   }
   * }
   * ```
   *
   * ### Note on line numbers
   *
   * If you include file location information in your log encoder (such as enabling
   * `includeCallerData` in `logstash-logback-encoder`), then the log will show an incorrect line
   * number. This happens because [Logger]'s methods are `inline`, to avoid allocating a function
   * object for [buildLog]. Inline functions give incorrect line numbers, but we prioritize the
   * performance gain in this case. File, class and method names will still be correct.
   *
   * @param cause Optional cause exception. Pass this in parentheses before the lambda.
   * @param buildLog Returns the message to log. Will only be called if the log level is enabled, so
   *   you don't pay for string concatenation if it's not logged.
   *
   *   The [LogBuilder] receiver lets you call [field][LogBuilder.field] in the scope of the lambda,
   *   to add structured key-value data to the log.
   *
   *   We mark the lambda as `crossinline`, so you don't accidentally do a non-local return in it,
   *   which would drop the log.
   */
  public inline fun warn(
      cause: Throwable? = null,
      crossinline buildLog: LogBuilder.() -> String,
  ) {
    if (underlyingLogger.isWarnEnabled()) {
      log(LogLevel.WARN, cause, buildLog)
    }
  }

  /**
   * Calls the given lambda to build a log message, and logs it at [LogLevel.ERROR], if enabled.
   *
   * If the log was caused by an exception, you can attach it to the log with the optional [cause]
   * parameter before the lambda.
   *
   * In the scope of the [buildLog] lambda, you can call [LogBuilder.field] to add structured
   * key-value data to the log.
   *
   * ### Example
   *
   * ```
   * private val log = getLogger()
   *
   * fun example(event: Event) {
   *   try {
   *     processEvent(event)
   *   } catch (e: Exception) {
   *     log.error(e) {
   *       field("event", event)
   *       "Failed to process event"
   *     }
   *   }
   * }
   * ```
   *
   * ### Note on line numbers
   *
   * If you include file location information in your log encoder (such as enabling
   * `includeCallerData` in `logstash-logback-encoder`), then the log will show an incorrect line
   * number. This happens because [Logger]'s methods are `inline`, to avoid allocating a function
   * object for [buildLog]. Inline functions give incorrect line numbers, but we prioritize the
   * performance gain in this case. File, class and method names will still be correct.
   *
   * @param cause Optional cause exception. Pass this in parentheses before the lambda.
   * @param buildLog Returns the message to log. Will only be called if the log level is enabled, so
   *   you don't pay for string concatenation if it's not logged.
   *
   *   The [LogBuilder] receiver lets you call [field][LogBuilder.field] in the scope of the lambda,
   *   to add structured key-value data to the log.
   *
   *   We mark the lambda as `crossinline`, so you don't accidentally do a non-local return in it,
   *   which would drop the log.
   */
  public inline fun error(
      cause: Throwable? = null,
      crossinline buildLog: LogBuilder.() -> String,
  ) {
    if (underlyingLogger.isErrorEnabled()) {
      log(LogLevel.ERROR, cause, buildLog)
    }
  }

  /**
   * Calls the given lambda to build a log message, and logs it at [LogLevel.DEBUG], if enabled.
   *
   * If the log was caused by an exception, you can attach it to the log with the optional [cause]
   * parameter before the lambda.
   *
   * In the scope of the [buildLog] lambda, you can call [LogBuilder.field] to add structured
   * key-value data to the log.
   *
   * ### Example
   *
   * ```
   * private val log = getLogger()
   *
   * fun example(event: Event) {
   *   log.debug {
   *     field("event", event)
   *     "Processing event"
   *   }
   * }
   * ```
   *
   * ### Note on line numbers
   *
   * If you include file location information in your log encoder (such as enabling
   * `includeCallerData` in `logstash-logback-encoder`), then the log will show an incorrect line
   * number. This happens because [Logger]'s methods are `inline`, to avoid allocating a function
   * object for [buildLog]. Inline functions give incorrect line numbers, but we prioritize the
   * performance gain in this case. File, class and method names will still be correct.
   *
   * @param cause Optional cause exception. Pass this in parentheses before the lambda.
   * @param buildLog Returns the message to log. Will only be called if the log level is enabled, so
   *   you don't pay for string concatenation if it's not logged.
   *
   *   The [LogBuilder] receiver lets you call [field][LogBuilder.field] in the scope of the lambda,
   *   to add structured key-value data to the log.
   *
   *   We mark the lambda as `crossinline`, so you don't accidentally do a non-local return in it,
   *   which would drop the log.
   */
  public inline fun debug(
      cause: Throwable? = null,
      crossinline buildLog: LogBuilder.() -> String,
  ) {
    if (underlyingLogger.isDebugEnabled()) {
      log(LogLevel.DEBUG, cause, buildLog)
    }
  }

  /**
   * Calls the given lambda to build a log message, and logs it at the [LogLevel.TRACE], if enabled.
   *
   * If the log was caused by an exception, you can attach it to the log with the optional [cause]
   * parameter before the lambda.
   *
   * In the scope of the [buildLog] lambda, you can call [LogBuilder.field] to add structured
   * key-value data to the log.
   *
   * ### Example
   *
   * ```
   * private val log = getLogger()
   *
   * fun example(event: Event) {
   *   log.trace {
   *     field("event", event)
   *     "Event processing started"
   *   }
   * }
   * ```
   *
   * ### Note on line numbers
   *
   * If you include file location information in your log encoder (such as enabling
   * `includeCallerData` in `logstash-logback-encoder`), then the log will show an incorrect line
   * number. This happens because [Logger]'s methods are `inline`, to avoid allocating a function
   * object for [buildLog]. Inline functions give incorrect line numbers, but we prioritize the
   * performance gain in this case. File, class and method names will still be correct.
   *
   * @param cause Optional cause exception. Pass this in parentheses before the lambda.
   * @param buildLog Returns the message to log. Will only be called if the log level is enabled, so
   *   you don't pay for string concatenation if it's not logged.
   *
   *   The [LogBuilder] receiver lets you call [field][LogBuilder.field] in the scope of the lambda,
   *   to add structured key-value data to the log.
   *
   *   We mark the lambda as `crossinline`, so you don't accidentally do a non-local return in it,
   *   which would drop the log.
   */
  public inline fun trace(
      cause: Throwable? = null,
      crossinline buildLog: LogBuilder.() -> String,
  ) {
    if (underlyingLogger.isTraceEnabled()) {
      log(LogLevel.TRACE, cause, buildLog)
    }
  }

  /**
   * Calls the given lambda to build a log message, and logs it at the given [LogLevel], if enabled.
   * This is useful when setting the log level dynamically, instead of calling
   * [info]/[warn]/[error]/[debug]/[trace] conditionally.
   *
   * If the log was caused by an exception, you can attach it to the log with the optional [cause]
   * parameter before the lambda.
   *
   * In the scope of the [buildLog] lambda, you can call [LogBuilder.field] to add structured
   * key-value data to the log.
   *
   * ### Example
   *
   * ```
   * private val log = getLogger()
   *
   * fun example(event: Event) {
   *   try {
   *     processEvent(event)
   *   } catch (e: Exception) {
   *     val logLevel = if (e is IOException) LogLevel.ERROR else LogLevel.WARN
   *     log.at(logLevel, cause = e) {
   *       field("event", event)
   *       "Failed to process event"
   *     }
   *   }
   * }
   * ```
   *
   * ### Note on line numbers
   *
   * If you include file location information in your log encoder (such as enabling
   * `includeCallerData` in `logstash-logback-encoder`), then the log will show an incorrect line
   * number. This happens because [Logger]'s methods are `inline`, to avoid allocating a function
   * object for [buildLog]. Inline functions give incorrect line numbers, but we prioritize the
   * performance gain in this case. File, class and method names will still be correct.
   *
   * @param level Severity of the log.
   * @param cause Optional cause exception. Pass this in parentheses before the lambda.
   * @param buildLog Returns the message to log. Will only be called if the log level is enabled, so
   *   you don't pay for string concatenation if it's not logged.
   *
   *   The [LogBuilder] receiver lets you call [field][LogBuilder.field] in the scope of the lambda,
   *   to add structured key-value data to the log.
   *
   *   We mark the lambda as `crossinline`, so you don't accidentally do a non-local return in it,
   *   which would drop the log.
   */
  public inline fun at(
      level: LogLevel,
      cause: Throwable? = null,
      crossinline buildLog: LogBuilder.() -> String,
  ) {
    if (isEnabledFor(level)) {
      log(level, cause, buildLog)
    }
  }

  @PublishedApi
  internal inline fun log(
      level: LogLevel,
      cause: Throwable?,
      crossinline buildLog: LogBuilder.() -> String,
  ) {
    val builder = LogBuilder(createLogEvent(level, cause, underlyingLogger))
    val message = builder.buildLog()
    if (cause != null) {
      // Call this after buildLog(), so cause exception fields don't overwrite LogBuilder fields
      builder.addFieldsFromCauseException(cause)
    }

    builder.logEvent.log(message, underlyingLogger)
  }

  /**
   * Returns true if [LogLevel.ERROR] is enabled for this logger.
   *
   * When using Logback, you can enable/disable log levels for loggers based on their package names
   * (see
   * [Logback configuration docs](https://logback.qos.ch/manual/configuration.html#loggerElement)).
   */
  public val isErrorEnabled: Boolean
    inline get() = underlyingLogger.isErrorEnabled()

  /**
   * Returns true if [LogLevel.WARN] is enabled for this logger.
   *
   * When using Logback, you can enable/disable log levels for loggers based on their package names
   * (see
   * [Logback configuration docs](https://logback.qos.ch/manual/configuration.html#loggerElement)).
   */
  public val isWarnEnabled: Boolean
    inline get() = underlyingLogger.isWarnEnabled()

  /**
   * Returns true if [LogLevel.INFO] is enabled for this logger.
   *
   * When using Logback, you can enable/disable log levels for loggers based on their package names
   * (see
   * [Logback configuration docs](https://logback.qos.ch/manual/configuration.html#loggerElement)).
   */
  public val isInfoEnabled: Boolean
    inline get() = underlyingLogger.isInfoEnabled()

  /**
   * Returns true if [LogLevel.DEBUG] is enabled for this logger.
   *
   * When using Logback, you can enable/disable log levels for loggers based on their package names
   * (see
   * [Logback configuration docs](https://logback.qos.ch/manual/configuration.html#loggerElement)).
   */
  public val isDebugEnabled: Boolean
    inline get() = underlyingLogger.isDebugEnabled()

  /**
   * Returns true if [LogLevel.TRACE] is enabled for this logger.
   *
   * When using Logback, you can enable/disable log levels for loggers based on their package names
   * (see
   * [Logback configuration docs](https://logback.qos.ch/manual/configuration.html#loggerElement)).
   */
  public val isTraceEnabled: Boolean
    inline get() = underlyingLogger.isTraceEnabled()

  /**
   * Returns true if the given log level is enabled for this logger.
   *
   * When using Logback, you can enable/disable log levels for loggers based on their package names
   * (see
   * [Logback configuration docs](https://logback.qos.ch/manual/configuration.html#loggerElement)).
   */
  public fun isEnabledFor(level: LogLevel): Boolean {
    return level.match(
        ERROR = { underlyingLogger.isErrorEnabled() },
        WARN = { underlyingLogger.isWarnEnabled() },
        INFO = { underlyingLogger.isInfoEnabled() },
        DEBUG = { underlyingLogger.isDebugEnabled() },
        TRACE = { underlyingLogger.isTraceEnabled() },
    )
  }
}

/**
 * Returns a [Logger], with its name inferred from the class in which it's called (or file, if
 * defined at the top level).
 *
 * The logger name is included in the log output, and can be used to enable/disable log levels for
 * loggers based on their package names, or query for logs from a specific class.
 *
 * ### Example
 *
 * ```
 * // In file Example.kt
 * package com.example
 *
 * import no.liflig.logging.getLogger
 *
 * // Gets the name "com.example.Example"
 * private val log = getLogger()
 *
 * fun example() {
 *   log.info { "Example message" }
 * }
 * ```
 *
 * ### Implementation
 *
 * This calls `MethodHandles.lookup().lookupClass()`, which returns the calling class. Since this
 * function is inline, that will actually return the class that called `getLogger`, so we can use it
 * to get the name of the caller. When called at file scope, the calling class will be the synthetic
 * `Kt` class that Kotlin generates for the file, so we can use the file name in that case.
 *
 * This is the pattern that
 * [the SLF4J docs recommends](https://www.slf4j.org/faq.html#declaration_pattern) for instantiating
 * loggers in a generic manner.
 */
public inline fun getLogger(): Logger {
  return getLogger(javaClass = MethodHandles.lookup().lookupClass())
}

@PublishedApi
internal fun getLogger(javaClass: Class<*>): Logger {
  val name = normalizeLoggerName(javaClass.name)
  return Logger(Slf4jLoggerFactory.getLogger(name))
}

@Deprecated(
    "Replaced by zero-parameter getLogger() overload. You can search-and-replace 'getLogger {}' -> 'getLogger()' to update",
)
public fun getLogger(emptyLambdaToGetName: () -> Unit): Logger {
  val name = normalizeLoggerName(emptyLambdaToGetName::class.qualifiedName)
  return Logger(Slf4jLoggerFactory.getLogger(name))
}

/**
 * Returns a [Logger] with the name of the given class.
 *
 * The logger name is included in the log output, and can be used to enable/disable log levels for
 * loggers based on their package names, or query for logs from a specific class.
 *
 * In most cases, you should prefer the zero-parameter `getLogger()` overload, to automatically get
 * the name of the containing class (or file). But if you want more control over which class to use
 * for the logger name, you can use this overload.
 *
 * ### Example
 *
 * ```
 * package com.example
 *
 * import no.liflig.logging.getLogger
 *
 * class Example {
 *   companion object {
 *     // Gets the name "com.example.Example"
 *     private val log = getLogger(Example::class)
 *   }
 *
 *   fun example() {
 *     log.info { "Example message" }
 *   }
 * }
 * ```
 */
public fun getLogger(forClass: KClass<*>): Logger {
  val name = normalizeLoggerName(forClass.qualifiedName)
  return Logger(Slf4jLoggerFactory.getLogger(name))
}

/**
 * Returns a [Logger] with the given name.
 *
 * The logger name is included in the log output, and can be used to enable/disable log levels for
 * loggers based on their package names, or query for logs from a specific class. Because of this,
 * the name given here should follow fully qualified class name format, like `com.example.Example`.
 *
 * To set the name automatically from the containing class/file, you can use the zero-parameter
 * `getLogger()` overload instead.
 *
 * ### Example
 *
 * ```
 * private val log = getLogger(name = "com.example.Example")
 * ```
 *
 * @param name Should follow fully qualified class name format, like `com.example.Example`.
 */
public fun getLogger(name: String): Logger {
  return Logger(Slf4jLoggerFactory.getLogger(name))
}

/**
 * Removes any `Kt` suffix from the given class name (which Kotlin adds to the classes that are
 * generated for the top-level of files).
 *
 * Implementation based on the
 * [KLoggerNameResolver from kotlin-logging](https://github.com/oshai/kotlin-logging/blob/e9c6ec570cd503c626fca5878efcf1291d4125b7/src/jvmMain/kotlin/mu/internal/KLoggerNameResolver.kt#L9-L19),
 * with minor changes. Licensed under
 * [Apache 2.0](https://github.com/oshai/kotlin-logging/blob/e9c6ec570cd503c626fca5878efcf1291d4125b7/LICENSE).
 */
private fun normalizeLoggerName(name: String?): String {
  return when {
    // We may get a null name from `KClass.qualifiedName` in the `getLogger(forClass: KClass<*>)`
    // overload. Although this should be rare (as it would only happen if the user somehow passed a
    // synthetic class), we want to gracefully handle this, using "Logger" as a generic fallback.
    name == null -> "Logger"
    name.contains("Kt$") -> name.substringBefore("Kt$")
    name.contains("$") -> name.substringBefore("$")
    name.endsWith("Kt") -> name.removeSuffix("Kt")
    else -> name
  }
}
