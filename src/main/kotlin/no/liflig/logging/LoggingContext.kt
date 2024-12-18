package no.liflig.logging

import kotlin.concurrent.getOrSet
import net.logstash.logback.marker.SingleFieldAppendingMarker

@PublishedApi internal val loggingContext = ThreadLocal<ArrayList<SingleFieldAppendingMarker>>()

/**
 * Adds the given [log fields][LogField] to every log made by a [Logger] in the context of the given
 * [block].
 *
 * An example of when this is useful is when processing an event, and you want the event to be
 * attached to every log while processing it. Instead of manually attaching the event to each log,
 * you can wrap the event processing in `withLoggingContext` with the event as a log field, and then
 * all logs inside that context will include the event.
 *
 * ### Example
 *
 * ```
 * import no.liflig.logging.Logger
 * import no.liflig.logging.field
 * import no.liflig.logging.withLoggingContext
 *
 * private val log = Logger {}
 *
 * fun example(event: Event) {
 *   withLoggingContext(field("event", event)) {
 *     log.debug { "Started processing event" }
 *     // ...
 *     log.debug { "Finished processing event" }
 *   }
 * }
 * ```
 *
 * The field from `withLoggingContext` will then be attached to every log, like:
 * ```json
 * { "message": "Started processing event", "event": { /* ... */  } }
 * { "message": "Finished processing event", "event": { /* ... */  } }
 * ```
 *
 * ### Note on coroutines
 *
 * `withLoggingContext` uses a thread-local, so it won't work with Kotlin coroutines and `suspend`
 * functions (though it does work with Java virtual threads). An alternative that supports
 * coroutines may be added in a future version of the library.
 */
inline fun <ReturnT> withLoggingContext(vararg logFields: LogField, block: () -> ReturnT): ReturnT {
  val contextFields = loggingContext.getOrSet { ArrayList(logFields.size) }
  contextFields.ensureCapacity(contextFields.size + logFields.size)
  // We add context fields in reverse when adding them to log events, to show the newest field
  // first. But if we called `withLoggingContext` with multiple fields, this would cause these
  // fields to show in reverse order to how they were passed. So to counteract that, we add the
  // fields to the logging context here in reverse order.
  logFields.forEachReversed { field -> contextFields.add(field.logstashField) }

  try {
    return block()
  } finally {
    for (i in logFields.indices) {
      contextFields.removeLast()
    }
    // Reset thread-local if list is empty, to avoid keeping the allocation around forever
    if (contextFields.isEmpty()) {
      loggingContext.remove()
    }
  }
}

internal fun getLogFieldsFromContext(): List<SingleFieldAppendingMarker> {
  // loggingContext list will be null if withLoggingContext has not been called in this thread
  return loggingContext.get() ?: emptyList()
}
