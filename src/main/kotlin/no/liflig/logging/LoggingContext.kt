package no.liflig.logging

import kotlin.concurrent.getOrSet
import net.logstash.logback.marker.SingleFieldAppendingMarker

@PublishedApi internal val loggingContext = ThreadLocal<ArrayList<SingleFieldAppendingMarker>>()

/**
 * Adds the given [log markers][LogMarker] to a thread-local list, which is added to every log made
 * by a [Logger] in the context of the given [block].
 *
 * An example of when this is useful is when processing an event, and you want the event to be
 * attached to every log while processing it. Instead of manually attaching the event to each log,
 * you can wrap the event processing in `withLoggingContext` with the event as a log marker, and
 * then all logs inside that context will include the event.
 *
 * ### Example
 *
 * ```
 * import no.liflig.logging.Logger
 * import no.liflig.logging.marker
 * import no.liflig.logging.withLoggingContext
 *
 * private val log = Logger {}
 *
 * fun example(event: Event) {
 *   withLoggingContext(marker("event", event)) {
 *     log.debug { "Started processing event" }
 *     // ...
 *     log.debug { "Finished processing event" }
 *   }
 * }
 * ```
 *
 * The marker from `withLoggingContext` will then be attached to every log, like:
 * ```json
 * { "message": "Started processing event", "event": { /* ... */  } }
 * { "message": "Finished processing event", "event": { /* ... */  } }
 * ```
 *
 * ### Note on coroutines
 *
 * Since `withLoggingContext` uses a thread-local, it won't work with Kotlin coroutines and
 * `suspend` functions (though it does work with Java virtual threads). An alternative that supports
 * coroutines may be added in a future version of the library.
 */
inline fun <ReturnT> withLoggingContext(
    vararg logMarkers: LogMarker,
    block: () -> ReturnT
): ReturnT {
  val contextMarkers = loggingContext.getOrSet { ArrayList(logMarkers.size) }
  // We add context markers in reverse when adding them to log events, to show the newest marker
  // first. But if we called `withLoggingContext` with multiple markers, this would cause these
  // markers to show in reverse order to how they were passed. So to counteract that, we add the
  // markers to the logging context here in reverse order.
  logMarkers.forEachReversed { marker -> contextMarkers.add(marker.logstashMarker) }

  try {
    return block()
  } finally {
    for (i in logMarkers.indices) {
      contextMarkers.removeLast()
    }
    // Reset thread-local if list is empty, to avoid keeping the allocation around forever
    if (contextMarkers.isEmpty()) {
      loggingContext.remove()
    }
  }
}
