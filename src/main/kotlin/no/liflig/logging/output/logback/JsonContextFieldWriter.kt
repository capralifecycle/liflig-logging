package no.liflig.logging.output.logback

import com.fasterxml.jackson.core.JsonGenerator
import net.logstash.logback.composite.loggingevent.mdc.MdcEntryWriter
import no.liflig.logging.LoggingContextState

/**
 * Writes logging context fields as JSON when using
 * [`logstash-logback-encoder`](https://github.com/logfellow/logstash-logback-encoder). This allows
 * you to include object fields in the logging context and have them be included as actual JSON in
 * the log output, instead of as escaped strings.
 *
 * To use it, configure `logback.xml` under `src/main/resources` as follows:
 * ```xml
 * <?xml version="1.0" encoding="UTF-8"?>
 * <configuration>
 *   <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
 *     <encoder class="net.logstash.logback.encoder.LogstashEncoder">
 *       <!-- Writes object values from logging context as actual JSON (not escaped) -->
 *       <mdcEntryWriter class="no.liflig.logging.output.logback.JsonContextFieldWriter"/>
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
public class JsonContextFieldWriter : MdcEntryWriter {
  /** @return true if we handled the entry, false otherwise. */
  override fun writeMdcEntry(
      generator: JsonGenerator,
      fieldName: String,
      mdcKey: String,
      value: String
  ): Boolean {
    /** See [LoggingContextState]. */
    if (LoggingContextState.get().isJsonField(mdcKey, value)) {
      generator.writeFieldName(fieldName)
      generator.writeRawValue(value)
      return true
    }

    return false
  }
}
