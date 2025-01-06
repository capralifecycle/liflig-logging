package no.liflig.logging

import kotlinx.serialization.SerializationStrategy

/**
 * Class used in the logging methods on [Logger], allowing you to set a [cause] exception and
 * [add structured key-value data][field] to a log.
 *
 * ### Example
 *
 * ```
 * private val log = getLogger {}
 *
 * fun example(user: User) {
 *   try {
 *     storeUser(user)
 *   } catch (e: Exception) {
 *     // The lambda argument passed to this logger method has a LogBuilder as its receiver, which
 *     // means that you can set `LogBuilder.cause` and call `LogBuilder.field` in this scope.
 *     log.error {
 *       cause = e
 *       field("user", user)
 *       "Failed to store user in database"
 *     }
 *   }
 * }
 * ```
 */
@JvmInline // Inline value class, since we just wrap a log event
value class LogBuilder
@PublishedApi
internal constructor(
    @PublishedApi internal val logEvent: LogEvent,
) {
  /**
   * Set this if the log was caused by an exception, to include the exception message and stack
   * trace in the log.
   *
   * This property should only be set once on a single log. If you set `cause` multiple times, only
   * the first non-null exception will be kept (this is due to a limitation in Logback's
   * LoggingEvent API).
   */
  var cause: Throwable?
    set(value) = logEvent.setCause(value)
    get() = logEvent.getCause()

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
   *   val user = User(id = 1, name = "John Doe")
   *
   *   log.info {
   *     field("user", user)
   *     "Registered new user"
   *   }
   * }
   *
   * @Serializable
   * data class User(val id: Long, val name: String)
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
  inline fun <reified ValueT : Any> field(
      key: String,
      value: ValueT?,
      serializer: SerializationStrategy<ValueT>? = null,
  ) {
    if (!logEvent.isFieldKeyAdded(key)) {
      encodeFieldValue(
          value,
          serializer,
          onJson = { jsonValue -> logEvent.addField(key, RawJson(jsonValue)) },
          onString = { stringValue -> logEvent.addField(key, stringValue) },
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
   * 100% sure that the given JSON string is valid, you can set [validJson] to true.
   *
   * ### Example
   *
   * ```
   * import no.liflig.logging.getLogger
   *
   * private val log = getLogger {}
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
  fun rawJsonField(key: String, json: String, validJson: Boolean = false) {
    if (!logEvent.isFieldKeyAdded(key)) {
      validateRawJson(
          json,
          validJson,
          onValidJson = { jsonValue -> logEvent.addField(key, RawJson(jsonValue)) },
          onInvalidJson = { stringValue -> logEvent.addField(key, stringValue) },
      )
    }
  }

  /**
   * Adds the given [log field][LogField] to the log. This is useful when you have a previously
   * constructed field from the
   * [field][no.liflig.logging.field]/[rawJsonField][no.liflig.logging.rawJsonField] functions.
   * - If you want to create a new field and add it to the log, you should instead call
   *   [LogBuilder.field]
   * - If you want to add the field to all logs within a scope, you should instead use
   *   [withLoggingContext]
   */
  fun existingField(field: LogField) {
    addFieldToLog(field)
  }

  @PublishedApi
  internal fun finalize(message: String) {
    logEvent.setMessage(message)

    addFieldsFromCauseException()
  }

  /**
   * Checks if the log [cause] exception (or any of its own cause exceptions) implements the
   * [WithLogFields] interface, and if so, adds those fields.
   */
  private fun addFieldsFromCauseException() {
    // The `cause` here is the log event cause exception. But this exception may itself have a
    // `cause` exception, and that may have another one, and so on. We want to go through all these
    // exceptions to look for log fields, so we re-assign this local variable as we iterate through.
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
        exception.logFields.forEach(::addFieldToLog)
      }

      exception = exception.cause
      depth++
    }
  }

  private fun addFieldToLog(field: LogField) {
    // Don't add fields with keys that have already been added
    if (!logEvent.isFieldKeyAdded(field.key)) {
      val value = field.getValueForLog()
      if (value != null) {
        logEvent.addField(field.key, value)
      }
    }
  }

  // Kept around for backwards compatibility.
  // TODO: Remove after users have migrated.
  @Deprecated(
      "Renamed to 'field'.",
      ReplaceWith("field(key, value, serializer)"),
      DeprecationLevel.ERROR,
  )
  inline fun <reified ValueT> addField(
      key: String,
      value: ValueT,
      serializer: SerializationStrategy<ValueT>? = null,
  ) {
    field(key, value, serializer)
  }

  // Kept around for backwards compatibility.
  // TODO: Remove after users have migrated.
  @Deprecated(
      "Renamed to 'rawJsonField'.",
      ReplaceWith("rawJsonField(key, json, validJson)"),
      DeprecationLevel.ERROR,
  )
  fun addRawJsonField(key: String, json: String, validJson: Boolean = false) {
    rawJsonField(key, json, validJson)
  }

  // Kept around for backwards compatibility.
  // TODO: Remove after users have migrated.
  @Deprecated(
      "Renamed to 'existingField'.",
      ReplaceWith("existingField(field)"),
      DeprecationLevel.ERROR,
  )
  fun addPreconstructedField(field: LogField) {
    existingField(field)
  }
}
