package no.liflig.logging.http4k

import no.liflig.logging.ErrorLog
import org.http4k.core.Filter
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.lens.BiDiLens

/**
 * Catches throwables and attaches them to the request with `errorLogLens`. Then responds with `500
 * Internal Server Error` for all exceptions, except jetty EofException which returns `400 Bad
 * Request`.
 */
object ErrorHandlerFilter {
  operator fun invoke(errorLogLens: BiDiLens<Request, ErrorLog?>) = Filter { next ->
    { request ->
      try {
        next(request)
      } catch (e: Throwable) {
        // RequestContext is bound to the Request object.
        request.with(errorLogLens of ErrorLog(e))

        // Check for class name without depending on Jetty itself.
        if (e::class.java.name == "org.eclipse.jetty.io.EofException") {
          Response(Status.BAD_REQUEST).body("EofException")
        } else {
          Response(Status.INTERNAL_SERVER_ERROR).body("Something went wrong.")
        }
      }
    }
  }
}
