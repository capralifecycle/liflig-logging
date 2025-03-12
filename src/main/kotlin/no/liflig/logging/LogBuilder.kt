package no.liflig.logging

import kotlinx.serialization.SerializationStrategy

/**
 * Class used in the logging methods on [Logger], allowing you to add structured key-value data to
 * the log by calling the [field] and [rawJsonField] methods.
 *
 * This class is given as a receiver to the lambda arguments on `Logger`'s methods, which lets you
 * call its methods directly in the scope of that lambda. This is a common technique for creating
 * _type-safe builders_ in Kotlin (see the
 * [Kotlin docs](https://kotlinlang.org/docs/lambdas.html#function-literals-with-receiver) for more
 * on this).
 *
 * ### Example
 *
 * ```
 * private val log = getLogger {}
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
@JvmInline // Inline value class, since we just wrap a log event
public value class LogBuilder
@PublishedApi
internal constructor(
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
   * should make sure it is annotated with [@Serializable][kotlinx.serialization.Serializable].
   * Alternatively, you can pass your own [serializer] for the value. If serialization fails, we
   * fall back to calling `toString()` on the value.
   *
   * If you have a value that is already serialized, you should use [rawJsonField] instead.
   *
   * ### Example
   *
   * ```
   * import no.liflig.logging.getLogger
   * import kotlinx.serialization.Serializable
   *
   * private val log = getLogger {}
   *
   * fun example() {
   *   val event = Event(id = 1001, type = EventType.ORDER_PLACED)
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
   *     "id": 1001,
   *     "type": "ORDER_PLACED"
   *   },
   *   // ...timestamp etc.
   * }
   * ```
   */
  public inline fun <reified ValueT : Any> field(
      key: String,
      value: ValueT?,
      serializer: SerializationStrategy<ValueT>? = null,
  ) {
    if (!logEvent.isFieldKeyAdded(key)) {
      encodeFieldValue(
          value,
          serializer,
          onJson = { jsonValue -> logEvent.addJsonField(key, jsonValue) },
          onString = { stringValue -> logEvent.addStringField(key, stringValue) },
      )
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
   * private val log = getLogger {}
   *
   * fun example() {
   *   val eventJson = """{"id":1001,"type":"ORDER_PLACED"}"""
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
   *   "event": {"id":1001,"type":"ORDER_PLACED"},
   *   // ...timestamp etc.
   * }
   * ```
   *
   * @param validJson Set this true if you are 100% sure that [json] is valid JSON, and you want to
   *   save the performance cost of validating it.
   */
  public fun rawJsonField(key: String, json: String, validJson: Boolean = false) {
    if (!logEvent.isFieldKeyAdded(key)) {
      validateRawJson(
          json,
          isValid = validJson,
          onValidJson = { jsonValue -> logEvent.addJsonField(key, jsonValue) },
          onInvalidJson = { stringValue -> logEvent.addStringField(key, stringValue) },
      )
    }
  }

  /**
   * Adds the given [log field][LogField] to the log. This is useful when you have a previously
   * constructed field from the
   * [field][no.liflig.logging.field]/[rawJsonField][no.liflig.logging.rawJsonField] top-level
   * functions, that you want to add to a single log.
   * - If you want to create a new field and add it to the log, you should instead call
   *   [LogBuilder.field]
   * - If you want to add the field to all logs within a scope, you should instead use
   *   [withLoggingContext]
   */
  public fun existingField(field: LogField) {
    addField(field)
  }

  /**
   * Checks if the log [cause] exception (or any of its own cause exceptions) implements the
   * [WithLogFields] interface, and if so, adds those fields to the log.
   */
  @PublishedApi
  internal fun addFieldsFromCauseException(cause: Throwable) {
    // The `cause` here is the log event cause exception. But this exception may itself have a
    // `cause` exception, and that may have another one, and so on. We want to go through all these
    // exceptions to look for log fields, so we re-assign this local variable as we iterate through.
    var exception: Throwable? = cause
    // Limit the depth of cause exceptions, so we don't expose ourselves to infinite loops.
    // This can happen if:
    // - exception1.cause -> exception2
    // - exception2.cause -> exception3
    // - exception3.cause -> exception1
    // We set max depth to 10, which should be high enough to not affect real users.
    var depth = 0
    while (exception != null && depth < 10) {
      if (exception is WithLogFields) {
        exception.logFields.forEach(::addField)
      }

      exception = exception.cause
      depth++
    }
  }

  private fun addField(field: LogField) {
    // Don't add fields with keys that have already been added
    if (!logEvent.isFieldKeyAdded(field.key) && field.includeInLog()) {
      when (field) {
        is JsonLogField -> logEvent.addJsonField(field.key, field.value)
        is StringLogField -> logEvent.addStringField(field.key, field.value)
      }
    }
  }
}
