package no.liflig.logging.http4k

import java.util.UUID
import no.liflig.logging.ErrorLog
import no.liflig.logging.NormalizedStatus
import org.http4k.core.RequestContexts
import org.http4k.lens.RequestContextKey

/**
 * Operates on [ErrorLog] instances in the [RequestContexts].
 *
 * Used together with:
 * - [ErrorHandlerFilter] to attach errors on the context
 * - [ErrorResponseRendererWithLogging] to attach lens failures on the context after 400 Bad Request
 * - [LoggingFilter] to read and log errors from the context (via a `logHandler`)
 */
fun createErrorLogLens(contexts: RequestContexts) = RequestContextKey.optional<ErrorLog>(contexts)

fun createRequestIdChainLens(contexts: RequestContexts) =
    RequestContextKey.required<List<UUID>>(contexts)

fun createNormalizedStatusLens(contexts: RequestContexts) =
    RequestContextKey.optional<NormalizedStatus>(contexts)
