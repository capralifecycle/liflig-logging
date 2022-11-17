package no.liflig.logging.http4k

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import net.logstash.logback.marker.Markers
import no.liflig.logging.ErrorLog
import no.liflig.logging.NormalizedStatus
import no.liflig.logging.PrincipalLog
import no.liflig.logging.RequestLog
import no.liflig.logging.RequestResponseLog
import no.liflig.logging.ResponseLog
import org.http4k.core.ContentType
import org.http4k.core.Filter
import org.http4k.core.Headers
import org.http4k.core.HttpMessage
import org.http4k.core.Request
import org.http4k.lens.BiDiLens
import org.http4k.lens.Header
import org.http4k.lens.RequestContextLens
import org.slf4j.LoggerFactory
import org.slf4j.helpers.NOPLogger
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Filter to handle request logging.
 */
object LoggingFilter {
  private val logger = LoggerFactory.getLogger(LoggingFilter.javaClass)

  init {
    if (logger is NOPLogger) {
      throw RuntimeException("Logging is not configured!")
    }
  }

  /**
   * The maximum size of a CloudWatch log event is 256 KiB.
   *
   * From our experience storing longer lines will result in the
   * line being wrapped, so it no longer will be parsed correctly
   * as JSON.
   *
   * We limit the body size we store to stay below this limit.
   */
  private const val MAX_BODY_LOGGED = 50 * 1024

  private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
  }

  private fun cleanAndNormalizeHeaders(headers: Headers, redactedHeaders: List<String>): List<Map<String, String?>> =
    headers.map { (name, value) ->
      mapOf(
        "name" to name,
        "value" to when {
          redactedHeaders.any { it.equals(name, ignoreCase = true) } -> "*REDACTED*"
          else -> value
        },
      )
    }

  private fun String.limitBody() =
    if (length > MAX_BODY_LOGGED) {
      take(MAX_BODY_LOGGED) + "**CAPPED**"
    } else {
      this
    }

  // Only log specific content types.
  // Include lack of content type as it is usually due to an error.
  private fun HttpMessage.shouldLogBody(contentTypesToLog: List<ContentType>): Boolean {
    val contentType = Header.CONTENT_TYPE(this)
    return contentType == null || contentTypesToLog.any { contentType.value == it.value }
  }

  operator fun <T : PrincipalLog> invoke(
    principalLog: (Request) -> T?,
    errorLogLens: BiDiLens<Request, ErrorLog?>,
    normalizedStatusLens: BiDiLens<Request, NormalizedStatus?>,
    requestIdChainLens: RequestContextLens<List<UUID>>,
    logHandler: (RequestResponseLog<T>) -> Unit,
    includeBody: Boolean = true,
    contentTypesToLog: List<ContentType> = listOf(ContentType.APPLICATION_JSON),
    redactedHeaders: List<String> = listOf("authorization", "x-api-key"),
  ) = Filter { next ->
    { request ->
      val requestIdChain = requestIdChainLens(request)
      val startTimeInstant = Instant.now()
      val startTime = System.nanoTime()

      // Pass to the next filters.
      val response = next(request)

      val endTimeInstant = Instant.now()
      val duration = Duration.ofNanos(System.nanoTime() - startTime)

      val logRequestBody = includeBody && request.shouldLogBody(contentTypesToLog)
      val logResponseBody = includeBody && response.shouldLogBody(contentTypesToLog)

      val requestBody = if (logRequestBody) request.bodyString() else null
      val responseBody = if (logResponseBody) response.bodyString() else null

      val logEntry = RequestResponseLog(
        timestamp = Instant.now(),
        requestId = requestIdChain.last(),
        requestIdChain = requestIdChain,
        request = RequestLog(
          timestamp = startTimeInstant,
          method = request.method.toString(),
          uri = request.uri.toString(),
          headers = cleanAndNormalizeHeaders(request.headers, redactedHeaders),
          size = request.body.length?.toInt() ?: requestBody?.length,
          body = requestBody?.limitBody(),
        ),
        response = ResponseLog(
          timestamp = endTimeInstant,
          statusCode = response.status.code,
          headers = cleanAndNormalizeHeaders(response.headers, redactedHeaders),
          size = response.body.length?.toInt() ?: responseBody?.length,
          body = responseBody?.limitBody(),
        ),
        principal = principalLog(request),
        durationMs = duration.toMillis(),
        throwable = errorLogLens(request)?.throwable,
        status = normalizedStatusLens(request) ?: deriveNormalizedStatus(response),
        thread = Thread.currentThread().name,
      )

      logHandler(logEntry)

      response
    }
  }

  /**
   * Log handler that will log information from the request using INFO level
   * under the requestInfo property.
   *
   * This relies on using the "net.logstash.logback.encoder.LogstashEncoder"
   * Logback encoder, since it uses special markers that it will parse.
   *
   * If [printStacktraceToConsole] is true, any throwable attached to the log entry
   * will be printed to stdout. This is typically used locally to easy debugging
   * during development.
   *
   * If [supressSuccessfulHealthChecks] is true, any calls to /health that returned HTTP200 - Ok will not be logged.
   */
  fun <T : PrincipalLog> createLogHandler(
    printStacktraceToConsole: Boolean,
    principalLogSerializer: KSerializer<T>,
    suppressSuccessfulHealthChecks: Boolean = true,
  ): (RequestResponseLog<T>) -> Unit {
    return handler@{ entry ->
      val request = entry.request
      val response = entry.response

      if (suppressSuccessfulHealthChecks && request.uri == "/health" && response.statusCode == 200 && entry.throwable == null) {
        return@handler
      }

      logger.info(
        "HTTP request (${response.statusCode}) (${entry.durationMs} ms): ${request.method} ${request.uri}",
        Markers.appendRaw(
          "requestInfo",
          json.encodeToString(RequestResponseLog.serializer(principalLogSerializer), entry),
        ),
      )

      val throwable = entry.throwable
      if (printStacktraceToConsole && throwable != null) {
        // Using println instead of logger to not scramble logfile.
        println("Throwable from request ${request.method} ${request.uri}:")
        throwable.printStackTrace()
      }
    }
  }
}
