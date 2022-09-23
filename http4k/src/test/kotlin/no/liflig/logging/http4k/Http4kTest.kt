package no.liflig.logging.http4k

import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import no.liflig.logging.PrincipalLog
import no.liflig.logging.RequestResponseLog
import org.http4k.contract.JsonErrorResponseRenderer
import org.http4k.contract.bind
import org.http4k.contract.contract
import org.http4k.contract.meta
import org.http4k.contract.openapi.ApiInfo
import org.http4k.contract.openapi.v3.OpenApi3
import org.http4k.core.Filter
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.RequestContexts
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.filter.ServerFilters
import org.http4k.format.Jackson
import org.http4k.lens.RequestContextKey
import org.junit.jupiter.api.Test

class Http4kTest {

  @Test
  fun `can be set up properly`() {
    val contexts = RequestContexts()

    val requestIdChainLens = createRequestIdChainLens(contexts)
    val errorLogLens = createErrorLogLens(contexts)
    val normalizedStatusLens = createNormalizedStatusLens(contexts)

    val principalLens = RequestContextKey.optional<Principal>(contexts)

    val principalLog = { request: Request ->
      principalLens(request)?.toLog()
    }

    val logHandler = LoggingFilter.createLogHandler(
      printStacktraceToConsole = false,
      principalLogSerializer = MyPrincipalLog.serializer(),
    )

    val errorResponseRenderer = ErrorResponseRendererWithLogging(
      errorLogLens,
      normalizedStatusLens,
      JsonErrorResponseRenderer(Jackson),
    )

    val api = "/" bind contract {
      renderer = OpenApi3(
        apiInfo = ApiInfo(
          title = "example",
          version = "unversioned",
        ),
        json = Jackson,
        errorResponseRenderer = errorResponseRenderer,
      )
      routes += "/" meta {
        summary = "Example route"
      } bindContract Method.GET to { _ ->
        Response(Status.OK)
      }
    }

    lateinit var logEntry: RequestResponseLog<MyPrincipalLog>

    val handler = ServerFilters
      .InitialiseRequestContext(contexts)
      .then(RequestIdMdcFilter(requestIdChainLens))
      .then(CatchAllExceptionFilter())
      .then(
        LoggingFilter(
          principalLog,
          errorLogLens,
          normalizedStatusLens,
          requestIdChainLens,
          {
            logEntry = it
            logHandler(it)
          },
        ),
      )
      .then(ErrorHandlerFilter(errorLogLens))
      .then(RequestLensFailureFilter(errorResponseRenderer))
      .then(
        Filter { next ->
          { req ->
            val dummyPrincipal = Principal("dummy-principal")
            next(req.with(principalLens of dummyPrincipal))
          }
        },
      )
      .then(api)

    val response = handler(Request(Method.GET, "/"))

    response.status shouldBe Status.OK
    logEntry.principal?.id shouldBe "dummy-principal"
  }

  @Serializable
  private data class MyPrincipalLog(
    val id: String,
  ) : PrincipalLog

  private data class Principal(
    val id: String,
  )

  private fun Principal.toLog() =
    MyPrincipalLog(id)
}
