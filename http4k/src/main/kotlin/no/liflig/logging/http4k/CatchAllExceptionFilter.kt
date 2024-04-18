package no.liflig.logging.http4k

import org.http4k.core.Filter
import org.http4k.core.Response
import org.http4k.core.Status
import org.slf4j.LoggerFactory

/**
 * Filter to avoid leaking exceptions to the client. This does not store anything on context but
 * logs the error and returns an 500 error.
 *
 * This filter is a last resort and should generally only be triggered on some other failure to
 * properly handle an error. This is because if an exception propagates pass this filter and to
 * Jetty, it will be shown to the client with a Jetty-specific response.
 */
@Deprecated("Replace usage with liflig-http4k-setup")
object CatchAllExceptionFilter {
  private val logger = LoggerFactory.getLogger(CatchAllExceptionFilter.javaClass)

  operator fun invoke() = Filter { next ->
    { request ->
      try {
        next(request)
      } catch (e: Throwable) {
        logger.error("Unhandled exception caught", e)
        Response(Status.INTERNAL_SERVER_ERROR).body("Something went wrong")
      }
    }
  }
}
