package no.liflig.logging.http4k

import io.mockk.mockk
import no.liflig.snapshot.verifyStringSnapshot
import org.http4k.contract.JsonErrorResponseRenderer
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.format.Jackson
import org.http4k.lens.BiDiLens
import org.http4k.lens.Query
import org.junit.jupiter.api.Test

class RequestLensFailureFilterTest {

  val filter = RequestLensFailureFilter(
    ErrorResponseRendererWithLogging(
      BiDiLens(mockk(), mockk()) { _, req -> req },
      BiDiLens(mockk(), mockk()) { _, req -> req },
      JsonErrorResponseRenderer(Jackson),
    ),
  )

  @Test
  fun `required query lens not given should give expected response`() {
    val handler = filter {
      Query.required("test")(it)
      Response(Status.OK)
    }

    val response = handler(Request(Method.GET, "/"))
    verifyStringSnapshot(
      "RequestLensFailureFilter-query-required.txt",
      response.toString(),
    )
  }

  @Test
  fun `mismatch on mapping for query lens should give expected response`() {
    val handler = filter {
      Query.map<String> { throw IllegalArgumentException() }.required("test")(it)
      Response(Status.OK)
    }

    val response = handler(Request(Method.GET, "/?test=abc"))
    verifyStringSnapshot(
      "RequestLensFailureFilter-query-mapping-error.txt",
      response.toString(),
    )
  }
}
