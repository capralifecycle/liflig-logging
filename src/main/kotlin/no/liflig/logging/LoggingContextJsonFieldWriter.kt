package no.liflig.logging

import com.fasterxml.jackson.core.JsonGenerator
import net.logstash.logback.composite.loggingevent.mdc.MdcEntryWriter

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
 *       <!-- Writes object values in logging context as actual JSON (not escaped) -->
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
  /** @return true if we handled the entry, false otherwise. */
  override fun writeMdcEntry(
      generator: JsonGenerator,
      fieldName: String,
      mdcKey: String,
      value: String
  ): Boolean {
    if (mdcKey.endsWith(LoggingContext.JSON_FIELD_KEY_SUFFIX)) {
      // If the user has configured a different fieldName, we still want to use that
      val fieldNameWithoutSuffix =
          if (fieldName == mdcKey || fieldName.endsWith(LoggingContext.JSON_FIELD_KEY_SUFFIX)) {
            LoggingContext.removeJsonFieldSuffixFromKey(fieldName)
          } else {
            fieldName
          }

      generator.writeFieldName(fieldNameWithoutSuffix)
      generator.writeRawValue(value)
      return true
    }

    return false
  }
}
