package no.liflig.logging.testutils

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContainOnlyOnce
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import no.liflig.logging.jsonEncoder

internal data class LogOutput(
    /** String of all JSON-encoded log-event-specific fields from log output, in order. */
    val logFields: String,
    /**
     * Map of context fields in the log output. We don't use a String here and verify order, since
     * SLF4J's MDC (which we use for our logging context) uses a HashMap internally, which does not
     * guarantee order.
     */
    val contextFields: Map<String, JsonElement>,
)

/**
 * Since we have configured Logback in resources/logback-test.xml to use the Logstash JSON encoder,
 * we can verify in our tests that user-provided log fields have the expected JSON output.
 */
internal fun captureLogOutput(block: () -> Unit): LogOutput {
  val originalStdout = System.out

  // We redirect System.out to our own output stream, so we can capture the log output
  val outputStream = ByteArrayOutputStream()
  System.setOut(PrintStream(outputStream))

  try {
    block()
  } finally {
    System.setOut(originalStdout)
  }

  val logOutput = outputStream.toString("UTF-8")

  // We expect each call to captureLogFields to capture just a single log line, so it should only
  // contain 1 newline. If we get more, that is likely an error and should fail our tests.
  logOutput shouldContainOnlyOnce "\n"

  // The last standard log field before custom log fields is either:
  // - "context" if log had context fields (we configure this in logback-test.xml)
  // - "stack_trace" if log had a cause exception
  // - "level_value" otherwise
  // We want our tests to assert on the contents of all user-provided fields (and in the correct
  // order, so we don't just want to deserialize to a Map), so we strip away the standard fields
  // here.
  val indexOfContext = logOutput.indexOf("\"context\"")
  val indexOfStackTrace = logOutput.indexOf("\"stack_trace\"")
  val indexOfLevelValue = logOutput.indexOf("\"level_value\"")

  val fieldsStartIndex =
      when {
        (indexOfContext != -1) -> indexAfterJsonObjectField(logOutput, startIndex = indexOfContext)
        (indexOfStackTrace != -1) ->
            indexAfterJsonStringField(logOutput, startIndex = indexOfStackTrace)
        (indexOfLevelValue != -1) ->
            indexAfterJsonNumberField(logOutput, startIndex = indexOfLevelValue)
        else -> null
      }
  fieldsStartIndex.shouldNotBeNull()

  // Since we set includeCallerData=true in logback-test.xml, caller info fields are included at the
  // end of the log - we strip away these as well, since we only want to test user-provided fields
  val fieldsEndIndex = logOutput.indexOf("\"caller_class_name\"") shouldNotBe -1

  // Omit comma before and after fields
  val start = fieldsStartIndex + 1
  val end = fieldsEndIndex - 1
  val fieldsString =
      // If there are no user-provided fields (which we want to test sometimes), start will be
      // greater than end
      if (start > end) {
        ""
      } else {
        logOutput.substring(start, end)
      }

  // We've configured logback-test.xml to include logging context fields under this key, so we can
  // separate them from log-event-specific fields
  @Serializable
  data class ContextFieldsInLogOutput(val context: MutableMap<String, JsonElement>? = null)

  val contextFields =
      try {
        jsonEncoder.decodeFromString<ContextFieldsInLogOutput>(logOutput).context
      } catch (_: Exception) {
        null
      }

  return LogOutput(fieldsString, contextFields ?: emptyMap())
}

private fun indexAfterJsonKey(json: String, startIndex: Int): Int? {
  val indexAfterKeyString = indexAfterJsonString(json, startIndex) ?: return null

  return if (json[indexAfterKeyString] == ':') {
    indexAfterKeyString + 1
  } else {
    null
  }
}

private fun indexAfterJsonString(json: String, startIndex: Int): Int? {
  // Iterate until we find 2 unescaped quotes
  var quoteCount = 0
  for (i in startIndex until json.length) {
    if (json[i] == '"' && json[i - 1] != '\\') {
      quoteCount++
    }

    if (quoteCount == 2) {
      return i + 1
    }
  }

  return null
}

private fun indexAfterJsonStringField(json: String, startIndex: Int): Int? {
  val valueStartIndex = indexAfterJsonKey(json, startIndex) ?: return null

  return indexAfterJsonString(json, startIndex = valueStartIndex)
}

private fun indexAfterJsonObjectField(json: String, startIndex: Int): Int? {
  val valueStartIndex = indexAfterJsonKey(json, startIndex) ?: return null

  // We want to iterate past the full object, but it may contain nested objects - so we count
  // unescaped opening and closing braces until we close the first opening brace
  var braceCount = 0
  for (i in valueStartIndex until json.length) {
    if (json[i - 1] != '\\') {
      when (json[i]) {
        '{' -> braceCount++
        '}' -> braceCount--
      }
    }

    if (braceCount == 0) {
      return i + 1
    }
  }

  return null
}

private fun indexAfterJsonNumberField(json: String, startIndex: Int): Int? {
  val valueStartIndex = indexAfterJsonKey(json, startIndex) ?: return null

  for (i in valueStartIndex until json.length) {
    // If we have started the number, it ends when we find a non-digit character
    if (!json[i].isDigit()) {
      return i
    }
  }

  return null
}
