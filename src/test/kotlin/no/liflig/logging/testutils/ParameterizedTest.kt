package no.liflig.logging.testutils

import io.kotest.assertions.withClue

/**
 * Runs the given [test] lambda for each of the given [testCases]. When a test fails, the failing
 * test case will be printed, so you know which test case failed.
 *
 * If a test case implements [TestCase], then [TestCase.name] will be used - otherwise, the
 * `toString` representation of the test case is used.
 */
internal inline fun <TestCaseT> parameterizedTest(
    testCases: Iterable<TestCaseT>,
    beforeEach: () -> Unit = {},
    afterEach: () -> Unit = {},
    test: (TestCaseT) -> Unit
) {
  for ((index, testCase) in testCases.withIndex()) {
    beforeEach()
    try {
      withClue(
          {
            val clue: String = if (testCase is TestCase) testCase.name else testCase.toString()
            "Test Case ${index}: ${clue}"
          },
      ) {
        test(testCase)
      }
    } finally {
      afterEach()
    }
  }
}

/**
 * When passed to [parameterizedTest], [name] will be used instead of `toString` to print failing
 * test cases.
 */
internal interface TestCase {
  val name: String
}
