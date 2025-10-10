package no.liflig.logging.testutils

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import no.liflig.logging.LoggingContext
import no.liflig.logging.getCopyOfLoggingContext
import no.liflig.logging.getEmptyLoggingContextState

internal fun createLoggingContext(fields: Map<String, String>): LoggingContext {
  return LoggingContext(map = fields, state = getEmptyLoggingContextState())
}

internal fun loggingContextShouldContainExactly(expectedFields: Map<String, String>) {
  val context = getCopyOfLoggingContext()
  context.map.shouldNotBeNull()

  context.map.size shouldBe expectedFields.size
  for ((key, expectedValue) in expectedFields) {
    withClue({ "key='${key}', expectedValue='${expectedValue}'" }) {
      val actualValue = context.map[key]
      actualValue.shouldNotBeNull()
      actualValue shouldBe expectedValue
    }
  }
}

internal fun loggingContextShouldBeEmpty() {
  val context = getCopyOfLoggingContext()
  context.map.shouldBeNull()
}
