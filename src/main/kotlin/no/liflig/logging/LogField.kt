package no.liflig.logging

import java.math.BigDecimal
import java.net.URI
import java.net.URL
import java.time.Instant
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
import org.slf4j.LoggerFactory as Slf4jLoggerFactory

/**
 * A log field is a key-value pair for adding structured data to logs.
 *
 * When outputting logs as JSON (using e.g.
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
 * private val log = getLogger()
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
public abstract class LogField
internal constructor(
    @JvmField internal val key: String,
    @JvmField internal val value: String,
) {
  /**
   * Should call [LogEvent.addJsonField] / [LogEvent.addStringField] to add this field to the log.
   */
  internal abstract fun addToLogEvent(logEvent: LogEvent)

  /**
   * [JsonLogField] adds a suffix to the key in the logging context to identify the value as raw
   * JSON (so we can write the JSON unescaped in `LoggingContextJsonFieldWriter`).
   */
  internal abstract fun getKeyForLoggingContext(): String

  /**
   * Returns a string representation of the log field, formatted as `${key}=${value}`.
   *
   * You should not rely on this format for anything other than debugging.
   */
  override fun toString(): String = "${key}=${value}"

  /** Two log fields are equal if their keys and values are equal. */
  override fun equals(other: Any?): Boolean =
      other is LogField && this.key == other.key && this.value == other.value

  /** Returns the combined hash code of the key and value for this log field. */
  override fun hashCode(): Int =
      key.hashCode() * 31 + value.hashCode() // 31 is default factor for aggregate hash codes
}

@PublishedApi
internal open class StringLogField(key: String, value: String) : LogField(key, value) {
  override fun addToLogEvent(logEvent: LogEvent) {
    logEvent.addStringField(key, value)
  }

  final override fun getKeyForLoggingContext(): String = key
}

@PublishedApi
internal open class JsonLogField(
    key: String,
    value: String,
    private val keyForLoggingContext: String =
        if (ADD_JSON_SUFFIX_TO_LOGGING_CONTEXT_KEYS) {
          key + LOGGING_CONTEXT_JSON_KEY_SUFFIX
        } else {
          key
        },
) : LogField(key, value) {
  override fun addToLogEvent(logEvent: LogEvent) {
    logEvent.addJsonField(key, value)
  }

  final override fun getKeyForLoggingContext(): String = keyForLoggingContext

  // TODO: Remove PublishedApi once users have migrated away from JsonLogField.NULL_VALUE
  @PublishedApi
  internal companion object {
    // Kept for backwards compatibility. TODO: Remove once users have migrated
    @Suppress("unused") @PublishedApi internal const val NULL_VALUE: String = JSON_NULL_VALUE

    init {
      try {
        /**
         * Needed to make sure [ADD_JSON_SUFFIX_TO_LOGGING_CONTEXT_KEYS] is set.
         *
         * We catch all throwables here, since this is called in a static initializer where we never
         * wanna throw, and it's OK for this to fail silently.
         */
        ensureLoggerImplementationIsLoaded()
      } catch (_: Throwable) {}
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
 * The value is serialized using `kotlinx.serialization`, so if you pass an object here, you should
 * make sure it is annotated with [@Serializable][kotlinx.serialization.Serializable]. If
 * serialization fails, we fall back to calling `toString()` on the value.
 *
 * If you want to specify the serializer for the value explicitly, you can call the overload of this
 * function that takes a [SerializationStrategy][kotlinx.serialization.SerializationStrategy] as a
 * third parameter. That is also useful for cases where you can't call this function with a reified
 * type parameter.
 *
 * If you have a value that is already serialized, you should use [rawJsonField] instead.
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
public inline fun <reified ValueT> field(key: String, value: ValueT): LogField {
  return encodeFieldValue(
      value,
      onJson = { jsonValue -> JsonLogField(key, jsonValue) },
      onString = { stringValue -> StringLogField(key, stringValue) },
  )
}

/**
 * Constructs a [LogField], a key-value pair for adding structured data to logs.
 *
 * This function is made to be used with [withLoggingContext], to add fields to all logs within a
 * scope. If you just want to add a field to a single log, you should instead call
 * [LogBuilder.field] on one of [Logger]'s methods ([see example][LogBuilder.field]).
 *
 * The value is serialized using `kotlinx.serialization`, so if you pass an object here, you should
 * make sure it is annotated with [@Serializable][kotlinx.serialization.Serializable]. If
 * serialization fails, we fall back to calling `toString()` on the value.
 *
 * If you want to specify the serializer for the value explicitly, you can call the overload of this
 * function that takes a [SerializationStrategy][kotlinx.serialization.SerializationStrategy] as a
 * third parameter. That is also useful for cases where you can't call this function with a reified
 * type parameter.
 *
 * If you have a value that is already serialized, you should use [rawJsonField] instead.
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
    serializer: SerializationStrategy<ValueT>,
): LogField {
  return encodeFieldValueWithSerializer(
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
internal inline fun <reified ValueT, ReturnT> encodeFieldValue(
    value: ValueT,
    crossinline onJson: (String) -> ReturnT,
    crossinline onString: (String) -> ReturnT,
): ReturnT {
  try {
    return when {
      value == null -> onJson(JSON_NULL_VALUE)
      // Special case for String, to avoid redundant serialization
      value is String -> onString(value)
      // Special case for common types that kotlinx.serialization doesn't handle by default
      fieldValueShouldUseToString(value) -> onString(value.toString())
      // Try to serialize with kotlinx.serialization - if it fails, we fall back to toString below
      else -> onJson(jsonEncoder.encodeToString(value))
    }
  } catch (_: Exception) {
    // We don't want to ever throw an exception from constructing a log field, which may happen if
    // serialization fails, for example. So in these cases we fall back to toString()
    return onString(value.toString())
  }
}

/** Same as [encodeFieldValue], but takes a user-provided serializer for serializing the value. */
internal inline fun <ValueT : Any, ReturnT> encodeFieldValueWithSerializer(
    value: ValueT?,
    serializer: SerializationStrategy<ValueT>,
    crossinline onJson: (String) -> ReturnT,
    crossinline onString: (String) -> ReturnT,
): ReturnT {
  try {
    return when {
      // Handle nulls here, so users don't have to deal with passing a null-handling serializer
      value == null -> onJson(JSON_NULL_VALUE)
      // Try to serialize with kotlinx.serialization - if it fails, we fall back to toString below
      else -> onJson(jsonEncoder.encodeToString(serializer, value))
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
 * private val log = getLogger()
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
 * private val log = getLogger()
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
    crossinline onValidJson: (String) -> ReturnT,
    crossinline onInvalidJson: (String) -> ReturnT
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
    val decoded = jsonEncoder.parseToJsonElement(json)
    if (!isValidJson(decoded)) {
      return onInvalidJson(json)
    }

    // If we successfully decoded the JSON, and it does not contain unescaped newlines, we can
    // return it as-is.
    if (!containsNewlines) {
      return onValidJson(json)
    }

    // If the JSON did contain unescaped newlines, then we need to re-encode to escape them.
    val encoded = jsonEncoder.encodeToString(JsonElement.serializer(), decoded)
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

internal fun createLogFieldFromContext(key: String, value: String): LogField {
  return if (ADD_JSON_SUFFIX_TO_LOGGING_CONTEXT_KEYS &&
      key.endsWith(LOGGING_CONTEXT_JSON_KEY_SUFFIX)) {
    JsonLogFieldFromContext(key, value)
  } else {
    StringLogFieldFromContext(key, value)
  }
}

internal class StringLogFieldFromContext(key: String, value: String) : StringLogField(key, value) {
  override fun addToLogEvent(logEvent: LogEvent) {
    // We only want to include fields from the logging context if it's not already in the context
    // (in which case the logger implementation will add the fields from SLF4J's MDC).
    if (!LoggingContext.contains(this)) {
      logEvent.addStringField(key, value)
    }
  }
}

internal class JsonLogFieldFromContext(
    /**
     * We construct this log field with keys that already have the JSON key suffix (see
     * [createLogFieldFromContext]). So we set [keyForLoggingContext] to the key with the suffix,
     * and remove the suffix for [key] below.
     */
    keyWithJsonSuffix: String,
    value: String,
) :
    JsonLogField(
        key = keyWithJsonSuffix.removeSuffix(LOGGING_CONTEXT_JSON_KEY_SUFFIX),
        value = value,
        keyForLoggingContext = keyWithJsonSuffix,
    ) {
  override fun addToLogEvent(logEvent: LogEvent) {
    // We only want to include fields from the logging context if it's not already in the context
    // (in which case the logger implementation will add the fields from SLF4J's MDC).
    if (!LoggingContext.contains(this)) {
      logEvent.addJsonField(key, value)
    }
  }
}

/**
 * SLF4J's MDC only supports String values. This works fine for our [StringLogField] - but we also
 * want the ability to include JSON-serialized objects in our logging context. This is useful when
 * for example processing an event, and you want that event to be included on all logs in the scope
 * of processing it. If we were to just include it as a string, the JSON would be escaped, which
 * prevents log analysis platforms from parsing fields from the event and letting us query on them.
 * What we want is for the [JsonLogField] to be included as actual JSON on the log output,
 * unescaped, to get the benefits of structured logging.
 *
 * To achieve this, we add the raw JSON string from [JsonLogField] to the MDC, but with this suffix
 * added to the key. Then, users can configure our [LoggingContextJsonFieldWriter] to strip this
 * suffix from the key and write the field value as raw JSON in the log output. This only works when
 * using Logback with `logstash-logback-encoder`, but that's what this library is primarily designed
 * for anyway.
 *
 * We add a suffix to the field key instead of the field value, since the field value may be
 * external input, which would open us up to malicious actors breaking our logs by passing invalid
 * JSON strings with the appropriate prefix/suffix.
 *
 * This specific suffix was chosen to reduce the chance of clashing with other keys - MDC keys
 * typically don't have spaces/parentheses.
 */
internal const val LOGGING_CONTEXT_JSON_KEY_SUFFIX = " (json)"

/**
 * We only want to add [LOGGING_CONTEXT_JSON_KEY_SUFFIX] to context field keys if the user has
 * configured [LoggingContextJsonFieldWriter] with `logstash-logback-encoder`. If this is not the
 * case, we don't want to add the key suffix, as that will show up in the log output.
 *
 * So to check this, we use this global boolean (volatile for thread-safety), defaulting to false.
 * If `LoggingContextJsonFieldWriter` is configured, its constructor will run when Logback is
 * initialized, and set this to true. Then we can check this value in [JsonLogField], to decide
 * whether or not to add the JSON key suffix.
 *
 * One obstacle with this approach is that we need Logback to be loaded before checking this field.
 * The user may construct a [JsonLogField] before loading Logback, in which case
 * `LoggingContextJsonFieldWriter`'s constructor will not have run yet, and we will omit the key
 * suffix when it should have been added. So to ensure that Logback is loaded before checking this
 * field, we call [ensureLoggerImplementationIsLoaded] from an `init` block on
 * [JsonLogField.Companion], which will run when the class is loaded. We test that this works in the
 * `LogbackLoggerTest` under `integration-tests/logback`.
 */
@Volatile internal var ADD_JSON_SUFFIX_TO_LOGGING_CONTEXT_KEYS = false

/** See [ADD_JSON_SUFFIX_TO_LOGGING_CONTEXT_KEYS]. */
internal fun ensureLoggerImplementationIsLoaded() {
  // This will initialize the SLF4J logger implementation, if not already initialized
  Slf4jLoggerFactory.getILoggerFactory()
}

/**
 * Some types, namely classes in the Java standard library, are not supported by
 * `kotlinx.serialization` by default, and so will always fail to serialize. In these cases, we want
 * to call `toString` on the value before even trying to serialize, as we don't want to pay the cost
 * of creating an exception just to discard it right after. So we use this function to check if the
 * field value type should eagerly use `toString`.
 */
@PublishedApi
internal fun fieldValueShouldUseToString(value: Any): Boolean {
  return when (value) {
    is Instant,
    is UUID,
    is URI,
    is URL,
    is BigDecimal -> true
    else -> false
  }
}

/**
 * SLF4J supports null values in `KeyValuePair`s, and it's up to the logger implementation for how
 * to handle it. In the case of Logback and `logstash-logback-encoder`, key-value pairs with `null`
 * values are omitted entirely. But this can be confusing for the user, since they may think the log
 * field was omitted due to some error. So in this library, we instead use a JSON `null` as the
 * value for null log fields.
 */
@PublishedApi internal const val JSON_NULL_VALUE: String = "null"

@PublishedApi
@JvmField
internal val jsonEncoder: Json = Json {
  encodeDefaults = true
  ignoreUnknownKeys = true
}

// Kept for backwards compatibility. TODO: Remove once users have migrated
@Suppress("unused")
@PublishedApi
internal val logFieldJson: Json
  get() = jsonEncoder
