package no.liflig.logging

import com.fasterxml.jackson.core.JsonGenerator
import net.logstash.logback.composite.loggingevent.mdc.MdcEntryWriter

@Deprecated(
    "Renamed and moved to: no.liflig.logging.output.logback.JsonContextFieldWriter",
    ReplaceWith(
        "JsonContexFieldWriter",
        imports = ["no.liflig.logging.output.logback.JsonContextFieldWriter"],
    ),
)
public class LoggingContextJsonFieldWriter : MdcEntryWriter {
  /** @return true if we handled the entry, false otherwise. */
  override fun writeMdcEntry(
      generator: JsonGenerator,
      fieldName: String,
      mdcKey: String,
      value: String
  ): Boolean {
    if (LoggingContextState.get().isJsonField(mdcKey, value)) {
      generator.writeFieldName(fieldName)
      generator.writeRawValue(value)
      return true
    }

    return false
  }
}
