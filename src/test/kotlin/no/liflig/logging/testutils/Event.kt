package no.liflig.logging.testutils

import kotlinx.serialization.Serializable

/** Serializable example class for tests. */
@Serializable internal data class Event(val id: Long, val type: EventType)

internal enum class EventType {
  ORDER_PLACED,
  ORDER_UPDATED,
}
