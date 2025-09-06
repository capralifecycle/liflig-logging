package no.liflig.logging

import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer
import org.slf4j.Logger as Slf4jLogger

/**
 * Class used in the logging methods on [Logger], allowing you to add structured key-value data to
 * the log by calling the [field] and [rawJsonField] methods.
 *
 * This class is provided as a receiver for the `buildLog` lambda parameter on `Logger`'s methods,
 * which lets you call its methods directly in the scope of the lambda. This is a common pattern for
 * creating _type-safe builders_ in Kotlin (see the
 * [Kotlin docs](https://kotlinlang.org/docs/lambdas.html#function-literals-with-receiver) for more
 * on this).
 *
 * ### Example
 *
 * ```
 * private val log = getLogger()
 *
 * fun example(event: Event) {
 *   log.info {
 *     // This lambda gets a LogBuilder receiver, which means that we can call `LogBuilder.field`
 *     // directly in this scope
 *     field("event", event)
 *     "Processing event"
 *   }
 * }
 * ```
 */
@JvmInline // Inline value class, to wrap the underlying log event without overhead
public value class LogBuilder
// TODO: @PublishedApi kept for backwards compatibility. Remove once users have migrated
@PublishedApi
internal constructor(
    // TODO: @PublishedApi kept for backwards compatibility. Remove once users have migrated
    @PublishedApi internal val logEvent: LogEvent,
) {
  /**
   * Adds a [log field][LogField] (structured key-value data) to the log.
   *
   * When outputting logs as JSON, this becomes a field in the logged JSON object (see example
   * below). This allows you to filter and query on the field in the log analysis tool of your
   * choice, in a more structured manner than if you were to just use string concatenation.
   *
   * The value is serialized using `kotlinx.serialization`, so if you pass an object here, you
   * should make sure it is annotated with [@Serializable][kotlinx.serialization.Serializable]. If
   * serialization fails, we fall back to calling `toString()` on the value.
   *
   * If you want to specify the serializer for the value explicitly, you can call the overload of
   * this method that takes a [SerializationStrategy] as a third parameter. That is also useful for
   * cases where you can't call this method with a reified type parameter.
   *
   * If you have a value that is already serialized, you should use [rawJsonField] instead.
   *
   * ### Example
   *
   * ```
   * import no.liflig.logging.getLogger
   * import kotlinx.serialization.Serializable
   *
   * private val log = getLogger()
   *
   * fun example() {
   *   val event = Event(id = 1000, type = EventType.ORDER_PLACED)
   *
   *   log.info {
   *     field("event", event)
   *     "Processing event"
   *   }
   * }
   *
   * @Serializable
   * data class Event(val id: Long, val type: EventType)
   *
   * enum class EventType {
   *   ORDER_PLACED,
   *   ORDER_UPDATED,
   * }
   * ```
   *
   * This gives the following output (using `logstash-logback-encoder`):
   * ```json
   * {
   *   "message": "Processing event",
   *   "event": {
   *     "id": 1000,
   *     "type": "ORDER_PLACED"
   *   },
   *   // ...timestamp etc.
   * }
   * ```
   *
   * ### Special-case handling for common types
   *
   * Certain types that `kotlinx.serialization` doesn't support natively have special-case handling
   * here, using their `toString()` representation instead:
   * - `java.time.Instant`
   * - `java.util.UUID`
   * - `java.net.URI`
   * - `java.net.URL`
   * - `java.math.BigDecimal`
   */
  public inline fun <reified ValueT> field(key: String, value: ValueT) {
    try {
      addLogFieldOfType(key, value, typeOf<ValueT>())
    } catch (_: Exception) {
      // Falls back to `toString()` if serialization fails
      addStringLogField(key, value)
    }
  }

  /**
   * We split this out from [field] to reduce the amount of inlined code (more inlined code ->
   * bigger code size -> possibly worse performance, and also more internal API that must be exposed
   * with `@PublishedApi`).
   */
  @PublishedApi
  internal fun addLogFieldOfType(
      key: String,
      value: Any?,
      valueType: KType,
  ) {
    when {
      /** See [JSON_NULL_VALUE] for why we handle nulls like this. */
      value == null -> {
        logEvent.addJsonField(key, JSON_NULL_VALUE)
      }
      // Special case for String, to avoid redundant serialization
      value is String -> {
        logEvent.addStringField(key, value)
      }
      // Special case for common types that kotlinx.serialization doesn't handle by default
      fieldValueShouldUseToString(value) -> {
        logEvent.addStringField(key, value.toString())
      }
      // Try to serialize with kotlinx.serialization - if it throws an exception, the `field` method
      // will catch it and fall back to `createStringLogField`
      else -> {
        val serializer = LOG_FIELD_JSON_FORMAT.serializersModule.serializer(valueType)
        val serializedValue = LOG_FIELD_JSON_FORMAT.encodeToString(serializer, value)
        logEvent.addJsonField(key, serializedValue)
      }
    }
  }

  /** `toString()` fallback for the log field value when [addLogFieldOfType] fails. */
  @PublishedApi
  internal fun addStringLogField(key: String, value: Any?) {
    if (value == null) {
      logEvent.addJsonField(key, JSON_NULL_VALUE)
    } else {
      logEvent.addStringField(key, value.toString())
    }
  }

  /**
   * Adds a [log field][LogField] (structured key-value data) to the log.
   *
   * When outputting logs as JSON, this becomes a field in the logged JSON object (see example
   * below). This allows you to filter and query on the field in the log analysis tool of your
   * choice, in a more structured manner than if you were to just use string concatenation.
   *
   * The value is serialized using `kotlinx.serialization`, so if you pass an object here, you
   * should make sure it is annotated with [@Serializable][kotlinx.serialization.Serializable]. If
   * serialization fails, we fall back to calling `toString()` on the value.
   *
   * If you want to specify the serializer for the value explicitly, you can call the overload of
   * this method that takes a [SerializationStrategy] as a third parameter. That is also useful for
   * cases where you can't call this method with a reified type parameter.
   *
   * If you have a value that is already serialized, you should use [rawJsonField] instead.
   *
   * ### Example
   *
   * ```
   * import no.liflig.logging.getLogger
   * import kotlinx.serialization.Serializable
   *
   * private val log = getLogger()
   *
   * fun example() {
   *   val event = Event(id = 1000, type = EventType.ORDER_PLACED)
   *
   *   log.info {
   *     field("event", event)
   *     "Processing event"
   *   }
   * }
   *
   * @Serializable
   * data class Event(val id: Long, val type: EventType)
   *
   * enum class EventType {
   *   ORDER_PLACED,
   *   ORDER_UPDATED,
   * }
   * ```
   *
   * This gives the following output (using `logstash-logback-encoder`):
   * ```json
   * {
   *   "message": "Processing event",
   *   "event": {
   *     "id": 1000,
   *     "type": "ORDER_PLACED"
   *   },
   *   // ...timestamp etc.
   * }
   * ```
   *
   * ### Special-case handling for common types
   *
   * Certain types that `kotlinx.serialization` doesn't support natively have special-case handling
   * here, using their `toString()` representation instead:
   * - `java.time.Instant`
   * - `java.util.UUID`
   * - `java.net.URI`
   * - `java.net.URL`
   * - `java.math.BigDecimal`
   */
  public fun <ValueT : Any> field(
      key: String,
      value: ValueT?,
      serializer: SerializationStrategy<ValueT>
  ) {
    try {
      if (value == null) {
        logEvent.addJsonField(key, JSON_NULL_VALUE)
      } else {
        val serializedValue = LOG_FIELD_JSON_FORMAT.encodeToString(serializer, value)
        logEvent.addJsonField(key, serializedValue)
      }
    } catch (_: Exception) {
      // Falls back to `toString()` if serialization fails
      addStringLogField(key, value)
    }
  }

  /**
   * Adds a [log field][LogField] (structured key-value data) to the log, with the given
   * pre-serialized JSON value.
   *
   * When outputting logs as JSON, this becomes a field in the logged JSON object (see example
   * below). This allows you to filter and query on the field in the log analysis tool of your
   * choice, in a more structured manner than if you were to just use string concatenation.
   *
   * By default, this function checks that the given JSON string is actually valid JSON. The reason
   * for this is that giving raw JSON to our log encoder when it is not in fact valid JSON can break
   * our logs. So if the given JSON string is not valid JSON, we escape it as a string. If you are
   * 100% sure that the given JSON string is valid and you want to skip this check, you can set
   * [validJson] to true.
   *
   * ### Example
   *
   * ```
   * import no.liflig.logging.getLogger
   *
   * private val log = getLogger()
   *
   * fun example() {
   *   val eventJson = """{"id":1000,"type":"ORDER_PLACED"}"""
   *
   *   log.info {
   *     rawJsonField("event", eventJson)
   *     "Processing event"
   *   }
   * }
   * ```
   *
   * This gives the following output (using `logstash-logback-encoder`):
   * ```json
   * {
   *   "message": "Processing event",
   *   "event": {"id":1000,"type":"ORDER_PLACED"},
   *   // ...timestamp etc.
   * }
   * ```
   *
   * @param validJson Set this true if you are 100% sure that [json] is valid JSON, and you want to
   *   save the performance cost of validating it.
   */
  public fun rawJsonField(key: String, json: String, validJson: Boolean = false) {
    validateRawJson(
        json,
        isValid = validJson,
        onValidJson = { jsonValue -> logEvent.addJsonField(key, jsonValue) },
        onInvalidJson = { stringValue -> logEvent.addStringField(key, stringValue) },
    )
  }

  /**
   * Adds the given [log field][LogField] to the log. This is useful when you have a previously
   * constructed field from the
   * [field][no.liflig.logging.field]/[rawJsonField][no.liflig.logging.rawJsonField] top-level
   * functions, that you want to add to a single log.
   * - If you want to create a new field and add it to the log, you should instead use
   *   [LogBuilder.field] to create the field in-place
   * - If you want to add a field to all logs in a scope, you should instead use
   *   [withLoggingContext]
   */
  public fun addField(field: LogField) {
    if (field.isJson) {
      logEvent.addJsonField(field.key, field.value)
    } else {
      logEvent.addStringField(field.key, field.value)
    }
  }

  /**
   * Adds the given [log fields][LogField] to the log. This is useful when you have a collection of
   * previously constructed fields from the
   * [field][no.liflig.logging.field]/[rawJsonField][no.liflig.logging.rawJsonField] top-level
   * functions, that you want to add to a single log.
   * - If you want to create new fields and add them to the log, you should instead use
   *   [LogBuilder.field] to create the fields in-place
   * - If you want to add fields to all logs in a scope, you should instead use [withLoggingContext]
   */
  public fun addFields(fields: Collection<LogField>) {
    for (field in fields) {
      addField(field)
    }
  }

  internal fun addFields(fields: Array<out LogField>) {
    for (field in fields) {
      addField(field)
    }
  }

  /**
   * Checks if the log cause exception (or any of its own cause exceptions) implements the
   * [HasLoggingContext] interface, and if so, adds those fields to the log.
   */
  internal fun setCause(cause: Throwable, logger: Slf4jLogger) {
    logEvent.setCause(cause, logger, this)

    if (!logEvent.handlesExceptionTreeTraversal()) {
      traverseExceptionTreeForLogFields(root = cause)
    }
  }

  /**
   * We keep this in a separate method from [setCause], because [traverseExceptionTree] is a big
   * inline function, and we don't want it to blow up the code size of `setCause` for the common
   * case of [LogEvent.handlesExceptionTreeTraversal] returning true.
   */
  private fun traverseExceptionTreeForLogFields(root: Throwable) {
    traverseExceptionTree(root, action = ::addFieldsFromException)
  }

  internal fun addFieldsFromException(exception: Throwable) {
    when (exception) {
      is ExceptionWithLoggingContext -> {
        exception.addFieldsToLog(this)
      }
      is LoggingContextProvider -> {
        exception.addFieldsToLog(this)
      }
      is HasLoggingContext -> {
        addFields(exception.logFields)
      }
    }
  }

  @Deprecated("Replaced by new 'addFields' overload that takes a Collection instead of an Iterable")
  public fun addFields(fields: Iterable<LogField>) {
    for (field in fields) {
      addField(field)
    }
  }

  // TODO: Kept for backwards compatibility. Remove once users have migrated
  @Suppress("unused")
  @PublishedApi
  internal fun addFieldsFromCauseException(cause: Throwable) {
    if (!logEvent.handlesExceptionTreeTraversal()) {
      traverseExceptionTreeForLogFields(root = cause)
    }
  }
}

@PublishedApi
internal fun createLogBuilder(level: LogLevel, logger: Logger): LogBuilder {
  return LogBuilder(createLogEvent(level, logger.underlyingLogger))
}
