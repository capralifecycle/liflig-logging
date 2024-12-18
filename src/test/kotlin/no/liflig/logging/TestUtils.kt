package no.liflig.logging

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContainOnlyOnce
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Since we have configured Logback in resources/logback-test.xml to use the Logstash JSON encoder,
 * we can verify in our tests that user-provided log fields have the expected JSON output.
 */
internal fun captureLogFields(block: () -> Unit): String {
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

  var fieldsStartIndex: Int? = null

  // The last standard log field before user-provided fields is either "level_value" or
  // "stack_trace" (depending on whether a cause exception was attached). We want our tests to
  // assert on the contents of all user-provided fields, so we strip away the standard fields here.
  val indexOfStackTrace = logOutput.indexOf("\"stack_trace\"")
  if (indexOfStackTrace != -1) {
    fieldsStartIndex = indexAfterJsonStringField(logOutput, startIndex = indexOfStackTrace)
  }
  if (fieldsStartIndex == null) {
    val indexOfLevelValue = logOutput.indexOf("\"level_value\"") shouldNotBe -1 // -1 = not found
    fieldsStartIndex = indexAfterJsonNumberField(logOutput, startIndex = indexOfLevelValue)
  }

  fieldsStartIndex.shouldNotBeNull() shouldNotBe -1

  // Since we set includeCallerData=true in logback-test.xml, caller info fields are included at the
  // end of the log - we strip away these as well, since we again only want to test user-provided
  // fields
  val fieldsEndIndex = logOutput.indexOf("\"caller_class_name\"") shouldNotBe -1

  // Omit comma before and after fields
  val start = fieldsStartIndex + 1
  val end = fieldsEndIndex - 1
  // If there are no user-provided fields (which we want to test sometimes), start will be greater
  // than end
  if (start > end) {
    return ""
  }
  return logOutput.substring(start, end)
}

private fun indexAfterJsonStringField(json: String, startIndex: Int): Int? {
  // We want to iterate past the key and the value, meaning we want to iterate until we've passed 4
  // unescaped quotes
  var quoteCount = 0
  for (i in startIndex until json.length) {
    if (json[i] == '"' && json[i - 1] != '\\') {
      quoteCount++
    }

    if (quoteCount == 4) {
      return i + 1
    }
  }

  return null
}

private fun indexAfterJsonNumberField(json: String, startIndex: Int): Int? {
  // We first want to iterate past the key (2 quotes), then the number value
  var quoteCount = 0
  var numberBegun = false
  for (i in startIndex until json.length) {
    if (json[i] == '"' && json[i - 1] != '\\') {
      quoteCount++
    }

    // The number starts after the 2 quotes from the keys and the following colon, and it either
    // starts with a digit or a minus sign
    if (quoteCount == 2 && json[i - 1] == ':' && (json[i].isDigit() || json[i] == '-')) {
      numberBegun = true
      continue
    }

    // If we have started the number, it ends when we find a non-digit character
    if (numberBegun && !json[i].isDigit()) {
      return i
    }
  }

  return null
}

/**
 * Used in [LoggerTest] to test that the logger gets the expected name from the file it's
 * constructed in.
 */
internal val loggerConstructedInOtherFile = Logger {}
