package no.liflig.logging

import java.math.BigDecimal
import java.net.URI
import java.net.URL
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import net.logstash.logback.marker.ObjectAppendingMarker
import net.logstash.logback.marker.RawJsonAppendingMarker
import net.logstash.logback.marker.SingleFieldAppendingMarker

/**
 * A log marker is a key-value pair to add structured context to a log event.
 *
 * If you output logs as JSON (using
 * [logstash-logback-encoder](https://github.com/logfellow/logstash-logback-encoder)), each marker
 * will be its own field in the resulting JSON. This allows you to filter and query on these fields
 * in the log analysis tool of your choice, in a more structured manner than if you were to just use
 * string concatenation.
 *
 * You can add a marker to a log by calling [LogBuilder.addMarker] on one of [Logger]'s methods (see
 * example below). This serializes the value using `kotlinx.serialization`. Alternatively, if you
 * have a value that is already serialized, you can instead call [LogBuilder.addRawJsonMarker].
 *
 * If you want to attach a marker to all logs within a scope, you can use [withLoggingContext] and
 * pass markers to it with the [marker]/[rawJsonMarker] functions.
 *
 * Finally, you can implement the [WithLogMarkers] interface or use [ExceptionWithLogMarkers] to
 * attach markers to an exception when it's logged.
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
 *     addMarker("user", user)
 *     "Registered new user"
 *   }
 * }
 *
 * @Serializable data class User(val id: Long, val name: String)
 * ```
 *
 * This gives the following output using `logstash-logback-encoder`:
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
class LogMarker
@PublishedApi
internal constructor(
    @PublishedApi internal val logstashMarker: SingleFieldAppendingMarker,
) {
  // We override toString, equals and hashCode manually here instead of using a data class, since we
  // don't want the data class copy/componentN methods to be part of our API.
  override fun toString() = logstashMarker.toString()

  override fun equals(other: Any?) =
      other is LogMarker && other.logstashMarker == this.logstashMarker

  override fun hashCode() = logstashMarker.hashCode()
}

/**
 * Constructs a [LogMarker], a key-value pair to add structured context to logs.
 *
 * This function is made to be used with [withLoggingContext], to add markers to all logs within a
 * scope. If you just want to add a marker to a single log, you should instead call
 * [LogBuilder.addMarker] on one of [Logger]'s methods (see example on [LogMarker]).
 *
 * The value is serialized using `kotlinx.serialization`, so if you pass an object here, it should
 * be annotated with [@Serializable][kotlinx.serialization.Serializable]. Alternatively, you can
 * pass your own [serializer] for the value. If serialization fails, we fall back to calling
 * `toString()` on the value.
 *
 * If you have a value that is already serialized, you should use [rawJsonMarker] instead.
 *
 * Certain types that `kotlinx.serialization` doesn't support natively have special-case handling
 * here, using their `toString()` representation instead:
 * - [java.time.Instant]
 * - [java.util.UUID]
 * - [java.net.URI]
 * - [java.net.URL]
 * - [java.math.BigDecimal]
 */
inline fun <reified ValueT> marker(
    key: String,
    value: ValueT,
    serializer: SerializationStrategy<ValueT>? = null
): LogMarker {
  return LogMarker(createLogstashMarker(key, value, serializer))
}

/**
 * Implementation for [marker], but without wrapping the return type in the [LogMarker] class - we
 * use this in [LogBuilder.addMarker], where we don't need the wrapper.
 */
@PublishedApi
internal inline fun <reified ValueT> createLogstashMarker(
    key: String,
    value: ValueT,
    serializer: SerializationStrategy<ValueT>?
): SingleFieldAppendingMarker {
  try {
    if (serializer != null) {
      val serializedValue = logMarkerJson.encodeToString(serializer, value)
      return RawJsonAppendingMarker(key, serializedValue)
    }

    return when (ValueT::class) {
      // Special case for String to avoid redundant serialization
      String::class -> ObjectAppendingMarker(key, value)
      // Special cases for common types that kotlinx.serialization doesn't handle by default.
      // If more cases are added here, you should add them to the list in the docstring for `marker`
      Instant::class,
      UUID::class,
      URI::class,
      URL::class,
      BigDecimal::class -> ObjectAppendingMarker(key, value.toString())
      else -> {
        val serializedValue = logMarkerJson.encodeToString(value)
        RawJsonAppendingMarker(key, serializedValue)
      }
    }
  } catch (_: Exception) {
    // We don't want to ever throw an exception from constructing a marker, which may happen if
    // serialization fails, for example. So in these cases we fall back to toString().
    return ObjectAppendingMarker(key, value.toString())
  }
}

/**
 * Constructs a [LogMarker], a key-value pair to add structured context to logs, with the given
 * pre-serialized JSON value.
 *
 * This function is made to be used with [withLoggingContext], to add markers to all logs within a
 * scope. If you just want to add a marker to a single log, you should instead call
 * [LogBuilder.addRawJsonMarker] on one of [Logger]'s methods (see example on
 * [addRawJsonMarker][LogBuilder.addRawJsonMarker]).
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
 * import no.liflig.logging.rawJsonMarker
 * import no.liflig.logging.withLoggingContext
 *
 * private val log = Logger {}
 *
 * fun example() {
 *   val userJson = """{"id":1,"name":"John Doe"}"""
 *
 *   withLoggingContext(rawJsonMarker("user", userJson)) {
 *     log.debug { "Started processing user" }
 *     // ...
 *     log.debug { "User processing ended" }
 *   }
 * }
 * ```
 *
 * This gives the following output using `logstash-logback-encoder`:
 * ```json
 * {"message":"Started processing user","user":{"id":1,"name":"John Doe"},/* ...timestamp etc. */}
 * {"message":"User processing ended","user":{"id":1,"name":"John Doe"},/* ...timestamp etc. */}
 * ```
 */
fun rawJsonMarker(key: String, json: String, validJson: Boolean = false): LogMarker {
  return LogMarker(createRawJsonLogstashMarker(key, json, validJson))
}

/**
 * Implementation for [rawJsonMarker], but without wrapping the return type in the [LogMarker] class
 * - we use this in [LogBuilder.addRawJsonMarker], where we don't need the wrapper.
 */
internal fun createRawJsonLogstashMarker(
    key: String,
    json: String,
    validJson: Boolean
): SingleFieldAppendingMarker {
  try {
    // Some log platforms (e.g. AWS CloudWatch) use newlines as the separator between log messages.
    // So if the JSON string has unescaped newlines, we must re-parse the JSON.
    val containsNewlines = json.contains('\n')

    // If we assume the JSON is valid, and there are no unescaped newlines, we can return it as-is.
    if (validJson && !containsNewlines) {
      return RawJsonAppendingMarker(key, json)
    }

    // If we do not assume that the JSON is valid, we must try to decode it.
    val decoded = logMarkerJson.parseToJsonElement(json)

    // If we successfully decoded the JSON, and it does not contain unescaped newlines, we can
    // return it as-is.
    if (!containsNewlines) {
      return RawJsonAppendingMarker(key, json)
    }

    // If the JSON did contain unescaped newlines, then we need to re-encode to escape them.
    val encoded = logMarkerJson.encodeToString(JsonElement.serializer(), decoded)
    return RawJsonAppendingMarker(key, encoded)
  } catch (_: Exception) {
    // If we failed to decode/re-encode the JSON string, we return it as a non-JSON string.
    return ObjectAppendingMarker(key, json)
  }
}

@PublishedApi internal val logMarkerJson = Json { encodeDefaults = true }
