package no.liflig.logging

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializable
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.jsontype.TypeSerializer
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.serializer

/**
 * A log field is a key-value pair for adding structured data to logs.
 *
 * When outputting logs as JSON (using e.g.
 * [logstash-logback-encoder](https://github.com/logfellow/logstash-logback-encoder)), this becomes
 * a field in the logged JSON object. That allows you to filter and query on the field in the log
 * analysis tool of your choice, in a more structured manner than if you were to just use string
 * concatenation.
 *
 * There are 3 ways to add log fields in this library:
 * - Adding fields to a single log: Use [LogBuilder.field] in the scope of one of the methods on
 *   [Logger] (see example on [LogBuilder.field]).
 * - Adding fields to all logs in a scope: Use [withLoggingContext], with the [field] top-level
 *   function to construct log fields (see example on [withLoggingContext]).
 * - Adding fields to an exception: Use [ExceptionWithLoggingContext] (see example on its
 *   docstring).
 *
 * If you have a value that is already serialized, you can use [LogBuilder.rawJsonField] or the
 * [rawJsonField] top-level function.
 *
 * If there are duplicate keys in the fields that would apply to a log, we only add 1 of the fields
 * (to avoid duplicate keys in the JSON log output). Fields are prioritized as follows:
 * 1. Single-log fields (from [LogBuilder.field])
 * 2. Exception log fields (from [ExceptionWithLoggingContext])
 * 3. Logging context fields (from [withLoggingContext])
 */
public class LogField
internal constructor(
    @JvmField internal val key: String,
    @JvmField internal val value: String,
    @JvmField internal val isJson: Boolean,
) {
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

/**
 * Constructs a [LogField], a key-value pair for adding structured data to logs.
 *
 * This function is made to be used with [withLoggingContext], to add fields to all logs in a scope.
 * If you just want to add a field to a single log, you should instead call [LogBuilder.field] on
 * one of [Logger]'s methods ([see example][LogBuilder.field]).
 *
 * The value is serialized using `kotlinx.serialization`, so if you pass an object here, you should
 * make sure it is annotated with [@Serializable][Serializable]. If serialization fails, we fall
 * back to calling `toString()` on the value.
 *
 * If you want to specify the serializer for the value explicitly, you can call the overload of this
 * function that takes a [SerializationStrategy][SerializationStrategy] as a third parameter. That
 * is also useful for cases where you can't call this function with a reified type parameter.
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
  return try {
    createLogFieldOfType(key, value, valueType = typeOf<ValueT>())
  } catch (_: Exception) {
    // Falls back to `toString()` if serialization fails
    createStringLogField(key, value)
  }
}

/**
 * We split this out from [field] to reduce the amount of inlined code (more inlined code -> bigger
 * code size -> possibly worse performance, and also more internal API that must be exposed with
 * `@PublishedApi`).
 */
@PublishedApi
internal fun createLogFieldOfType(
    key: String,
    value: Any?,
    valueType: KType,
): LogField {
  return when {
    /** See [JSON_NULL_VALUE] for why we handle nulls like this. */
    value == null -> {
      LogField(key, JSON_NULL_VALUE, isJson = true)
    }
    // Special case for String, to avoid redundant serialization
    value is String -> {
      LogField(key, value, isJson = false)
    }
    // Special case for common types that kotlinx.serialization doesn't handle by default
    fieldValueShouldUseToString(value) -> {
      LogField(key, value.toString(), isJson = false)
    }
    // Try to serialize with kotlinx.serialization - if it throws an exception, the `field` function
    // will catch it and fall back to `createStringLogField`
    else -> {
      val serializer = LOG_FIELD_JSON_FORMAT.serializersModule.serializer(valueType)
      val serializedValue = LOG_FIELD_JSON_FORMAT.encodeToString(serializer, value)
      LogField(key, serializedValue, isJson = true)
    }
  }
}

/** `toString()` fallback for the log field value when [createLogFieldOfType] fails. */
@PublishedApi
internal fun createStringLogField(key: String, value: Any?): LogField {
  return if (value == null) {
    LogField(key, JSON_NULL_VALUE, isJson = true)
  } else {
    LogField(key, value.toString(), isJson = false)
  }
}

/**
 * Constructs a [LogField], a key-value pair for adding structured data to logs.
 *
 * This function is made to be used with [withLoggingContext], to add fields to all logs in a scope.
 * If you just want to add a field to a single log, you should instead call [LogBuilder.field] on
 * one of [Logger]'s methods ([see example][LogBuilder.field]).
 *
 * The value is serialized using `kotlinx.serialization`, so if you pass an object here, you should
 * make sure it is annotated with [@Serializable][Serializable]. If serialization fails, we fall
 * back to calling `toString()` on the value.
 *
 * If you want to specify the serializer for the value explicitly, you can call the overload of this
 * function that takes a [SerializationStrategy][SerializationStrategy] as a third parameter. That
 * is also useful for cases where you can't call this function with a reified type parameter.
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
  return try {
    if (value == null) {
      LogField(key, JSON_NULL_VALUE, isJson = true)
    } else {
      val serializedValue = LOG_FIELD_JSON_FORMAT.encodeToString(serializer, value)
      LogField(key, serializedValue, isJson = true)
    }
  } catch (_: Exception) {
    // Falls back to `toString()` if serialization fails
    createStringLogField(key, value)
  }
}

/**
 * Constructs a [LogField], a key-value pair for adding structured data to logs, with the given
 * pre-serialized JSON value.
 *
 * This function is made to be used with [withLoggingContext], to add fields to all logs in a scope.
 * If you just want to add a field to a single log, you should instead call
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
 *   val eventJson = """{"id":1000,"type":"ORDER_PLACED"}"""
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
 * {"message":"Started processing event","event":{"id":1000,"type":"ORDER_PLACED"},/* ...timestamp etc. */}
 * {"message":"Finished processing event","event":{"id":1000,"type":"ORDER_PLACED"},/* ...timestamp etc. */}
 * ```
 *
 * @param validJson Set this true if you are 100% sure that [json] is valid JSON, and you want to
 *   save the performance cost of validating it.
 */
public fun rawJsonField(key: String, json: String, validJson: Boolean = false): LogField {
  return validateRawJson(
      json,
      isValid = validJson,
      onValidJson = { jsonValue -> LogField(key, jsonValue, isJson = true) },
      onInvalidJson = { stringValue -> LogField(key, stringValue, isJson = false) },
  )
}

/**
 * Wraps the given pre-serialized JSON string in a serializable object, that will serialize the JSON
 * string inline (without escaping). Uses the same validation logic as [rawJsonField].
 *
 * By default, this function checks that the given JSON string is actually valid JSON. The reason
 * for this is that giving raw JSON to our log encoder when it is not in fact valid JSON can break
 * our logs. If the JSON is valid, we can use [JsonUnquotedLiteral] to avoid having to re-encode it
 * when serializing into another object. But if it's not valid JSON, we escape it as a normal JSON
 * string. If you are 100% sure that the given JSON string is valid and you want to skip this check,
 * you can set [validJson] to true.
 *
 * This is useful when you want to include a raw JSON field on a log. If it's a top-level field, you
 * can use [rawJsonField] - but if you want the field to be in a nested object, then you can use
 * this function.
 *
 * ### Example
 *
 * ```
 * import no.liflig.logging.RawJson
 * import no.liflig.logging.getLogger
 * import no.liflig.logging.rawJson
 * import kotlinx.serialization.Serializable
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
 *     // a serializable class with the fields we want, using the `rawJson` return type for "body".
 *     @Serializable data class ResponseLog(val status: Int, val body: RawJson)
 *
 *     log.error {
 *       field("response", ResponseLog(response.status.code, rawJson(response.bodyString())))
 *       "External service returned error response"
 *     }
 *   }
 * }
 * ```
 *
 * @param validJson Set this to true if you are 100% sure that [json] is valid JSON, and you want to
 *   save the performance cost of validating it.
 */
public fun rawJson(json: String, validJson: Boolean = false): RawJson {
  return validateRawJson(
      json,
      isValid = validJson,
      onValidJson = { jsonValue -> ValidRawJson(jsonValue) },
      onInvalidJson = { jsonValue -> NotValidJson(jsonValue) },
  )
}

/**
 * [rawJson] returns this wrapper around a given raw JSON string, controlling how the JSON string is
 * serialized:
 * - If the JSON string was valid, it will be serialized inline without escaping it (using
 *   [JsonUnquotedLiteral])
 * - If it was not valid, then it will be serialized as an escaped string
 *
 * This class is also serializable with Jackson, with the same serialization behavior.
 */
@Serializable(with = RawJsonSerializer::class) public sealed interface RawJson

/** [RawJson] implementation for when the JSON string given to [rawJson] is valid. */
internal class ValidRawJson(
    @JvmField internal actual val json: String,
) : RawJson, JsonSerializable {
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

  override fun equals(other: Any?): Boolean = other is ValidRawJson && this.json == other.json

  override fun hashCode(): Int = json.hashCode()
}

/** [RawJson] implementation for when the JSON string given to [rawJson] is not valid. */
internal class NotValidJson(
    @JvmField internal actual val value: String,
) : RawJson, JsonSerializable {
  override fun toString() = value

  override fun serialize(generator: JsonGenerator, serializers: SerializerProvider) {
    generator.writeString(value)
  }

  override fun serializeWithType(
      generator: JsonGenerator,
      serializers: SerializerProvider,
      typeSerializer: TypeSerializer
  ) {
    serialize(generator, serializers)
  }

  override fun equals(other: Any?): Boolean = other is NotValidJson && this.value == other.value

  override fun hashCode(): Int = value.hashCode()
}

/**
 * `kotlinx.serialization` serializer for [RawJson]. Serializes [ValidRawJson] using
 * [JsonUnquotedLiteral], and [NotValidJson] as an escaped string.
 */
internal object RawJsonSerializer : KSerializer<RawJson> {
  private val primitiveSerializer = JsonPrimitive.serializer()

  override val descriptor: SerialDescriptor
    get() = primitiveSerializer.descriptor

  // `JsonUnquotedLiteral` is experimental, but will likely be stabilized as-is:
  // https://github.com/Kotlin/kotlinx.serialization/issues/2900
  @OptIn(ExperimentalSerializationApi::class)
  override fun serialize(encoder: Encoder, value: RawJson) {
    when (value) {
      is ValidRawJson -> {
        // It's not valid to pass "null" to JsonUnquotedLiteral
        if (value.json == "null") {
          primitiveSerializer.serialize(encoder, JsonNull)
        } else {
          primitiveSerializer.serialize(encoder, JsonUnquotedLiteral(value.json))
        }
      }
      is NotValidJson -> encoder.encodeString(value.value)
    }
  }

  /** @throws IllegalStateException If the given decoder is not a [JsonDecoder]. */
  override fun deserialize(decoder: Decoder): RawJson {
    if (decoder !is JsonDecoder) {
      // Same exception that koltinx.serialization throws when given a non-JSON decoder
      throw IllegalStateException(
          "This serializer can be used only with Json format. Expected Decoder to be JsonDecoder, got ${decoder::class}",
      )
    }

    val jsonElement = decoder.decodeJsonElement()
    val jsonString = jsonElement.toString()

    /** See [isValidJson] for why we have to check this. */
    return if (isValidJson(jsonElement)) {
      ValidRawJson(jsonString)
    } else {
      NotValidJson(jsonString)
    }
  }
}

/**
 * Validates that the given raw JSON string is valid JSON, calling [onValidJson] if it is, or
 * [onInvalidJson] if it's not.
 *
 * We take callbacks for the different results here instead of returning a return value. This is
 * because we use this in both [rawJsonField] and [LogBuilder.rawJsonField], and they want to do
 * different things with the encoded value:
 * - [rawJsonField] constructs a [LogField] with it
 * - [LogBuilder.rawJsonField] passes the value to [LogEvent.addStringField] or
 *   [LogEvent.addJsonField]
 *
 * If we used a return value here, we would have to wrap it in an object to convey whether it was
 * encoded to JSON or just a plain string, which requires an allocation. By instead taking callbacks
 * and making the function `inline`, we pay no extra cost.
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
    val decoded = LOG_FIELD_JSON_FORMAT.parseToJsonElement(json)
    if (!isValidJson(decoded)) {
      return onInvalidJson(json)
    }

    // If we successfully decoded the JSON, and it does not contain unescaped newlines, we can
    // return it as-is.
    if (!containsNewlines) {
      return onValidJson(json)
    }

    // If the JSON did contain unescaped newlines, then we need to re-encode to escape them.
    val encoded = LOG_FIELD_JSON_FORMAT.encodeToString(JsonElement.serializer(), decoded)
    return onValidJson(encoded)
  } catch (_: Exception) {
    // If we failed to decode/re-encode the JSON string, we return it as a non-JSON string.
    return onInvalidJson(json)
  }
}

/**
 * [Json.parseToJsonElement] says that it throws on invalid JSON input, but this is not true: It
 * allows unquoted strings, such as `value`, and even unquoted strings in object values, like `{
 * "key": value }`. See https://github.com/Kotlin/kotlinx.serialization/issues/2511
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

/**
 * Some types, namely classes in the Java standard library, are not supported by
 * `kotlinx.serialization` by default, and so will always fail to serialize. In these cases, we want
 * to call `toString` on the value before even trying to serialize, as we don't want to pay the cost
 * of creating an exception just to discard it right after. So we use this function to check if the
 * field value type should eagerly use `toString`.
 */
internal fun fieldValueShouldUseToString(value: Any): Boolean {
  return when (value) {
    is java.time.Instant,
    is java.util.UUID,
    is java.net.URI,
    is java.net.URL,
    is java.math.BigDecimal -> true
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
internal const val JSON_NULL_VALUE: String = "null"

@JvmField
internal val LOG_FIELD_JSON_FORMAT: Json = Json {
  encodeDefaults = true
  ignoreUnknownKeys = true
}
