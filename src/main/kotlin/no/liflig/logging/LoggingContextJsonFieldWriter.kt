package no.liflig.logging

import com.fasterxml.jackson.core.JsonGenerator
import net.logstash.logback.composite.loggingevent.mdc.MdcEntryWriter

/**
 * Writes logging context fields as JSON when using
 * [`logstash-logback-encoder`](https://github.com/logfellow/logstash-logback-encoder). We need this
 * in order to include object fields as raw JSON instead of escaped strings on the log output.
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
public class LoggingContextJsonFieldWriter : MdcEntryWriter {
  init {
    ADD_JSON_SUFFIX_TO_LOGGING_CONTEXT_KEYS = true
  }

  /** @return true if we handled the entry, false otherwise. */
  override fun writeMdcEntry(
      generator: JsonGenerator,
      fieldName: String,
      mdcKey: String,
      value: String
  ): Boolean {
    if (mdcKey.endsWith(LOGGING_CONTEXT_JSON_KEY_SUFFIX)) {
      // We use fieldName instead of mdcKey here. These will typically be the same, but the user
      // may configure a mapping from certain MDC keys to custom field names. fieldName may not have
      // the JSON key suffix, so we call removeSuffix here, which removes the suffix if present,
      // otherwise it returns the string as-is.
      generator.writeFieldName(fieldName.removeSuffix(LOGGING_CONTEXT_JSON_KEY_SUFFIX))
      generator.writeRawValue(value)
      return true
    }

    return false
  }
}
