package no.liflig.logging

import ch.qos.logback.classic.Level as LogbackLevel
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.LoggingEvent as BaseLogbackEvent
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializable
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.jsontype.TypeSerializer
import org.slf4j.Logger as Slf4jLogger
import org.slf4j.event.DefaultLoggingEvent as BaseSlf4jEvent
import org.slf4j.event.KeyValuePair
import org.slf4j.event.Level as Slf4jLevel
import org.slf4j.spi.LocationAwareLogger
import org.slf4j.spi.LoggingEventAware

/**
 * The purpose of [LogBuilder] is to build a log event. We could store intermediate state on
 * `LogBuilder`, and then use that to construct a log event when the builder is finished. But if we
 * instead construct the log event in-place on `LogBuilder`, we can avoid allocations on the hot
 * path.
 *
 * SLF4J has support for building log events, through the [org.slf4j.event.LoggingEvent] interface,
 * [org.slf4j.event.DefaultLoggingEvent] implementation, and [LoggingEventAware] logger interface.
 * And [LogbackLogger] implements `LoggingEventAware` - great! Except Logback uses a different event
 * format internally, so in its implementation of [LoggingEventAware.log], it has to map from the
 * SLF4J event to its own event format. This allocates a new event, defeating the purpose of
 * constructing our log event in-place on `LogBuilder`.
 *
 * So to optimize for the common SLF4J + Logback combination, we construct the log event on
 * Logback's format in [LogbackLogEvent], so we can log it directly. However, we still want to be
 * compatible with alternative SLF4J implementations, so we implement SLF4J's format in
 * [Slf4jLogEvent]. [LogEvent] is the common interface between the two, so that [LogBuilder] can
 * call this interface without having to care about the underlying implementation.
 */
@PublishedApi
internal interface LogEvent {
  fun addStringField(key: String, value: String)

  fun addJsonField(key: String, json: String)

  fun isFieldKeyAdded(key: String): Boolean

  fun log(message: String, logger: Slf4jLogger)
}

@PublishedApi
internal fun createLogEvent(level: LogLevel, cause: Throwable?, logger: Slf4jLogger): LogEvent {
  if (LOGBACK_IS_ON_CLASSPATH && logger is LogbackLogger) {
    return LogbackLogEvent(level, cause, logger)
  }

  return Slf4jLogEvent(level, cause, logger)
}

/**
 * We want to support using this library without having Logback on the classpath at all (hence we
 * mark it as an optional dependency in the POM). This is because if the user has chosen a different
 * SLF4J implementation, loading Logback can interfere with that.
 *
 * If the user has not added Logback as a dependency, the below class loading will fail, and we'll
 * stick to only using SLF4J. We cache the result in this field instead of doing the try/catch every
 * time in [createLogEvent], as that would pay the cost of the exception every time for non-Logback
 * implementations.
 */
internal val LOGBACK_IS_ON_CLASSPATH =
    try {
      Class.forName("ch.qos.logback.classic.Logger")
      true
    } catch (_: Throwable) {
      false
    }

/** Extends Logback's custom log event class to implement [LogEvent]. */
internal class LogbackLogEvent(level: LogLevel, cause: Throwable?, logger: LogbackLogger) :
    LogEvent,
    BaseLogbackEvent(
        FULLY_QUALIFIED_CLASS_NAME,
        logger,
        level.toLogback(),
        null, // message (we set this when finalizing the log)
        cause,
        null, // argArray (we don't use this)
    ) {
  override fun addStringField(key: String, value: String) {
    super.addKeyValuePair(KeyValuePair(key, value))
  }

  override fun addJsonField(key: String, json: String) {
    super.addKeyValuePair(KeyValuePair(key, RawJson(json)))
  }

  override fun isFieldKeyAdded(key: String): Boolean {
    // getKeyValuePairs may return null if no fields have been added yet
    val fields = super.getKeyValuePairs() ?: return false
    return fields.any { it.key == key }
  }

  override fun log(message: String, logger: Slf4jLogger) {
    super.setMessage(message)

    // Safe to cast here, since we only construct this event if the logger is a LogbackLogger.
    // We choose to cast instead of keeping the LogbackLogger as a field on the event, since casting
    // to a concrete class is fast, and we don't want to increase the allocated size of the event.
    (logger as LogbackLogger).callAppenders(this)
  }

  internal companion object {
    /**
     * SLF4J has the concept of a "caller boundary": the fully qualified class name of the logger
     * class that made the log. This is used by logger implementations, such as Logback, when the
     * user enables "caller info": showing the location in the source code where the log was made.
     * Logback then knows to exclude stack trace elements up to this caller boundary, since the user
     * wants to see where in _their_ code the log was made, not the location in the logging library.
     *
     * In our case, the caller boundary is in fact not [Logger], but our [LogEvent] implementations.
     * This is because all the methods on `Logger` are `inline` - so the logger method actually
     * called by user code at runtime is [LogbackLogEvent.log]/[Slf4jLogEvent.log].
     */
    internal val FULLY_QUALIFIED_CLASS_NAME = LogbackLogEvent::class.java.name
  }
}

/**
 * We use an extension function for converting a [LogLevel] to the Logback equivalent, instead of a
 * field on [LogLevel] (like we do for [Slf4jLevel]). This is to allow using this library without
 * Logback on the classpath (such as when using an alternative SLF4J implementation). In such cases,
 * loading Logback may interfere with the user's chosen SLF4J logger.
 */
internal fun LogLevel.toLogback(): LogbackLevel {
  return when (this) {
    LogLevel.INFO -> LogbackLevel.INFO
    LogLevel.WARN -> LogbackLevel.WARN
    LogLevel.ERROR -> LogbackLevel.ERROR
    LogLevel.DEBUG -> LogbackLevel.DEBUG
    LogLevel.TRACE -> LogbackLevel.TRACE
  }
}

/** Extends SLF4J's log event class to implement [LogEvent]. */
internal class Slf4jLogEvent(level: LogLevel, cause: Throwable?, logger: Slf4jLogger) :
    LogEvent, BaseSlf4jEvent(level.slf4jLevel, logger) {
  init {
    super.setThrowable(cause)
    super.setCallerBoundary(FULLY_QUALIFIED_CLASS_NAME)
    super.setTimeStamp(System.currentTimeMillis())
  }

  override fun addStringField(key: String, value: String) = super.addKeyValue(key, value)

  override fun addJsonField(key: String, json: String) = super.addKeyValue(key, RawJson(json))

  override fun isFieldKeyAdded(key: String): Boolean {
    // getKeyValuePairs may return null if no fields have been added yet
    val fields = super.getKeyValuePairs() ?: return false
    return fields.any { it.key == key }
  }

  override fun log(message: String, logger: Slf4jLogger) {
    super.setMessage(message)

    when (logger) {
      // If logger is LoggingEventAware, we can just log the event directly
      is LoggingEventAware -> logger.log(this)
      // If logger is LocationAware, we want to use that interface so the logger implementation
      // can show the correct file location of where the log was made
      is LocationAwareLogger -> logWithLocationAwareApi(logger)
      // Otherwise, we fall back to the base SLF4J Logger API
      else -> logWithBasicSlf4jApi(logger)
    }
  }

  private fun logWithLocationAwareApi(logger: LocationAwareLogger) {
    // Location-aware SLF4J API doesn't take KeyValuePair, so we must merge them into message
    val message = mergeMessageAndKeyValuePairs()
    logger.log(
        null, // marker (we don't use this)
        callerBoundary, // Fully qualified class name of class making log (set in constructor)
        level.toInt(),
        message,
        null, // argArray (we don't use this)
        throwable,
    )
  }

  private fun logWithBasicSlf4jApi(logger: Slf4jLogger) {
    // Basic SLF4J API doesn't take KeyValuePair, so we must merge them into message
    val message = mergeMessageAndKeyValuePairs()
    // level should never be null here, since we pass it in the constructor
    when (level!!) {
      // We don't assume that the SLF4J implementation accepts a `null` cause exception in the
      // overload that takes a throwable. So we only call that overload if `throwable != null`.
      Slf4jLevel.INFO ->
          if (throwable == null) logger.info(message) else logger.info(message, throwable)
      Slf4jLevel.WARN ->
          if (throwable == null) logger.warn(message) else logger.warn(message, throwable)
      Slf4jLevel.ERROR ->
          if (throwable == null) logger.error(message) else logger.error(message, throwable)
      Slf4jLevel.DEBUG ->
          if (throwable == null) logger.debug(message) else logger.debug(message, throwable)
      Slf4jLevel.TRACE ->
          if (throwable == null) logger.trace(message) else logger.trace(message, throwable)
    }
  }

  private fun mergeMessageAndKeyValuePairs(): String {
    val keyValuePairs = this.keyValuePairs
    // If there are no key-value pairs, we can just return the message as-is
    if (keyValuePairs.isNullOrEmpty()) {
      return message
    }

    val builder = StringBuilder()
    builder.append(message)

    builder.append(" [")
    keyValuePairs.forEachIndexed { index, keyValuePair ->
      builder.append(keyValuePair.key)
      builder.append('=')
      builder.append(keyValuePair.value)
      if (index != keyValuePairs.size - 1) {
        builder.append(", ")
      }
    }
    builder.append(']')

    return builder.toString()
  }

  internal companion object {
    /** See [LogbackLogEvent.FULLY_QUALIFIED_CLASS_NAME]. */
    internal val FULLY_QUALIFIED_CLASS_NAME = Slf4jLogEvent::class.java.name
  }
}

/**
 * Wrapper class for a pre-serialized JSON string. It implements [JsonSerializable] from Jackson,
 * because most JSON-outputting logger implementations will use that library to encode the logs (at
 * least `logstash-logback-encoder` for Logback does this).
 *
 * Since we use this to wrap a value that has already been serialized with `kotlinx.serialization`,
 * we simply call [JsonGenerator.writeRawValue] in [serialize] to write the JSON string as-is.
 */
@PublishedApi
internal class RawJson(private val json: String) : JsonSerializable {
  override fun toString() = json

  override fun serialize(generator: JsonGenerator, serializers: SerializerProvider) {
    generator.writeRawValue(json)
  }

  override fun serializeWithType(
      generator: JsonGenerator,
      serializers: SerializerProvider,
      typeSerializer: TypeSerializer
  ) {
    // Since we don't know what type the raw JSON is, we can only redirect to normal serialization
    serialize(generator, serializers)
  }

  override fun equals(other: Any?) = other is RawJson && other.json == this.json

  override fun hashCode() = json.hashCode()
}
