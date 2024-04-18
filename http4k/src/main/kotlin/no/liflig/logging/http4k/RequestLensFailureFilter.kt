package no.liflig.logging.http4k

import org.http4k.contract.ErrorResponseRenderer
import org.http4k.core.Filter
import org.http4k.filter.ServerFilters

/**
 * Handle lens failures for request data. Saves the error on context so it can be used during
 * logging.
 */
@Deprecated("Replace usage with liflig-http4k-setup")
object RequestLensFailureFilter {
  operator fun invoke(errorResponseRenderer: ErrorResponseRenderer): Filter =
      ServerFilters.CatchLensFailure(errorResponseRenderer::badRequest)
}
