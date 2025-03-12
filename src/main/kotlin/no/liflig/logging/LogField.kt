package no.liflig.logging

import java.math.BigDecimal
import java.net.URI
import java.net.URL
import java.time.Instant
import java.util.Objects
import java.util.UUID
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

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
public sealed class LogField {
  internal abstract val key: String
  internal abstract val value: String
  /**
   * [JsonLogField] adds a suffix ([LOGGING_CONTEXT_JSON_KEY_SUFFIX]) to the key in the logging
   * context to identify the value as raw JSON (so we can write the JSON unescaped in
   * [LoggingContextJsonFieldWriter]).
   */
  internal abstract val keyForLoggingContext: String

  /**
   * Returns false if the field should not be included in the log (used by
   * [StringLogFieldFromContext]/[JsonLogFieldFromContext] to exclude fields that are already in the
   * logging context).
   */
  internal open fun includeInLog(): Boolean = true

  override fun toString(): String = "${key}=${value}"

  override fun equals(other: Any?): Boolean =
      other is LogField && this.key == other.key && this.value == other.value

  override fun hashCode(): Int = Objects.hash(key, value)
}

@PublishedApi
internal open class StringLogField(
    override val key: String,
    override val value: String,
) : LogField() {
  override val keyForLoggingContext: String
    get() = key
}

@PublishedApi
internal open class JsonLogField(
    override val key: String,
    override val value: String,
    override val keyForLoggingContext: String =
        if (ADD_JSON_SUFFIX_TO_LOGGING_CONTEXT_KEYS) {
          key + LOGGING_CONTEXT_JSON_KEY_SUFFIX
        } else {
          key
        }
) : LogField() {
  @PublishedApi
  internal companion object {
    /**
     * SLF4J supports null values in `KeyValuePair`s, and it's up to the logger implementation for
     * how to handle it. In the case of Logback and `logstash-logback-encoder`, key-value pairs with
     * `null` values are omitted entirely. But this can be confusing for the user, since they may
     * think the log field was omitted due to some error. So in this library, we instead use a JSON
     * `null` as the value for null log fields.
     */
    @PublishedApi internal const val NULL_VALUE: String = "null"

    init {
      // Needed to make sure ADD_JSON_SUFFIX_TO_LOGGING_CONTEXT_KEYS is set
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
public inline fun <reified ValueT : Any> field(
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
 * - [LogBuilder.field] passes the value to [LogEvent.addStringField] or [LogEvent.addJsonField]
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
      return onJson(JsonLogField.NULL_VALUE)
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
 * [rawJsonField][LogBuilder.rawJsonField]).
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
 *   val eventJson = """{"id":1001,"type":"ORDER_PLACED"}"""
 *
 *   withLoggingContext(rawJsonField("event", eventJson)) {
 *     log.debug { "Started processing event" }
 *     // ...
 *     log.debug { "Finished processing event" }
 *   }
 * }
 * ```
 *
 * This gives the following output (using `logstash-logback-encoder`):
 * ```json
 * {"message":"Started processing event","event":{"id":1001,"type":"ORDER_PLACED"},/* ...timestamp etc. */}
 * {"message":"Finished processing event","event":{"id":1001,"type":"ORDER_PLACED"},/* ...timestamp etc. */}
 * ```
 *
 * @param validJson Set this true if you are 100% sure that [json] is valid JSON, and you want to
 *   save the performance cost of validating it.
 */
public fun rawJsonField(key: String, json: String, validJson: Boolean = false): LogField {
  return validateRawJson(
      json,
      isValid = validJson,
      onValidJson = { jsonValue -> JsonLogField(key, jsonValue) },
      onInvalidJson = { stringValue -> StringLogField(key, stringValue) },
  )
}

/**
 * Turns the given pre-serialized JSON string into a [JsonElement] (from `kotlinx.serialization`),
 * using the same validation logic as [rawJsonField].
 *
 * By default, this function checks that the given JSON string is actually valid JSON. The reason
 * for this is that giving raw JSON to our log encoder when it is not in fact valid JSON can break
 * our logs. If the JSON is valid, we can use [JsonUnquotedLiteral] to avoid having to re-encode it
 * when serializing into another object. But if it's not valid JSON, we escape it as a string
 * (returning a [JsonPrimitive]). If you are 100% sure that the given JSON string is valid and you
 * want to skip this check, you can set [validJson] to true.
 *
 * This is useful when you want to include a raw JSON field on a log. If it's a top-level field, you
 * can use [rawJsonField] - but if you want the field to be in a nested object, then you can use
 * this function.
 *
 * ### Example
 *
 * ```
 * import no.liflig.logging.getLogger
 * import no.liflig.logging.rawJson
 * import kotlinx.serialization.Serializable
 * import kotlinx.serialization.json.JsonElement
 *
 * private val log = getLogger {}
 *
 * fun example() {
 *   // We hope the external service returns valid JSON, but we can't trust that fully. If it did
 *   // return JSON, we want to log it unescaped, but if it didn't, we want to log it as a string.
 *   // `rawJson` does this validation for us.
 *   val response = callExternalService()
 *
 *   if (!response.status.isSuccessful()) {
 *     // We want to log a "response" log field with an object of "status" and "body". So we create
 *     // a serializable class with the fields we want, and make "body" a JsonElement from rawJson.
 *     @Serializable data class ResponseLog(val status: Int, val body: JsonElement)
 *
 *     log.error {
 *       field("response", ResponseLog(response.status.code, rawJson(response.body)))
 *       "External service returned error response"
 *     }
 *   }
 * }
 * ```
 *
 * @param validJson Set this true if you are 100% sure that [json] is valid JSON, and you want to
 *   save the performance cost of validating it.
 */
// For JsonUnquotedLiteral. This will likely be stabilized as-is:
// https://github.com/Kotlin/kotlinx.serialization/issues/2900
@OptIn(ExperimentalSerializationApi::class)
public fun rawJson(json: String, validJson: Boolean = false): JsonElement {
  return validateRawJson(
      json,
      isValid = validJson,
      onValidJson = { jsonValue ->
        when (jsonValue) {
          // JsonUnquotedLiteral prohibits creating a value from "null", so we have to check for
          // that here and instead return JsonNull
          "null" -> JsonNull
          else -> JsonUnquotedLiteral(jsonValue)
        }
      },
      onInvalidJson = { jsonValue -> JsonPrimitive(jsonValue) },
  )
}

/**
 * Validates that the given raw JSON string is valid JSON, calling [onValidJson] if it is, or
 * [onInvalidJson] if it's not.
 *
 * We take lambdas here instead of returning a value, for the same reason as [encodeFieldValue]. We
 * use this in both [rawJsonField] and [LogBuilder.rawJsonField].
 */
internal inline fun <ReturnT> validateRawJson(
    json: String,
    isValid: Boolean,
    onValidJson: (String) -> ReturnT,
    onInvalidJson: (String) -> ReturnT
): ReturnT {
  try {
    // Some log platforms (e.g. AWS CloudWatch) use newlines as the separator between log messages.
    // So if the JSON string has unescaped newlines, we must re-parse the JSON.
    val containsNewlines = json.contains('\n')

    // If we assume the JSON is valid, and there are no unescaped newlines, we can return it as-is.
    if (isValid && !containsNewlines) {
      return onValidJson(json)
    }

    // If we do not assume that the JSON is valid, we must try to decode it.
    val decoded = logFieldJson.parseToJsonElement(json)
    if (!isValidJson(decoded)) {
      return onInvalidJson(json)
    }

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
 * [kotlinx.serialization.json.Json.parseToJsonElement] says that it throws on invalid JSON input,
 * but this is not true: It allows unquoted strings, such as `value`, and even unquoted strings in
 * object values, like `{ "key": value }`. See
 * https://github.com/Kotlin/kotlinx.serialization/issues/2511
 *
 * This is a deliberate design decision by kotlinx-serialization, so it may be kept as-is:
 * https://github.com/Kotlin/kotlinx.serialization/issues/2375#issuecomment-1647826508
 *
 * To check that the result from `parseToJsonElement` is _actually_ valid JSON, we have to go
 * through each element in it and verify that they're not an unquoted literal. We do this by
 * checking that primitives are either strings, booleans, numbers or null.
 */
internal fun isValidJson(jsonElement: JsonElement): Boolean {
  return when (jsonElement) {
    is JsonArray -> {
      jsonElement.all { arrayElement -> isValidJson(arrayElement) }
    }
    is JsonObject -> {
      jsonElement.all { (_, objectValue) -> isValidJson(objectValue) }
    }
    is JsonNull -> true
    is JsonPrimitive -> {
      jsonElement.isString ||
          jsonElement.booleanOrNull != null ||
          jsonElement.doubleOrNull != null ||
          jsonElement.longOrNull != null
    }
  }
}

@PublishedApi
internal val logFieldJson: Json = Json {
  encodeDefaults = true
  ignoreUnknownKeys = true
}
