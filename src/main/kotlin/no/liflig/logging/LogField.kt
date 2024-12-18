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
 * A log field is a key-value pair for adding structured data to logs.
 *
 * When outputting logs as JSON (using
 * [logstash-logback-encoder](https://github.com/logfellow/logstash-logback-encoder)), this becomes
 * a field in the logged JSON object. That allows you to filter and query on the field in the log
 * analysis tool of your choice, in a more structured manner than if you were to just use string
 * concatenation.
 *
 * You can add a field to a log by calling [LogBuilder.addField] on one of [Logger]'s methods (see
 * example below). This serializes the value using `kotlinx.serialization`. Alternatively, if you
 * have a value that is already serialized, you can instead call [LogBuilder.addRawJsonField].
 *
 * If you want to attach fields to all logs within a scope, you can use [withLoggingContext] and
 * pass fields to it with the [field]/[rawJsonField] functions.
 *
 * Finally, you can implement the [WithLogFields] interface or use [ExceptionWithLogFields] to
 * attach fields to an exception when it's logged.
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
class LogField
@PublishedApi
internal constructor(
    /**
     * We use a "marker" for the field here instead of [KeyValuePair][org.slf4j.event.KeyValuePair],
     * as this allows us to use [RawJsonAppendingMarker] to pass pre-serialized JSON to
     * `logstash-logback-encoder`. We want this for 2 reasons:
     * 1. To use `kotlinx.serialization` field value serialization, instead of Jackson.
     *     - When we use `kotlinx.serialization` generally in our application, some of our objects
     *       may be serializable with `kotlinx` but fail to serialize with Jackson. Since we
     *       typically don't test Jackson serialization when we use `kotlinx`, such serialization
     *       failures are often not discovered before the system is deployed.
     *     - By default, when `logstash-logback-encoder` fails to serialize a log field value with
     *       Jackson, it _drops the entire log_ (!). This can make it difficult to reason about what
     *       happened to a system in production, as a log being dropped may lead you to believe that
     *       a given code path was not executed.
     *     - To avoid this issue, we always fall back to `toString()` if we fail to serialize a log
     *       field value, and never drop logs.
     * 2. To support adding fields with raw JSON ([rawJsonField]/[LogBuilder.addRawJsonField]).
     */
    @PublishedApi internal val logstashField: SingleFieldAppendingMarker,
) {
  // We don't expose this publically, as we don't necessarily want to bind ourselves to this API
  internal val key: String
    get() = logstashField.fieldName

  // We override toString, equals and hashCode manually here instead of using a data class, since we
  // don't want the data class copy/componentN methods to be part of our API.
  override fun toString() = logstashField.toString()

  override fun equals(other: Any?) = other is LogField && other.logstashField == this.logstashField

  override fun hashCode() = logstashField.hashCode()
}

/**
 * Constructs a [LogField], a key-value pair for adding structured data to logs.
 *
 * This function is made to be used with [withLoggingContext], to add fields to all logs within a
 * scope. If you just want to add a field to a single log, you should instead call
 * [LogBuilder.addField] on one of [Logger]'s methods (see example on
 * [addField][LogBuilder.addField]).
 *
 * The value is serialized using `kotlinx.serialization`, so if you pass an object here, it should
 * be annotated with [@Serializable][kotlinx.serialization.Serializable]. Alternatively, you can
 * pass your own [serializer] for the value. If serialization fails, we fall back to calling
 * `toString()` on the value.
 *
 * If you have a value that is already serialized, you should use [rawJsonField] instead.
 *
 * Certain types that `kotlinx.serialization` doesn't support natively have special-case handling
 * here, using their `toString()` representation instead:
 * - [java.time.Instant]
 * - [java.util.UUID]
 * - [java.net.URI]
 * - [java.net.URL]
 * - [java.math.BigDecimal]
 */
inline fun <reified ValueT> field(
    key: String,
    value: ValueT,
    serializer: SerializationStrategy<ValueT>? = null
): LogField {
  return LogField(createLogstashField(key, value, serializer))
}

/**
 * Implementation for [field], but without wrapping the return type in the [LogField] class. We use
 * this in [LogBuilder.addField], where we don't need the wrapper.
 */
@PublishedApi
internal inline fun <reified ValueT> createLogstashField(
    key: String,
    value: ValueT,
    serializer: SerializationStrategy<ValueT>?
): SingleFieldAppendingMarker {
  try {
    if (serializer != null) {
      val serializedValue = logFieldJson.encodeToString(serializer, value)
      return RawJsonAppendingMarker(key, serializedValue)
    }

    return when (ValueT::class) {
      // Special case for String to avoid redundant serialization
      String::class -> ObjectAppendingMarker(key, value)
      // Special cases for common types that kotlinx.serialization doesn't handle by default.
      // If more cases are added here, you should add them to the list in the docstring for `field`.
      Instant::class,
      UUID::class,
      URI::class,
      URL::class,
      BigDecimal::class -> ObjectAppendingMarker(key, value.toString())
      else -> {
        val serializedValue = logFieldJson.encodeToString(value)
        RawJsonAppendingMarker(key, serializedValue)
      }
    }
  } catch (_: Exception) {
    // We don't want to ever throw an exception from constructing a log field, which may happen if
    // serialization fails, for example. So in these cases we fall back to toString().
    return ObjectAppendingMarker(key, value.toString())
  }
}

/**
 * Constructs a [LogField], a key-value pair for adding structured data to logs, with the given
 * pre-serialized JSON value.
 *
 * This function is made to be used with [withLoggingContext], to add fields to all logs within a
 * scope. If you just want to add a field to a single log, you should instead call
 * [LogBuilder.addRawJsonField] on one of [Logger]'s methods (see example on
 * [addRawJsonField][LogBuilder.addRawJsonField]).
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
 * import no.liflig.logging.Logger
 * import no.liflig.logging.rawJsonField
 * import no.liflig.logging.withLoggingContext
 *
 * private val log = Logger {}
 *
 * fun example() {
 *   val userJson = """{"id":1,"name":"John Doe"}"""
 *
 *   withLoggingContext(rawJsonField("user", userJson)) {
 *     log.debug { "Started processing user" }
 *     // ...
 *     log.debug { "User processing ended" }
 *   }
 * }
 * ```
 *
 * This gives the following output (using `logstash-logback-encoder`):
 * ```json
 * {"message":"Started processing user","user":{"id":1,"name":"John Doe"},/* ...timestamp etc. */}
 * {"message":"User processing ended","user":{"id":1,"name":"John Doe"},/* ...timestamp etc. */}
 * ```
 */
fun rawJsonField(key: String, json: String, validJson: Boolean = false): LogField {
  return LogField(createRawJsonLogstashField(key, json, validJson))
}

/**
 * Implementation for [rawJsonField], but without wrapping the return type in the [LogField] class.
 * We use this in [LogBuilder.addRawJsonField], where we don't need the wrapper.
 */
internal fun createRawJsonLogstashField(
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
    val decoded = logFieldJson.parseToJsonElement(json)

    // If we successfully decoded the JSON, and it does not contain unescaped newlines, we can
    // return it as-is.
    if (!containsNewlines) {
      return RawJsonAppendingMarker(key, json)
    }

    // If the JSON did contain unescaped newlines, then we need to re-encode to escape them.
    val encoded = logFieldJson.encodeToString(JsonElement.serializer(), decoded)
    return RawJsonAppendingMarker(key, encoded)
  } catch (_: Exception) {
    // If we failed to decode/re-encode the JSON string, we return it as a non-JSON string.
    return ObjectAppendingMarker(key, json)
  }
}

@PublishedApi internal val logFieldJson = Json { encodeDefaults = true }
