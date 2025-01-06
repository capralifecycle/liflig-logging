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
 * You can add a field to a log by calling [LogBuilder.field] on one of [Logger]'s methods (see
 * example below). This serializes the value using `kotlinx.serialization`. Alternatively, if you
 * have a value that is already serialized, you can instead call [LogBuilder.rawJsonField].
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
sealed class LogField {
  internal abstract val key: String
  internal abstract val value: String

  /**
   * [JsonLogField] adds a suffix ([LoggingContext.JSON_FIELD_KEY_SUFFIX]) to the key in the logging
   * context to identify the value as raw JSON (so we can write the JSON unescaped in
   * [LoggingContextJsonFieldWriter]).
   */
  internal abstract val keyForLoggingContext: String

  /**
   * Returns null if the field should not be included in the log (used by
   * [StringLogFieldFromContext]/[JsonLogFieldFromContext] to exclude fields that are already in the
   * logging context).
   */
  internal abstract fun getValueForLog(): LogFieldValue?

  override fun toString(): String = "${key}=${value}"

  override fun equals(other: Any?): Boolean =
      other is LogField && this.key == other.key && this.value == other.value

  override fun hashCode(): Int = Objects.hash(key, value)
}

@PublishedApi
internal class StringLogField(override val key: String, override val value: String) : LogField() {
  override val keyForLoggingContext: String
    get() = key

  override fun getValueForLog() = value
}

@PublishedApi
internal class JsonLogField(override val key: String, override val value: String) : LogField() {
  override val keyForLoggingContext: String =
      if (USING_LOGGING_CONTEXT_JSON_FIELD_WRITER) key + LoggingContext.JSON_FIELD_KEY_SUFFIX
      else key

  override fun getValueForLog() = RawJson(value)

  internal companion object {
    init {
      ensureLoggerImplementationIsLoaded()
    }
  }
}

/**
 * Constructs a [LogField], a key-value pair for adding structured data to logs.
 *
 * This function is made to be used with [withLoggingContext], to add fields to all logs within a
 * scope. If you just want to add a field to a single log, you should instead call
 * [LogBuilder.field] on one of [Logger]'s methods ([see example][LogBuilder.field]).
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
inline fun <reified ValueT : Any> field(
    key: String,
    value: ValueT?,
    serializer: SerializationStrategy<ValueT>? = null
): LogField {
  return encodeFieldValue(
      value,
      serializer,
      onJson = { jsonValue -> JsonLogField(key, jsonValue) },
      onString = { stringValue -> StringLogField(key, stringValue) },
  )
}

/**
 * Encodes the given value to JSON, calling [onJson] with the result. If we failed to encode to
 * JSON, or the value was already a string (or one of the types with special handling as explained
 * on [field]'s docstring), we fall back to its `toString` representation and call [onString].
 *
 * We take callbacks for the different results here instead of returning a return value. This is
 * because we use this in both [field] and [LogBuilder.field], and they want to do different things
 * with the encoded value:
 * - [field] constructs a [StringLogField] or [JsonLogField] with it
 * - [LogBuilder.field] passes the value to [LogEvent.addField], wrapping JSON values in [RawJson]
 *
 * If we used a return value here, we would have to wrap it in an object to convey whether it was
 * encoded to JSON or just a plain string, which requires an allocation. By instead taking callbacks
 * and making the function `inline`, we pay no extra cost.
 */
@PublishedApi
internal inline fun <reified ValueT : Any, ReturnT> encodeFieldValue(
    value: ValueT?,
    serializer: SerializationStrategy<ValueT>?,
    onJson: (String) -> ReturnT,
    onString: (String) -> ReturnT,
): ReturnT {
  try {
    if (value == null) {
      return onJson(RawJson.NULL)
    }

    if (serializer != null) {
      val serializedValue = logFieldJson.encodeToString(serializer, value)
      return onJson(serializedValue)
    }

    return when (ValueT::class) {
      // Special case for String to avoid redundant serialization
      String::class -> onString(value as String)
      // Special cases for common types that kotlinx.serialization doesn't handle by default.
      // If more cases are added here, you should add them to the list in the docstring for `field`.
      Instant::class,
      UUID::class,
      URI::class,
      URL::class,
      BigDecimal::class -> onString(value.toString())
      else -> {
        val serializedValue = logFieldJson.encodeToString(value)
        onJson(serializedValue)
      }
    }
  } catch (_: Exception) {
    // We don't want to ever throw an exception from constructing a log field, which may happen if
    // serialization fails, for example. So in these cases we fall back to toString().
    return onString(value.toString())
  }
}

/**
 * Constructs a [LogField], a key-value pair for adding structured data to logs, with the given
 * pre-serialized JSON value.
 *
 * This function is made to be used with [withLoggingContext], to add fields to all logs within a
 * scope. If you just want to add a field to a single log, you should instead call
 * [LogBuilder.rawJsonField] on one of [Logger]'s methods (see example on
 * [addRawJsonField][LogBuilder.rawJsonField]).
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
  return validateRawJson(
      json,
      validJson,
      onValidJson = { jsonValue -> JsonLogField(key, jsonValue) },
      onInvalidJson = { stringValue -> StringLogField(key, stringValue) },
  )
}

/**
 * Validates that the given raw JSON string is valid JSON, calling [onValidJson] if it is, or
 * [onInvalidJson] if it's not.
 *
 * We take lambdas here instead of returning a value, for the same reason as [encodeFieldValue]: we
 * use this in both [rawJsonField] and [LogBuilder.rawJsonField].
 */
internal inline fun <ReturnT> validateRawJson(
    json: String,
    validJson: Boolean,
    onValidJson: (String) -> ReturnT,
    onInvalidJson: (String) -> ReturnT
): ReturnT {
  try {
    // Some log platforms (e.g. AWS CloudWatch) use newlines as the separator between log messages.
    // So if the JSON string has unescaped newlines, we must re-parse the JSON.
    val containsNewlines = json.contains('\n')

    // If we assume the JSON is valid, and there are no unescaped newlines, we can return it as-is.
    if (validJson && !containsNewlines) {
      return onValidJson(json)
    }

    // If we do not assume that the JSON is valid, we must try to decode it.
    val decoded = logFieldJson.parseToJsonElement(json)

    // If we successfully decoded the JSON, and it does not contain unescaped newlines, we can
    // return it as-is.
    if (!containsNewlines) {
      return onValidJson(json)
    }

    // If the JSON did contain unescaped newlines, then we need to re-encode to escape them.
    val encoded = logFieldJson.encodeToString(JsonElement.serializer(), decoded)
    return onValidJson(encoded)
  } catch (_: Exception) {
    // If we failed to decode/re-encode the JSON string, we return it as a non-JSON string.
    return onInvalidJson(json)
  }
}

/**
 * A log field value is either a [String] or a [RawJson] instance.
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
    @PublishedApi internal const val NULL = "null"
  }
}

@PublishedApi
internal val logFieldJson = Json {
  encodeDefaults = true
  ignoreUnknownKeys = true
}
