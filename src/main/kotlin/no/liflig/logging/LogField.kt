package no.liflig.logging

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializable
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.jsontype.TypeSerializer
import java.math.BigDecimal
import java.net.URI
import java.net.URL
import java.time.Instant
import java.util.Objects
import java.util.UUID
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

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
 * Finally, you can throw or extend [ExceptionWithLogFields] to attach structured data to an
 * exception when it's logged.
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
    internal val key: String,
    internal val value: LogFieldValue,
) {
  // We override toString, equals and hashCode manually here instead of using a data class, since we
  // don't want the data class copy/componentN methods to be part of our API.
  override fun toString() = "${key}=${value}"

  override fun equals(other: Any?) =
      other is LogField && this.key == other.key && this.value == other.value

  override fun hashCode() = Objects.hash(key, value)
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
  return LogField(key, value = encodeFieldValue(value, serializer))
}

@PublishedApi
internal inline fun <reified ValueT> encodeFieldValue(
    value: ValueT,
    serializer: SerializationStrategy<ValueT>?
): LogFieldValue {
  try {
    if (value == null) {
      return RawJson.NULL
    }

    if (serializer != null) {
      val serializedValue = logFieldJson.encodeToString(serializer, value)
      return RawJson(serializedValue)
    }

    return when (ValueT::class) {
      // Special case for String to avoid redundant serialization
      String::class -> value
      // Special cases for common types that kotlinx.serialization doesn't handle by default.
      // If more cases are added here, you should add them to the list in the docstring for `field`.
      Instant::class,
      UUID::class,
      URI::class,
      URL::class,
      BigDecimal::class -> value.toString()
      else -> {
        val serializedValue = logFieldJson.encodeToString(value)
        RawJson(serializedValue)
      }
    }
  } catch (_: Exception) {
    // We don't want to ever throw an exception from constructing a log field, which may happen if
    // serialization fails, for example. So in these cases we fall back to toString().
    return value.toString()
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
 * import no.liflig.logging.getLogger
 * import no.liflig.logging.rawJsonField
 * import no.liflig.logging.withLoggingContext
 *
 * private val log = getLogger {}
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
  return LogField(key, value = rawJsonFieldValue(json, validJson))
}

internal fun rawJsonFieldValue(json: String, validJson: Boolean): LogFieldValue {
  try {
    // Some log platforms (e.g. AWS CloudWatch) use newlines as the separator between log messages.
    // So if the JSON string has unescaped newlines, we must re-parse the JSON.
    val containsNewlines = json.contains('\n')

    // If we assume the JSON is valid, and there are no unescaped newlines, we can return it as-is.
    if (validJson && !containsNewlines) {
      return RawJson(json)
    }

    // If we do not assume that the JSON is valid, we must try to decode it.
    val decoded = logFieldJson.parseToJsonElement(json)

    // If we successfully decoded the JSON, and it does not contain unescaped newlines, we can
    // return it as-is.
    if (!containsNewlines) {
      return RawJson(json)
    }

    // If the JSON did contain unescaped newlines, then we need to re-encode to escape them.
    val encoded = logFieldJson.encodeToString(JsonElement.serializer(), decoded)
    return RawJson(encoded)
  } catch (_: Exception) {
    // If we failed to decode/re-encode the JSON string, we return it as a non-JSON string.
    return json
  }
}

/**
 * A log field value is either:
 * - A [RawJson] value, serialized in [encodeFieldValue] or passed directly to [rawJsonFieldValue]
 * - A `String` or other primitive type that we assume the logger implementation can handle
 *
 * We don't use an interface or sealed class for this, to avoid allocating redundant wrapper
 * objects. We could wrap this in an inline value class, but experimenting with that proved to be
 * more cumbersome than worthwhile.
 */
internal typealias LogFieldValue = Any

/**
 * Wrapper class for a pre-serialized JSON string. It implements [JsonSerializable] from Jackson,
 * because most JSON-outputting logger implementations will use that library to encode the logs (at
 * least `logstash-logback-encoder` for Logback does this).
 *
 * Since we use this to wrap a value that has already been serialized with `kotlinx.serialization`,
 * we simply call [JsonGenerator.writeRawValue] in [serialize] to write the JSON string as-is.
 */
@PublishedApi
@JvmInline
internal value class RawJson(private val json: String) : JsonSerializable {
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

  @PublishedApi
  internal companion object {
    /**
     * SLF4J supports null values in `KeyValuePair`s, and it's up to the logger implementation for
     * how to handle it. In the case of Logback and `logstash-logback-encoder`, key-value pairs with
     * `null` values are omitted entirely. But this can be confusing for the user, since they may
     * think the log field was omitted due to some error. So in this library, we instead use a JSON
     * `null` as the value for null log fields.
     */
    @PublishedApi internal val NULL = RawJson("null")
  }
}

@PublishedApi internal val logFieldJson = Json { encodeDefaults = true }
