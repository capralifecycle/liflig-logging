package no.liflig.logging

import org.slf4j.event.Level as Slf4jLevel

/**
 * The severity of a log. From most to least severe:
 * - [LogLevel.ERROR]
 * - [LogLevel.WARN]
 * - [LogLevel.INFO]
 * - [LogLevel.DEBUG]
 * - [LogLevel.TRACE]
 *
 * When using Logback, you can enable/disable log levels for loggers based on their package names
 * (see
 * [Logback configuration docs](https://logback.qos.ch/manual/configuration.html#loggerElement)).
 * You also set a "root" (default) log level - if this level is `INFO`, then `DEBUG`/`TRACE` logs
 * will not produce any output unless explicitly enabled for a logger.
 *
 * Log levels are implemented as static instances on the companion object, instead of as an enum.
 * This is because we don't want to bind ourselves to the public API that an enum exposes
 * ([Enum.name] and [Enum.ordinal]).
 */
public class LogLevel
private constructor(
    // Kept for backwards compatibility. TODO: Remove once users have migrated
    @PublishedApi internal val slf4jLevel: Slf4jLevel,
) {
  /** See [LogLevel]. */
  public companion object {
    /**
     * The highest log level, for errors in your system that may require immediate attention.
     *
     * See [LogLevel] for more on how to configure log levels.
     */
    @JvmField // Compiles this as a static field on the JVM, for faster access
    public val ERROR: LogLevel = LogLevel(Slf4jLevel.ERROR)

    /**
     * The second-highest log level - less severe than `ERROR`, more severe than `INFO`. Use this
     * when a fault has occurred in the system, but it doesn't necessarily require the immediate
     * attention that an `ERROR` would.
     *
     * See [LogLevel] for more on how to configure log levels.
     */
    @JvmField // Compiles this as a static field on the JVM, for faster access
    public val WARN: LogLevel = LogLevel(Slf4jLevel.WARN)

    /**
     * The median log level - less severe than `ERROR` and `WARN`, more severe than `DEBUG` and
     * `TRACE`. The standard log level to use for informational output, that most consumers of your
     * logs will be interested in, but that doesn't signal an error in your system.
     *
     * See [LogLevel] for more on how to configure log levels.
     */
    @JvmField // Compiles this as a static field on the JVM, for faster access
    public val INFO: LogLevel = LogLevel(Slf4jLevel.INFO)

    /**
     * The second-lowest log level - less severe than `INFO`, more severe than `TRACE`. This is used
     * for debug output, that you may not always have enabled, but that you may want to enable for
     * certain packages where you need more information for debugging.
     *
     * See [LogLevel] for more on how to configure log levels.
     */
    @JvmField // Compiles this as a static field on the JVM, for faster access
    public val DEBUG: LogLevel = LogLevel(Slf4jLevel.DEBUG)

    /**
     * The lowest log level, for tracing minute application details. This log level will typically
     * be disabled by default, and so will not produce any log output unless explicitly enabled for
     * a logger.
     *
     * See [LogLevel] for more on how to configure log levels.
     */
    @JvmField // Compiles this as a static field on the JVM, for faster access
    public val TRACE: LogLevel = LogLevel(Slf4jLevel.TRACE)
  }

  /**
   * Returns a string representation of the log level:
   * - `"ERROR"` for [LogLevel.ERROR]
   * - `"WARN"` for [LogLevel.WARN]
   * - `"INFO"` for [LogLevel.INFO]
   * - `"DEBUG"` for [LogLevel.DEBUG]
   * - `"TRACE"` for [LogLevel.TRACE]
   */
  override fun toString(): String {
    return this.match(
        ERROR = { "ERROR" },
        WARN = { "WARN" },
        INFO = { "INFO" },
        DEBUG = { "DEBUG" },
        TRACE = { "TRACE" },
    )
  }

  /**
   * Emulates a `when` expression for all the possible variants of [LogLevel].
   *
   * If we made [LogLevel] an enum or a sealed class, we could have exhaustive `when` expressions
   * without an `else` branch. However, that would freeze the current members of `LogLevel` as part
   * of our public API, as introducing new members would break compatibility. Library consumers
   * should only be using log levels as constants to pass to the library, so we don't want to expand
   * our public API needlessly like that.
   *
   * But since we now lose exhaustive `when` checks, we need an `else` branch. This `else` branch
   * will always be unreachable as long as we check all the levels defined on the companion object
   * here - since the constructor is private, there will be no other instances. To keep this logic
   * in one place, we provide this method to emulate a `when` expression on a log level:
   * - We take a lambda parameter for each log level, to ensure that all cases are covered
   *     - We make the method `inline`, so we don't pay a cost for the lambdas
   * - In the unreachable else branch, we throw an exception
   *
   * We may be able to use an enum/sealed class instead if
   * [this issue moves along](https://youtrack.jetbrains.com/issue/KT-38750/Support-declaration-site-nonexhaustiveness-for-enums-and-sealed-classes).
   */
  @Suppress("LocalVariableName") // We want the parameter names here to be the same as the constants
  internal inline fun <ReturnT> match(
      crossinline ERROR: () -> ReturnT,
      crossinline WARN: () -> ReturnT,
      crossinline INFO: () -> ReturnT,
      crossinline DEBUG: () -> ReturnT,
      crossinline TRACE: () -> ReturnT,
  ): ReturnT {
    return when (this) {
      LogLevel.ERROR -> ERROR()
      LogLevel.WARN -> WARN()
      LogLevel.INFO -> INFO()
      LogLevel.DEBUG -> DEBUG()
      LogLevel.TRACE -> TRACE()
      else -> throw IllegalStateException("Unrecognized log level")
    }
  }
}
