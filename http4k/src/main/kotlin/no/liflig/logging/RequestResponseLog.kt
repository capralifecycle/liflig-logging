@file:UseSerializers(
  InstantSerializer::class,
  NormalizedStatusSerializer::class,
  ThrowableSerializer::class,
  UUIDSerializer::class,
)

package no.liflig.logging

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.util.UUID

interface PrincipalLog

@Serializable
data class RequestResponseLog<T : PrincipalLog>(
  /**
   * Timestamp when the log entry is created.
   */
  val timestamp: Instant,
  val requestId: UUID,
  /**
   * The request-ID chain contains all traced request-IDs. The last element
   * is the newest in the chain, and will always reference this request itself
   * and have the same value as [requestId].
   */
  val requestIdChain: List<UUID>,
  val request: RequestLog,
  val response: ResponseLog,
  val principal: T?,
  /**
   * Request duration in ms.
   */
  val durationMs: Long,
  /**
   * Throwable during handling of request/response.
   */
  val throwable: Throwable?,
  val status: NormalizedStatus?,
  /**
   * Name of the thread handling the request.
   */
  val thread: String,
)

@Serializable
data class RequestLog(
  /**
   * Timestamp when we first saw the request.
   */
  val timestamp: Instant,
  val method: String,
  val uri: String,
  val headers: List<Map<String, String?>>,
  val size: Int?,
  val body: String?,
)

@Serializable
data class ResponseLog(
  /**
   * Timestamp when we last saw the response.
   */
  val timestamp: Instant,
  val statusCode: Int,
  val headers: List<Map<String, String?>>,
  val size: Int?,
  val body: String?,
)
