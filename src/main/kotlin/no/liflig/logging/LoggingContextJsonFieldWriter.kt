package no.liflig.logging

import com.fasterxml.jackson.core.JsonGenerator
import kotlin.concurrent.Volatile
import net.logstash.logback.composite.loggingevent.mdc.MdcEntryWriter
import org.slf4j.LoggerFactory as Slf4jLoggerFactory

/**
 * Writes logging context fields as JSON when using
 * [`logstash-logback-encoder`](https://github.com/logfellow/logstash-logback-encoder). We need this
 * in order to include object fields as raw JSON instead of escaped strings on the log output (see
 * [LoggingContext.JSON_FIELD_KEY_SUFFIX]).
 *
 * To use it, configure `logback.xml` under `src/main/resources` as follows:
 * ```xml
 * <?xml version="1.0" encoding="UTF-8"?>
 * <configuration>
 *   <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
 *     <encoder class="net.logstash.logback.encoder.LogstashEncoder">
 *       <!-- Writes object values from logging context as actual JSON (not escaped) -->
 *       <mdcEntryWriter class="no.liflig.logging.LoggingContextJsonFieldWriter"/>
 *     </encoder>
 *   </appender>
 *
 *   <root level="INFO">
 *     <appender-ref ref="STDOUT"/>
 *   </root>
 * </configuration>
 * ```
 *
 * This requires that you have added `ch.qos.logback:logback-classic` and
 * `net.logstash.logback:logstash-logback-encoder` as dependencies.
 */
class LoggingContextJsonFieldWriter : MdcEntryWriter {
  init {
    USING_LOGGING_CONTEXT_JSON_FIELD_WRITER = true
  }

  /** @return true if we handled the entry, false otherwise. */
  override fun writeMdcEntry(
      generator: JsonGenerator,
      fieldName: String,
      mdcKey: String,
      value: String
  ): Boolean {
    if (mdcKey.endsWith(LoggingContext.JSON_FIELD_KEY_SUFFIX)) {
      // We use fieldName instead of mdcKey here. These will typically be the same, but the user
      // may configure a mapping from certain MDC keys to custom field names. fieldName may not have
      // the JSON key suffix, so we call removeSuffix here, which removes the suffix if present,
      // otherwise it returns the string as-is.
      generator.writeFieldName(fieldName.removeSuffix(LoggingContext.JSON_FIELD_KEY_SUFFIX))
      generator.writeRawValue(value)
      return true
    }

    return false
  }
}

/**
 * We only want to add [LoggingContext.JSON_FIELD_KEY_SUFFIX] to context field keys if the user has
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
@Volatile internal var USING_LOGGING_CONTEXT_JSON_FIELD_WRITER = false

/**
 * See [USING_LOGGING_CONTEXT_JSON_FIELD_WRITER].
 *
 * This function catches all throwables (this is important, since we call this from static
 * initializers).
 */
internal fun ensureLoggerImplementationIsLoaded() {
  try {
    Slf4jLoggerFactory.getILoggerFactory()
  } catch (_: Throwable) {}
}
