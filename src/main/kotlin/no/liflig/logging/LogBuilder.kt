package no.liflig.logging

import ch.qos.logback.classic.spi.LoggingEvent as LogbackEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import kotlinx.serialization.SerializationStrategy
import net.logstash.logback.marker.SingleFieldAppendingMarker

/**
 * Class used in the logging methods on [Logger], allowing you to set a [cause] exception and
 * [add structured key-value data][addField] to a log.
 *
 * ### Example
 *
 * ```
 * private val log = Logger {}
 *
 * fun example(user: User) {
 *   try {
 *     storeUser(user)
 *   } catch (e: Exception) {
 *     // The lambda argument passed to this logger method has a LogBuilder as its receiver, which
 *     // means that you can set `LogBuilder.cause` and call `LogBuilder.addField` in this scope.
 *     log.error {
 *       cause = e
 *       addField("user", user)
 *       "Failed to store user in database"
 *     }
 *   }
 * }
 * ```
 */
@JvmInline // Inline value class, since we just wrap a Logback logging event
value class LogBuilder
internal constructor(
    @PublishedApi internal val logEvent: LogbackEvent,
) {
  /**
   * Set this if the log was caused by an exception, to include the exception message and stack
   * trace in the log.
   */
  var cause: Throwable?
    set(value) = logEvent.setThrowableProxy(ThrowableProxy(value))
    get() = (logEvent.throwableProxy as? ThrowableProxy)?.throwable

  /**
   * Adds a [log field][LogField] (structured key-value data) to the log.
   *
   * When outputting logs as JSON, this becomes a field in the logged JSON object (see example
   * below). This allows you to filter and query on the field in the log analysis tool of your
   * choice, in a more structured manner than if you were to just use string concatenation.
   *
   * The value is serialized using `kotlinx.serialization`, so if you pass an object here, you
   * should make sure it is annotated with [@Serializable][kotlinx.serialization.Serializable].
   * Alternatively, you can pass your own serializer for the value. If serialization fails, we fall
   * back to calling `toString()` on the value.
   *
   * If you have a value that is already serialized, you should use [addRawJsonField] instead.
   *
   * ### Example
   *
   * ```
   * import no.liflig.logging.Logger
   * import kotlinx.serialization.Serializable
   *
   * private val log = Logger {}
   *
   * fun example() {
   *   val user = User(id = 1, name = "John Doe")
   *
   *   log.info {
   *     addField("user", user)
   *     "Registered new user"
   *   }
   * }
   *
   * @Serializable data class User(val id: Long, val name: String)
   * ```
   *
   * This gives the following output (using `logstash-logback-encoder`):
   * ```json
   * {
   *   "message": "Registered new user",
   *   "user": {
   *     "id": "1",
   *     "name": "John Doe"
   *   },
   *   // ...timestamp etc.
   * }
   * ```
   */
  inline fun <reified ValueT> addField(
      key: String,
      value: ValueT,
      serializer: SerializationStrategy<ValueT>? = null,
  ) {
    if (!keyAdded(key)) {
      logEvent.addMarker(createLogstashField(key, value, serializer))
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
   * 100% sure that the given JSON string is valid, you can set [validJson] to true.
   *
   * ### Example
   *
   * ```
   * import no.liflig.logging.Logger
   *
   * private val log = Logger {}
   *
   * fun example() {
   *   val userJson = """{"id":1,"name":"John Doe"}"""
   *
   *   log.info {
   *     addRawJsonField("user", userJson)
   *     "Registered new user"
   *   }
   * }
   * ```
   *
   * This gives the following output (using `logstash-logback-encoder`):
   * ```json
   * {"message":"Registered new user","user":{"id":1,"name":"John Doe"},/* ...timestamp etc. */}
   * ```
   */
  fun addRawJsonField(key: String, json: String, validJson: Boolean = false) {
    if (!keyAdded(key)) {
      logEvent.addMarker(createRawJsonLogstashField(key, json, validJson))
    }
  }

  /**
   * Adds the given [log field][LogField] to the log. This is useful when you have a previously
   * constructed field from the [field][no.liflig.logging.field]/[rawJsonField] functions.
   * - If you want to create a new field and add it to the log, you should instead call [addField]
   * - If you want to add the field to all logs within a scope, you should instead use
   *   [withLoggingContext]
   */
  fun addPreconstructedField(field: LogField) {
    if (!keyAdded(field.key)) {
      logEvent.addMarker(field.logstashField)
    }
  }

  /** Adds log fields from [withLoggingContext]. */
  internal fun addFieldsFromContext() {
    // Add context fields in reverse, so newest field shows first
    getLogFieldsFromContext().forEachReversed { logstashField ->
      // Don't add fields with keys that have already been added
      if (!keyAdded(logstashField.fieldName)) {
        logEvent.addMarker(logstashField)
      }
    }
  }

  /**
   * Checks if the log [cause] exception (or any of its own cause exceptions) implements the
   * [WithLogFields] interface, and if so, adds those fields.
   */
  internal fun addFieldsFromCauseException() {
    // The `cause` here is the log event cause exception. But this exception may itself have a
    // `cause` exception, and that may have another one, and so on. We want to go through all these
    // exceptions to look for log field, so we re-assign this local variable as we iterate through.
    var exception = cause
    // Limit the depth of cause exceptions, so we don't expose ourselves to infinite loops.
    // This can happen if:
    // - exception1.cause -> exception2
    // - exception2.cause -> exception3
    // - exception3.cause -> exception1
    // We set max depth to 10, which should be high enough to not affect real users.
    var depth = 0
    while (exception != null && depth < 10) {
      if (exception is WithLogFields) {
        exception.logFields.forEach { field ->
          // Don't add fields with keys that have already been added
          if (!keyAdded(field.key)) {
            logEvent.addMarker(field.logstashField)
          }
        }
      }

      exception = exception.cause
      depth++
    }
  }

  @PublishedApi
  internal fun keyAdded(key: String): Boolean {
    /** [LogbackEvent.markerList] can be null if no fields have been added yet. */
    val addedFields = logEvent.markerList ?: return false

    return addedFields.any { logstashField ->
      logstashField is SingleFieldAppendingMarker && logstashField.fieldName == key
    }
  }
}
