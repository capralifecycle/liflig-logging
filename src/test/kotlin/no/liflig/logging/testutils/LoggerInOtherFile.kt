package no.liflig.logging.testutils

import no.liflig.logging.getLogger

/**
 * Used in [no.liflig.logging.LoggerTest] to test that the logger gets the expected name from the
 * file it's constructed in.
 */
internal val loggerInOtherFile = getLogger()
