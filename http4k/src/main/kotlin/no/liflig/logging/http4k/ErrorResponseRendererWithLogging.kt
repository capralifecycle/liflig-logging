package no.liflig.logging.http4k

import no.liflig.logging.ErrorLog
import no.liflig.logging.NormalizedStatus
import org.http4k.contract.ErrorResponseRenderer
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.with
import org.http4k.lens.BiDiLens
import org.http4k.lens.LensFailure

/**
 * Responsible for converting lens failure to bad request response and providing body in proper
 * json-format. It also puts throwables in request context for logging.
 *
 * Must be applied to:
 * - Contract renderer: To catch any contractual errors.
 * - RequestLensFailureFilter: To catch any non-contractual errors.
 *
 * ContractRoutingHttpHandler has its own CatchLensFailure-filter set up so any contractual errors
 * are swallowed before it reaches the global RequestLensFailureFilter. Therefore we must also
 * provide this handler to the contract renderer so that it is called in this filter.
 *
 * E.g.
 * /path/to/api bind contract {
 *   renderer = OpenApi3(
 *     apiInfo = ApiInfo(...),
 *     errorResponseRenderer = ErrorResponseRendererWithLogging(...)
 *   )
 * }
 */
class ErrorResponseRendererWithLogging(
  private val errorLogLens: BiDiLens<Request, ErrorLog?>,
  private val normalizedStatusLens: BiDiLens<Request, NormalizedStatus?>,
  private val delegate: ErrorResponseRenderer,
) : ErrorResponseRenderer {
  override fun badRequest(lensFailure: LensFailure): Response {
    val target = lensFailure.target
    check(target is Request)

    // RequestContext is bound to the Request object.
    target.with(errorLogLens of ErrorLog(lensFailure))

    // Reset any normalized status in case it is set earlier.
    // RequestContext is bound to the Request object.
    target.with(normalizedStatusLens of null)

    return delegate.badRequest(lensFailure)
  }
}
