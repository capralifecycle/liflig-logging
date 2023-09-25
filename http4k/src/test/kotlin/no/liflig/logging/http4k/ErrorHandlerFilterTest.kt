package no.liflig.logging.http4k

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.beInstanceOf
import no.liflig.logging.ErrorLog
import org.eclipse.jetty.io.EofException
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.lens.BiDiLens
import org.http4k.lens.Meta
import org.http4k.lens.ParamMeta
import org.junit.jupiter.api.Test

class ErrorHandlerFilterTest {
  @Test
  fun `maps jetty EofException as bad user request`() {
    var errorLog: ErrorLog? = null

    val errorLogLens =
        BiDiLens<Request, ErrorLog?>(
            Meta(false, "dummy", ParamMeta.StringParam, "dummy"),
            { ErrorLog(RuntimeException("dummy")) },
            { thisErrorLog, request ->
              errorLog = thisErrorLog
              request
            },
        )

    val handler = ErrorHandlerFilter(errorLogLens).then { throw EofException("doh") }

    val response = handler(Request(Method.GET, "/dummy"))

    response.status shouldBe Status.BAD_REQUEST

    val currentErrorLog = errorLog

    currentErrorLog.shouldNotBeNull()
    currentErrorLog.throwable should beInstanceOf<EofException>()
  }

  @Test
  fun `maps any other exception to internal server error`() {
    var errorLog: ErrorLog? = null

    val errorLogLens =
        BiDiLens<Request, ErrorLog?>(
            Meta(false, "dummy", ParamMeta.StringParam, "dummy"),
            { ErrorLog(RuntimeException("dummy")) },
            { thisErrorLog, request ->
              errorLog = thisErrorLog
              request
            },
        )

    val handler = ErrorHandlerFilter(errorLogLens).then { throw RuntimeException("doh") }

    val response = handler(Request(Method.GET, "/dummy"))

    response.status shouldBe Status.INTERNAL_SERVER_ERROR

    val currentErrorLog = errorLog

    currentErrorLog.shouldNotBeNull()
    currentErrorLog.throwable should beInstanceOf<RuntimeException>()
  }

  @Test
  fun `catches errors such as NotImplementedError`() {
    var errorLog: ErrorLog? = null

    val errorLogLens =
        BiDiLens<Request, ErrorLog?>(
            Meta(false, "dummy", ParamMeta.StringParam, "dummy"),
            { ErrorLog(RuntimeException("dummy")) },
            { thisErrorLog, request ->
              errorLog = thisErrorLog
              request
            },
        )

    val handler = ErrorHandlerFilter(errorLogLens).then { throw NotImplementedError("doh") }

    val response = handler(Request(Method.GET, "/dummy"))

    response.status shouldBe Status.INTERNAL_SERVER_ERROR

    val currentErrorLog = errorLog

    currentErrorLog.shouldNotBeNull()
    currentErrorLog.throwable should beInstanceOf<NotImplementedError>()
  }
}
