// We use Kotlin Contracts in `withLoggingContext`, for ergonomic use with lambdas. Contracts are
// an experimental feature, but they guarantee binary compatibility, so we can safely use them here
@file:OptIn(ExperimentalContracts::class)

package no.liflig.logging

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import org.slf4j.MDC
import org.slf4j.event.KeyValuePair

/**
 * Adds the given [log fields][LogField] to every log made by a [Logger] in the context of the given
 * [block].
 *
 * Use the [field]/[rawJsonField] functions to construct log fields.
 *
 * If an exception is thrown from [block], then the given log fields are attached to the exception,
 * and included in the log output when the exception is logged. That way, we don't lose logging
 * context when an exception escapes the context scope.
 *
 * An example of when this is useful is when processing an event, and you want to trace all the logs
 * made in the context of the event. Instead of manually attaching the event ID to each log, you can
 * wrap the event processing in `withLoggingContext`, with the event ID as a log field. All the logs
 * inside that context will then include the event ID as a structured log field, that you can filter
 * on in your log analysis tool.
 *
 * ### Field value encoding with SLF4J
 *
 * The implementation uses `MDC` from SLF4J, which only supports String values by default. But we
 * want to encode object values in the logging context as actual JSON (not escaped strings), so that
 * log analysis tools can parse the fields. If you're using Logback and
 * [`logstash-logback-encoder`](https://github.com/logfellow/logstash-logback-encoder) for JSON
 * output, you can add support for this by configuring
 * `no.liflig.logging.output.logback.JsonContextFieldWriter` as an `mdcEntryWriter`:
 * ```xml
 * <!-- Example Logback config (in src/main/resources/logback.xml) -->
 * <?xml version="1.0" encoding="UTF-8"?>
 * <configuration>
 *   <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
 *     <encoder class="net.logstash.logback.encoder.LogstashEncoder">
 *       <!-- Writes object values from logging context as actual JSON (not escaped) -->
 *       <mdcEntryWriter class="no.liflig.logging.output.logback.JsonContextFieldWriter"/>
 *     </encoder>
 *   </appender>
 *
 *   <root level="INFO">
 *     <appender-ref ref="STDOUT"/>
 *   </root>
 * </configuration
 * ```
 *
 * This requires that you have added `ch.qos.logback:logback-classic` and
 * `net.logstash.logback:logstash-logback-encoder` as dependencies.
 *
 * ### Note on coroutines
 *
 * SLF4J's `MDC` uses a thread-local, so it won't work by default with Kotlin coroutines and
 * `suspend` functions. If you use coroutines, you can solve this with
 * [`MDCContext` from `kotlinx-coroutines-slf4j`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-slf4j/kotlinx.coroutines.slf4j/-m-d-c-context/).
 *
 * ### Example
 *
 * ```
 * import no.liflig.logging.field
 * import no.liflig.logging.getLogger
 * import no.liflig.logging.withLoggingContext
 *
 * private val log = getLogger()
 *
 * fun example(event: Event) {
 *   withLoggingContext(field("eventId", event.id)) {
 *     log.debug { "Started processing event" }
 *     // ...
 *     log.debug { "Finished processing event" }
 *   }
 * }
 * ```
 *
 * If you have configured `no.liflig.logging.output.logback.JsonContextFieldWriter`, the field from
 * `withLoggingContext` will then be attached to every log as follows:
 * ```json
 * { "message": "Started processing event", "eventId": "..." }
 * { "message": "Finished processing event", "eventId": "..." }
 * ```
 */
public inline fun <ReturnT> withLoggingContext(
    vararg logFields: LogField,
    block: () -> ReturnT,
): ReturnT {
  // Allows callers to use `block` as if it were in-place
  contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }

  addFieldsToLoggingContext(logFields)
  try {
    return block()
  } catch (e: Exception) {
    addLoggingContextToException(e, logFields)
    throw e
  } finally {
    removeFieldsFromLoggingContext(logFields)
  }
}

/**
 * Adds the given [log fields][LogField] to every log made by a [Logger] in the context of the given
 * [block].
 *
 * Use the [field]/[rawJsonField] functions to construct log fields.
 *
 * If an exception is thrown from [block], then the given log fields are attached to the exception,
 * and included in the log output when the exception is logged. That way, we don't lose logging
 * context when an exception escapes the context scope.
 *
 * An example of when this is useful is when processing an event, and you want to trace all the logs
 * made in the context of the event. Instead of manually attaching the event ID to each log, you can
 * wrap the event processing in `withLoggingContext`, with the event ID as a log field. All the logs
 * inside that context will then include the event ID as a structured log field, that you can filter
 * on in your log analysis tool.
 *
 * ### Field value encoding with SLF4J
 *
 * The implementation uses `MDC` from SLF4J, which only supports String values by default. But we
 * want to encode object values in the logging context as actual JSON (not escaped strings), so that
 * log analysis tools can parse the fields. If you're using Logback and
 * [`logstash-logback-encoder`](https://github.com/logfellow/logstash-logback-encoder) for JSON
 * output, you can add support for this by configuring
 * `no.liflig.logging.output.logback.JsonContextFieldWriter` as an `mdcEntryWriter`:
 * ```xml
 * <!-- Example Logback config (in src/main/resources/logback.xml) -->
 * <?xml version="1.0" encoding="UTF-8"?>
 * <configuration>
 *   <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
 *     <encoder class="net.logstash.logback.encoder.LogstashEncoder">
 *       <!-- Writes object values from logging context as actual JSON (not escaped) -->
 *       <mdcEntryWriter class="no.liflig.logging.output.logback.JsonContextFieldWriter"/>
 *     </encoder>
 *   </appender>
 *
 *   <root level="INFO">
 *     <appender-ref ref="STDOUT"/>
 *   </root>
 * </configuration
 * ```
 *
 * This requires that you have added `ch.qos.logback:logback-classic` and
 * `net.logstash.logback:logstash-logback-encoder` as dependencies.
 *
 * ### Note on coroutines
 *
 * SLF4J's `MDC` uses a thread-local, so it won't work by default with Kotlin coroutines and
 * `suspend` functions. If you use coroutines, you can solve this with
 * [`MDCContext` from `kotlinx-coroutines-slf4j`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-slf4j/kotlinx.coroutines.slf4j/-m-d-c-context/).
 *
 * ### Example
 *
 * ```
 * import no.liflig.logging.field
 * import no.liflig.logging.getLogger
 * import no.liflig.logging.withLoggingContext
 *
 * private val log = getLogger()
 *
 * fun example(event: Event) {
 *   withLoggingContext(field("eventId", event.id)) {
 *     log.debug { "Started processing event" }
 *     // ...
 *     log.debug { "Finished processing event" }
 *   }
 * }
 * ```
 *
 * If you have configured `no.liflig.logging.output.logback.JsonContextFieldWriter`, the field from
 * `withLoggingContext` will then be attached to every log as follows:
 * ```json
 * { "message": "Started processing event", "eventId": "..." }
 * { "message": "Finished processing event", "eventId": "..." }
 * ```
 */
public inline fun <ReturnT> withLoggingContext(
    logFields: Collection<LogField>,
    block: () -> ReturnT
): ReturnT {
  // Allows callers to use `block` as if it were in-place
  contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }

  // The logging context implementation assumes that the field collection isn't mutated. We can't
  // guarantee this for `Collection`, so we must copy it to an array here. We don't need to do this
  // defensive copy for the `vararg` overload, since Kotlin always creates a new array for varargs:
  // https://discuss.kotlinlang.org/t/hidden-allocations-when-using-vararg-and-spread-operator/1640/2
  val fieldArray = logFields.toTypedArray()

  addFieldsToLoggingContext(fieldArray)
  try {
    return block()
  } catch (e: Exception) {
    addLoggingContextToException(e, fieldArray)
    throw e
  } finally {
    removeFieldsFromLoggingContext(fieldArray)
  }
}

/**
 * Adds the fields from an existing logging context to all logs made by a [Logger] in the context of
 * the given [block]. This overload is designed to be used with [getCopyOfLoggingContext], to pass
 * logging context between threads. If you want to add fields to the current thread's logging
 * context, you should instead construct log fields with the [field]/[rawJsonField] functions, and
 * pass them to one of the [withLoggingContext][withLoggingContext] overloads that take [LogField]s.
 *
 * If you spawn threads using a `java.util.concurrent.ExecutorService`, you may instead use the
 * `ExecutorService.inheritLoggingContext` extension function from this library, which passes
 * logging context from parent to child for you.
 *
 * If an exception is thrown from [block], then the given logging context is attached to the
 * exception, and included in the log output when the exception is logged. That way, we don't lose
 * logging context when an exception escapes the context scope.
 *
 * ### Example
 *
 * Scenario: We store an updated order in a database, and then want to asynchronously update
 * statistics for the order.
 *
 * ```
 * import no.liflig.logging.field
 * import no.liflig.logging.getCopyOfLoggingContext
 * import no.liflig.logging.getLogger
 * import no.liflig.logging.withLoggingContext
 * import kotlin.concurrent.thread
 *
 * private val log = getLogger()
 *
 * class OrderService(
 *     private val orderRepository: OrderRepository,
 *     private val statisticsService: StatisticsService,
 * ) {
 *   fun updateOrder(order: Order) {
 *     // This is the default withLoggingContext overload, adding context to the current thread
 *     withLoggingContext(field("order", order)) {
 *       orderRepository.update(order)
 *       updateStatistics(order)
 *     }
 *   }
 *
 *   // In this scenario, we don't want updateStatistics to block updateOrder, so we spawn a thread.
 *   //
 *   // But we want to log if it fails, and include the logging context from the parent thread.
 *   private fun updateStatistics(order: Order) {
 *     // We call getCopyOfLoggingContext here, to copy the context fields from the parent thread
 *     val parentContext = getCopyOfLoggingContext()
 *
 *     thread {
 *       // We then pass the parent context to withLoggingContext here in the child thread.
 *       // This uses the overload that takes an existing logging context
 *       withLoggingContext(parentContext) {
 *         try {
 *           statisticsService.orderUpdated(order)
 *         } catch (e: Exception) {
 *           // This log will get the "order" field from the parent logging context
 *           log.error(e) { "Failed to update order statistics" }
 *         }
 *       }
 *     }
 *   }
 * }
 * ```
 */
public inline fun <ReturnT> withLoggingContext(
    context: LoggingContext,
    block: () -> ReturnT
): ReturnT {
  // Allows callers to use `block` as if it were in-place
  contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }

  addExistingContextFieldsToLoggingContext(context)
  try {
    return block()
  } catch (e: Exception) {
    addExistingLoggingContextToException(e, context)
    throw e
  } finally {
    removeExistingContextFieldsFromLoggingContext(context)
  }
}

/**
 * Returns a copy of the log fields in the current thread's logging context (from
 * [withLoggingContext]). This can be used to pass logging context between threads (see example
 * below).
 *
 * If you spawn threads using a `java.util.concurrent.ExecutorService`, you may instead use the
 * `no.liflig.logging.inheritLoggingContext` extension function, which does the logging context
 * copying from parent to child for you.
 *
 * ### Example
 *
 * Scenario: We store an updated order in a database, and then want to asynchronously update
 * statistics for the order.
 *
 * ```
 * import no.liflig.logging.field
 * import no.liflig.logging.getCopyOfLoggingContext
 * import no.liflig.logging.getLogger
 * import no.liflig.logging.withLoggingContext
 * import kotlin.concurrent.thread
 *
 * private val log = getLogger()
 *
 * class OrderService(
 *     private val orderRepository: OrderRepository,
 *     private val statisticsService: StatisticsService,
 * ) {
 *   fun updateOrder(order: Order) {
 *     withLoggingContext(field("order", order)) {
 *       orderRepository.update(order)
 *       updateStatistics(order)
 *     }
 *   }
 *
 *   // In this scenario, we don't want updateStatistics to block updateOrder, so we spawn a thread.
 *   //
 *   // But we want to log if it fails, and include the logging context from the parent thread.
 *   // This is where getCopyOfLoggingContext comes in.
 *   private fun updateStatistics(order: Order) {
 *     // We call getCopyOfLoggingContext here, to copy the context fields from the parent thread
 *     val parentContext = getCopyOfLoggingContext()
 *
 *     thread {
 *       // We then pass the parent context to withLoggingContext here in the child thread
 *       withLoggingContext(parentContext) {
 *         try {
 *           statisticsService.orderUpdated(order)
 *         } catch (e: Exception) {
 *           // This log will get the "order" field from the parent logging context
 *           log.error(e) { "Failed to update order statistics" }
 *         }
 *       }
 *     }
 *   }
 * }
 * ```
 */
public fun getCopyOfLoggingContext(): LoggingContext {
  val contextMap = MDC.getCopyOfContextMap()
  if (contextMap.isNullOrEmpty()) {
    return EMPTY_LOGGING_CONTEXT
  }

  val contextState = LoggingContextState.get().copy()

  return LoggingContext(contextMap, contextState)
}

/**
 * Wraps thread-local logging context fields (SLF4J's MDC context map).
 *
 * This type is returned by [getCopyOfLoggingContext], and can be passed to one of the
 * [withLoggingContext] overloads in order to copy logging context between threads.
 */
public class LoggingContext
internal constructor(
    internal val map: Map<String, String?>?,
    internal val state: LoggingContextState,
) {
  internal fun isEmpty(): Boolean {
    return map.isNullOrEmpty()
  }

  internal fun getFields(): Array<out LogField>? {
    if (map.isNullOrEmpty()) {
      return null
    }

    val contextFields: Array<LogField?> = arrayOfNulls(map.size)
    var index = 0
    for ((key, value) in map) {
      val field: LogField =
          if (value == null) {
            LogField(key, JSON_NULL_VALUE, isJson = true)
          } else {
            val isJson = state.isJsonField(key, value)
            LogField(key, value, isJson)
          }

      contextFields[index] = field

      index++
    }

    // Safe to cast here: We initialize `logFields` with `map.size`, and then add a log field for
    // every entry in the map, so all elements in the array will be initialized
    @Suppress("UNCHECKED_CAST")
    return contextFields as Array<LogField>
  }
}

/** Static field for the empty logging context, to avoid redundant re-instantiations. */
internal val EMPTY_LOGGING_CONTEXT = LoggingContext(map = null, state = LoggingContextState.empty())

@PublishedApi
internal fun addFieldsToLoggingContext(fields: Array<out LogField>) {
  var contextState = LoggingContextState.get()
  val newFieldCount = fields.size

  for (index in fields.indices) {
    val field = fields[index]
    val key = field.key
    val value = field.value

    // Skip duplicate keys in the field array
    if (isDuplicateField(key, index, fields)) {
      continue
    }

    val previousValue: String? = MDC.get(key)

    MDC.put(key, value)

    contextState = contextState.add(key, value, field.isJson, previousValue, newFieldCount)
  }

  contextState.saveAfterAddingFields()
}

@PublishedApi
internal fun removeFieldsFromLoggingContext(fields: Array<out LogField>) {
  val contextState = LoggingContextState.get()

  for (index in fields.indices) {
    val field = fields[index]
    val key = field.key

    // Skip duplicate keys, like we do in addFields
    if (isDuplicateField(key, index, fields)) {
      continue
    }

    val overwrittenValue = contextState.popKey(key)
    if (overwrittenValue != null) {
      MDC.put(key, overwrittenValue)
    } else {
      MDC.remove(key)
    }
  }

  contextState.saveAfterRemovingFields()
}

private fun isDuplicateField(key: String, index: Int, fields: Array<out LogField>): Boolean {
  for (previousFieldIndex in 0 until index) {
    if (fields[previousFieldIndex].key == key) {
      return true
    }
  }
  return false
}

@PublishedApi
internal fun addExistingContextFieldsToLoggingContext(existingContext: LoggingContext) {
  val existingContextMap = existingContext.map
  if (existingContextMap == null) {
    return
  }
  val existingContextSize = existingContextMap.size

  var currentState = LoggingContextState.get()

  for ((key, value) in existingContextMap) {
    if (value == null) {
      continue
    }

    val previousValue: String? = MDC.get(key)

    MDC.put(key, value)

    val isJson = existingContext.state.isJsonField(key, value)
    currentState =
        currentState.add(key, value, isJson, previousValue, newFieldCount = existingContextSize)
  }

  currentState.saveAfterAddingFields()
}

/** Like [removeFieldsFromLoggingContext], but for [addExistingContextFieldsToLoggingContext]. */
@PublishedApi
internal fun removeExistingContextFieldsFromLoggingContext(existingContext: LoggingContext) {
  val existingContextMap = existingContext.map
  if (existingContextMap == null) {
    return
  }

  val currentContextState = LoggingContextState.get()

  for ((key, value) in existingContextMap) {
    // To match `addExistingContextFieldsToLoggingContext`
    if (value == null) {
      continue
    }

    val overwrittenValue = currentContextState.popKey(key)
    if (overwrittenValue != null) {
      MDC.put(key, overwrittenValue)
    } else {
      MDC.remove(key)
    }
  }

  currentContextState.saveAfterRemovingFields()
}

@PublishedApi
internal fun addLoggingContextToException(
    exception: Throwable,
    contextFields: Array<out LogField>,
) {
  if (contextFields.isEmpty()) {
    return
  }

  /**
   * If there's already an [ExceptionWithLoggingContext] or [LoggingContextProvider] in the
   * exception tree, we can add these log fields to their existing logging context, instead of
   * creating a new `LoggingContextProvider`.
   */
  traverseExceptionTree(root = exception) { exception ->
    when (exception) {
      is ExceptionWithLoggingContext -> {
        exception.addLoggingContext(contextFields)
        return
      }
      is LoggingContextProvider -> {
        exception.addLoggingContext(contextFields)
        return
      }
    }
  }

  /**
   * If there were no eligible exception to add the context to, we add a suppressed
   * [LoggingContextProvider] - see its docstring for more on how this mechanism works.
   */
  exception.addSuppressed(LoggingContextProvider(contextFields))
}

@PublishedApi
internal fun addExistingLoggingContextToException(
    exception: Throwable,
    existingContext: LoggingContext
) {
  val contextFields = existingContext.getFields()
  if (contextFields != null) {
    addLoggingContextToException(exception, contextFields)
  }
}

/**
 * We want to avoid duplicate field keys in the log output, and for newer fields to overwrite older
 * fields if they have the same key. We already do this for context fields and for log fields, but
 * separately - so if a log field and a context field have the same key, then they would both appear
 * in the log output. A log field is always more recent than a context field, so in this case, we
 * want the log field to overwrite the context field.
 *
 * This function handles this case: it goes through the given fields on a log event, and checks if
 * any of their keys are already in the logging context. If there's a match, we remove that context
 * field, saving it in [LoggingContextState] to be restored after the log with
 * [restoreContextFieldsOverwrittenForLog].
 *
 * Users of this function should call this before the log, and wrap the log in a try/finally block,
 * calling [restoreContextFieldsOverwrittenForLog] in the `finally` block.
 */
internal fun overwriteDuplicateContextFieldsForLog(logFields: MutableList<KeyValuePair>?) {
  if (logFields == null) {
    return
  }

  val totalFieldCount = logFields.size
  if (totalFieldCount == 0) {
    return
  }

  var contextState = LoggingContextState.get()

  var removedFieldCount = 0
  for (index in 0..(totalFieldCount - 1)) {
    val field = logFields[index - removedFieldCount]
    val key = field.key

    val contextValue: String? = MDC.get(key)
    if (contextValue == null) {
      continue
    }

    // We use `toString()` here, since the field value when using this library will either be a
    // `String` or a `RawJson` (whose `toString` returns the serialized JSON)
    val fieldValue = field.value.toString()
    if (fieldValue == contextValue) {
      logFields.removeAt(index)
      removedFieldCount++
    } else {
      MDC.remove(key)
      contextState = contextState.storeFieldOverwrittenForLog(key, overwrittenValue = contextValue)
    }
  }

  contextState.saveAfterAddingFields()
}

/** See [overwriteDuplicateContextFieldsForLog]. */
internal fun restoreContextFieldsOverwrittenForLog() {
  val contextState = LoggingContextState.get()

  contextState.restoreFieldsOverwrittenForLog { key, overwrittenValue ->
    MDC.put(key, overwrittenValue)
  }

  contextState.saveAfterRemovingFields()
}

/**
 * We use SLF4J's MDC for logging context. We want to use that instead of managing our own logging
 * context, so that our logging context can be included in logs made by other libraries that use
 * SLF4J but not `liflig-logging`.
 *
 * MDC is implemented as a thread-local `HashMap<String, String?>`. This limits what we can put in
 * the logging context to just String values. But in this library, we want to be able to put
 * arbitrary JSON in the logging context, so that the [field] function can have the same interface
 * as [LogBuilder.field]. Serialized JSON is a string, so we can put it in MDC, but the issue then
 * is that the JSON will be escaped in the log output, since our log encoder does not expect MDC
 * values to be JSON.
 *
 * So in order to support JSON field values in the logging context using MDC, we need 2 things:
 * - A way to intercept writing of MDC entries in our log encoder, to write JSON values unescaped.
 *     - `logstash-logback-encoder` (JSON encoder library for Logback) lets us do this, by
 *       implementing the `MdcEntryWriter` interface. We do this in our `JsonContextFieldWriter` in
 *       the `output.logback` subpackage.
 *     - The downside is that users must explicitly add `JsonContextFieldWriter` to their Logback
 *       config, but that's not too bad when we document it clearly in the README.
 * - A way to track which logging context values are JSON.
 *     - In a previous version of this library, we did this by adding a suffix to MDC keys that had
 *       JSON values, and then we would check for the suffix in our `JsonContextFieldWriter`. If the
 *       suffix was present, we could then write the value as unescaped JSON, and write the key
 *       without the suffix. There were 3 downsides to this approach:
 *         - We did not want to add this suffix if our users are not using `JsonContextFieldWriter`.
 *           The logic for ensuring that was tricky, and led to the key suffix leaking out into the
 *           logs in certain cases, which was not good.
 *         - Having some MDC keys _with_ a suffix and some without greatly complicated our logic for
 *           overwriting existing logging context entries with the same key, since there were 2
 *           variants of the same context key (suffixed and non-suffixed).
 *         - We had to add this suffix both when adding fields to the context, but also when
 *           checking for fields. This caused quite a lot of allocations from runtime string
 *           concatenation. A goal of this library is to make as few memory allocations as possible,
 *           so this was not ideal.
 *     - This class, `LoggingContextState`, is our new solution to this problem of tracking which
 *       context values are JSON.
 *         - It consists of a thread-local that lives side-by-side with SLF4J's MDC.
 *         - Whenever we add fields to the logging context, we also add to this context state, where
 *           we can track more state than what MDC allows us:
 *             - Firstly, we have a field to mark whether a context value is JSON.
 *             - Secondly, we can track _overwritten_ context values:
 *                 - When a new key is put into MDC, any previous value associated with that key is
 *                   overwritten, and gone.
 *                 - But in our `withLoggingContext` function, we want such overwritten context
 *                   values to be restored when the context scope exits, so that we don't lose outer
 *                   context just because we entered an inner context.
 *                 - To achieve this, we check for previous context values before adding a new field
 *                   in `withLoggingContext`, and we find a previous value, we add it to this
 *                   context state, so that we can restore it after the context scope.
 *
 * Because a logging context scope can live for a while, we want this context state to be as
 * memory-efficient as possible. To do this, we implement it as an array of strings, instead of as
 * an array of objects where we would have to allocate wrapper classes. Each field in the logging
 * context takes up 4 elements in the array, as follows:
 * 1. Key
 * 2. Value
 * 3. Marker for whether the field is JSON (see [LoggingContextState.JSON_FIELD_SENTINEL])
 * 4. Overwritten value from previous logging context, to be restored (`null` if there was none)
 *
 * This lets us store the context state as compactly as possible.
 */
@JvmInline
internal value class LoggingContextState
private constructor(private val stateArray: Array<String?>?) {
  /**
   * Adds a field to the logging context state.
   *
   * If the context field array has not been initialized yet, we initialize it before setting the
   * key/value, and return the new array. It is an error not to use the return value (unfortunately,
   * [Kotlin can't disallow unused return values yet](https://youtrack.jetbrains.com/issue/KT-12719)).
   *
   * Remember to call [saveAfterAddingFields] after this to persist the changes.
   */
  internal fun add(
      key: String,
      value: String?,
      isJson: Boolean,
      overwrittenValue: String?,
      newFieldCount: Int,
  ): LoggingContextState {
    if (newFieldCount <= 0) {
      return this
    }

    var state = getOrInitializeState(newFieldCount)
    var index = state.getAvailableIndex()
    if (index == -1) {
      // When we resize, the next available key index will be the size of the array before resizing
      index = state.stateArray.size
      state = state.resize(newFieldCount)
    }

    state.set(index, key, value, isJson, overwrittenValue)

    return LoggingContextState(state.stateArray)
  }

  /**
   * Removes the latest logging context field with the given key from the context state.
   *
   * Remember to call [saveAfterRemovingFields] after this to persist the changes.
   *
   * @return Previous overwritten value for the given key, if any (i.e., the same value passed as
   *   `overwrittenValue` to [add]).
   */
  internal fun popKey(key: String): String? {
    if (stateArray == null) {
      return null
    }

    val state = InitializedState(stateArray)
    state.forEachKeyReversed { index, stateKey ->
      if (stateKey == key) {
        val overwrittenValue = state.getOverwrittenValue(index)
        state.remove(index)
        return overwrittenValue
      }
    }

    return null
  }

  /**
   * Saves added fields to the current thread context state.
   *
   * This method must always be called after adding fields to the context, which include:
   * - [add]
   * - [storeFieldOverwrittenForLog]
   *
   * We don't automatically call this at the end of each of these methods, as we typically want to
   * add multiple fields in a row, so we don't need to save to the thread-local every time.
   *
   * See [saveAfterRemovingFields] for why we have two separate save methods.
   */
  internal fun saveAfterAddingFields() {
    if (stateArray != null) {
      setThreadLoggingContextState(stateArray)
    }
  }

  /**
   * Saves this context state, with fields removed, to the current thread context.
   *
   * This method must always be called after removing fields from the context, which include:
   * - [popKey]
   * - [restoreFieldsOverwrittenForLog]
   *
   * We don't automatically call this at the end of each of these methods, as we typically want to
   * remove multiple fields in a row, so we don't need to save to the thread-local every time.
   *
   * The reason for having a separate method for saving after _removing_ fields, is that we want to
   * check if the state array is empty after removing fields. If it is, we want to allow that memory
   * to be reclaimed, so we clear the thread-local.
   */
  internal fun saveAfterRemovingFields() {
    if (stateArray == null || InitializedState(stateArray).isEmpty()) {
      clearThreadLoggingContextState()
    } else {
      setThreadLoggingContextState(stateArray)
    }
  }

  internal fun isJsonField(key: String, value: String): Boolean {
    if (stateArray == null) {
      return false
    }

    val state = InitializedState(stateArray)
    state.forEachKeyReversed { index, stateKey ->
      if (stateKey == key) {
        val stateValue = state.getValue(index)
        val isJson = state.isJson(index)
        return isJson && stateValue == value
      }
    }

    return false
  }

  /**
   * See [overwriteDuplicateContextFieldsForLog] for why we use this.
   *
   * If the context field array has not been initialized yet, we initialize it before setting the
   * key/value, and return the new array. It is an error not to use the return value (unfortunately,
   * [Kotlin can't disallow unused return values yet](https://youtrack.jetbrains.com/issue/KT-12719)).
   *
   * Remember to call [saveAfterAddingFields] after this to persist changes.
   */
  internal fun storeFieldOverwrittenForLog(
      key: String,
      overwrittenValue: String
  ): LoggingContextState {
    return add(
        key = key,
        value = null,
        isJson = false,
        overwrittenValue = overwrittenValue,
        newFieldCount = 1,
    )
  }

  /**
   * See [overwriteDuplicateContextFieldsForLog] for why we use this.
   *
   * Remember to call [saveAfterRemovingFields] after this to persist changes.
   */
  internal inline fun restoreFieldsOverwrittenForLog(
      // `crossinline` because we don't want non-local returns in this lambda, to ensure that we
      // call `state.remove` after the action
      crossinline action: (key: String, overwrittenValue: String) -> Unit
  ) {
    if (stateArray == null) {
      return
    }

    val state = InitializedState(stateArray)
    state.forEachKeyReversed { index, key ->
      val value = state.getValue(index)
      // Fields overwritten for log will always be at the end of the array, and since we iterate
      // in reverse, we can stop iterating once we get to a field with non-null value
      if (value != null) {
        return
      }

      val overwrittenValue = state.getOverwrittenValue(index)
      if (overwrittenValue != null) {
        action(key, overwrittenValue)
        state.remove(index)
      }
    }
  }

  internal fun copy(): LoggingContextState {
    if (stateArray == null) {
      return this
    }

    val emptyFields = InitializedState(stateArray).countEmptyFields()

    val newSize = stateArray.size - emptyFields * ELEMENTS_PER_FIELD
    return LoggingContextState(stateArray.copyOf(newSize = newSize))
  }

  private fun getOrInitializeState(newFieldCount: Int): InitializedState {
    return if (stateArray != null) {
      InitializedState(stateArray)
    } else {
      InitializedState(arrayOfNulls(size = newFieldCount * ELEMENTS_PER_FIELD))
    }
  }

  /**
   * Helper class for a [LoggingContextState] where we know the state array is initialized
   * (non-null). This lets us avoid redundant null checks.
   *
   * Since this is a `value class` (same as [LoggingContextState]), wrapping the same array, there's
   * no allocation made when instantiating this.
   */
  @JvmInline
  internal value class InitializedState(internal val stateArray: Array<String?>) {
    internal fun set(
        index: StateKeyIndex,
        key: String?,
        value: String?,
        isJson: Boolean,
        overwrittenValue: String?,
    ) {
      val isJsonValue = if (isJson) JSON_FIELD_SENTINEL else null
      stateArray[index] = key
      stateArray[index + 1] = value
      stateArray[index + 2] = isJsonValue
      stateArray[index + 3] = overwrittenValue
    }

    internal fun getKey(index: StateKeyIndex): String? {
      return stateArray[index]
    }

    internal fun getValue(index: StateKeyIndex): String? {
      return stateArray[index + 1]
    }

    internal fun isJson(index: StateKeyIndex): Boolean {
      /**
       * See [LoggingContextState.JSON_FIELD_SENTINEL] for why we use referential equality (`===`)
       * here.
       */
      return stateArray[index + 2] === JSON_FIELD_SENTINEL
    }

    internal fun getOverwrittenValue(index: StateKeyIndex): String? {
      return stateArray[index + 3]
    }

    internal fun remove(index: StateKeyIndex) {
      stateArray.fill(element = null, fromIndex = index, toIndex = index + 3)
    }

    /** Returns -1 if no available index. */
    internal fun getAvailableIndex(): StateKeyIndex {
      var availableIndex = -1
      forEachKeyIndexReversed { index ->
        if (getKey(index) == null) {
          availableIndex = index
        } else {
          return availableIndex
        }
      }
      return availableIndex
    }

    internal fun resize(newFieldCount: Int): InitializedState {
      val newState = arrayOfNulls<String?>(stateArray.size + newFieldCount * ELEMENTS_PER_FIELD)

      stateArray.copyInto(newState)

      return InitializedState(newState)
    }

    internal fun isEmpty(): Boolean {
      forEachKeyReversed { _, _ ->
        // This function iterates over non-null keys, so if we get a single hit, then we know we're
        // not empty
        return false
      }
      return true
    }

    internal fun countEmptyFields(): Int {
      var emptyFields = 0
      forEachKeyIndexReversed { index ->
        if (getKey(index) == null) {
          emptyFields++
        } else {
          return emptyFields
        }
      }
      return emptyFields
    }

    internal inline fun forEachKeyReversed(action: (index: StateKeyIndex, key: String) -> Unit) {
      forEachKeyIndexReversed { index ->
        val key = getKey(index)
        if (key != null) {
          action(index, key)
        }
      }
    }

    internal inline fun forEachKeyIndexReversed(action: (index: StateKeyIndex) -> Unit) {
      val size = stateArray.size
      if (size < ELEMENTS_PER_FIELD) {
        return
      }

      var index = size - ELEMENTS_PER_FIELD
      while (index >= 0) {
        action(index)

        index -= ELEMENTS_PER_FIELD
      }
    }
  }

  internal companion object {
    @JvmStatic
    internal fun get(): LoggingContextState {
      return LoggingContextState(getThreadLoggingContextState())
    }

    @JvmStatic
    internal fun empty(): LoggingContextState {
      return LoggingContextState(null)
    }

    /** See [LoggingContextState]. */
    private const val ELEMENTS_PER_FIELD = 4

    /**
     * A sentinel value to mark whether a logging context field is JSON. We use a sentinel String
     * object for this instead of a boolean, because we want the [LoggingContextState] array to be
     * an array of Strings (since all the other values in the context state are Strings, and we
     * don't want to do casting).
     *
     * We don't use `const` here, since we want to be 100% sure that we use the same string
     * reference when inserting into the state array as when we check for this value, so we can use
     * faster reference equality (`===`) instead of structural equality (`==`).
     */
    @Suppress("MayBeConstant") private val JSON_FIELD_SENTINEL: String = "JSON"
  }
}

/**
 * See [LoggingContextState].
 *
 * We store the array directly here instead of storing a [LoggingContextState] object, because value
 * classes are boxed when used as generics, and we want to avoid such redundant allocations (see
 * [Kotlin docs](https://kotlinlang.org/docs/inline-classes.html#representation) for more on value
 * class boxing).
 */
private val THREAD_LOGGING_CONTEXT_STATE = ThreadLocal<Array<String?>?>()

internal fun getThreadLoggingContextState(): Array<String?>? {
  return THREAD_LOGGING_CONTEXT_STATE.get()
}

internal fun setThreadLoggingContextState(stateArray: Array<String?>) {
  THREAD_LOGGING_CONTEXT_STATE.set(stateArray)
}

internal fun clearThreadLoggingContextState() {
  THREAD_LOGGING_CONTEXT_STATE.remove()
}

/** A valid index for a key in the [LoggingContextState] array. */
private typealias StateKeyIndex = Int

/**
 * Wraps an [ExecutorService] in a new implementation that copies logging context fields (from
 * [withLoggingContext]) from the parent thread to child threads when spawning new tasks. This is
 * useful when you use an `ExecutorService` in the scope of a logging context, and you want the
 * fields from the logging context to also be included on the logs in the child tasks.
 *
 * ### Example
 *
 * Scenario: We store an updated order in a database, and then want to asynchronously update
 * statistics for the order.
 *
 * ```
 * import no.liflig.logging.field
 * import no.liflig.logging.getLogger
 * import no.liflig.logging.inheritLoggingContext
 * import no.liflig.logging.withLoggingContext
 * import java.util.concurrent.Executors
 *
 * private val log = getLogger()
 *
 * class OrderService(
 *     private val orderRepository: OrderRepository,
 *     private val statisticsService: StatisticsService,
 * ) {
 *   // Call inheritLoggingContext on the executor
 *   private val executor = Executors.newSingleThreadExecutor().inheritLoggingContext()
 *
 *   fun updateOrder(order: Order) {
 *     withLoggingContext(field("order", order)) {
 *       orderRepository.update(order)
 *       updateStatistics(order)
 *     }
 *   }
 *
 *   // In this scenario, we don't want updateStatistics to block updateOrder, so we use an
 *   // ExecutorService to spawn a thread.
 *   //
 *   // But we want to log if it fails, and include the logging context from the parent thread.
 *   // This is where inheritLoggingContext comes in.
 *   private fun updateStatistics(order: Order) {
 *     executor.execute {
 *       try {
 *         statisticsService.orderUpdated(order)
 *       } catch (e: Exception) {
 *         // This log will get the "order" field from the parent logging context
 *         log.error(e) { "Failed to update order statistics" }
 *       }
 *     }
 *   }
 * }
 * ```
 */
public fun ExecutorService.inheritLoggingContext(): ExecutorService {
  return ExecutorServiceWithInheritedLoggingContext(this)
}

/**
 * Implementation for [inheritLoggingContext]. Wraps the methods on the given [ExecutorService] that
 * take a [Callable]/[Runnable] with [wrapCallable]/[wrapRunnable], which copy the logging context
 * fields from the spawning thread to the spawned tasks.
 */
@JvmInline // Inline value class, since we just wrap another ExecutorService
internal value class ExecutorServiceWithInheritedLoggingContext(
    private val wrappedExecutor: ExecutorService,
) :
    // Use interface delegation here, so we only override the methods we're interested in.
    ExecutorService by wrappedExecutor {

  private fun <T> wrapCallable(callable: Callable<T>): Callable<T> {
    // Copy context fields here, to get the logging context of the parent thread.
    // We then pass this to withLoggingContext in the returned Callable below, which will be invoked
    // in the child thread, thus inheriting the parent's context fields.
    val parentLoggingContext = getCopyOfLoggingContext()

    if (parentLoggingContext.isEmpty()) {
      return callable
    }

    return Callable { withLoggingContext(parentLoggingContext) { callable.call() } }
  }

  private fun wrapRunnable(runnable: Runnable): Runnable {
    // Copy context fields here, to get the logging context of the parent thread.
    // We then pass this to withLoggingContext in the returned Runnable below, which will be invoked
    // in the child thread, thus inheriting the parent's context fields.
    val parentLoggingContext = getCopyOfLoggingContext()

    if (parentLoggingContext.isEmpty()) {
      return runnable
    }

    return Runnable { withLoggingContext(parentLoggingContext) { runnable.run() } }
  }

  override fun execute(command: Runnable) {
    wrappedExecutor.execute(wrapRunnable(command))
  }

  override fun <T : Any?> submit(task: Callable<T>): Future<T> {
    return wrappedExecutor.submit(wrapCallable(task))
  }

  override fun submit(task: Runnable): Future<*> {
    return wrappedExecutor.submit(wrapRunnable(task))
  }

  override fun <T : Any?> submit(task: Runnable, result: T): Future<T> {
    return wrappedExecutor.submit(wrapRunnable(task), result)
  }

  override fun <T : Any?> invokeAll(
      tasks: MutableCollection<out Callable<T>>
  ): MutableList<Future<T>> {
    return wrappedExecutor.invokeAll(tasks.map { wrapCallable(it) })
  }

  override fun <T : Any?> invokeAll(
      tasks: MutableCollection<out Callable<T>>,
      timeout: Long,
      unit: TimeUnit
  ): MutableList<Future<T>> {
    return wrappedExecutor.invokeAll(tasks.map { wrapCallable(it) }, timeout, unit)
  }

  override fun <T : Any> invokeAny(tasks: MutableCollection<out Callable<T>>): T {
    return wrappedExecutor.invokeAny(tasks.map { wrapCallable(it) })
  }

  override fun <T : Any?> invokeAny(
      tasks: MutableCollection<out Callable<T>>,
      timeout: Long,
      unit: TimeUnit
  ): T {
    return wrappedExecutor.invokeAny(tasks.map { wrapCallable(it) }, timeout, unit)
  }
}
