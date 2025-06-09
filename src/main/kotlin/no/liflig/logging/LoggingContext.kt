package no.liflig.logging

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.slf4j.MDC

/**
 * Adds the given [log fields][LogField] to every log made by a [Logger] in the context of the given
 * [block]. Use the [field]/[rawJsonField] functions to construct log fields.
 *
 * An example of when this is useful is when processing an event, and you want the event to be
 * attached to every log while processing it. Instead of manually attaching the event to each log,
 * you can wrap the event processing in `withLoggingContext` with the event as a log field, and then
 * all logs inside that context will include the event.
 *
 * ### Field value encoding with SLF4J
 *
 * The implementation uses `MDC` from SLF4J, which only supports String values by default. To encode
 * object values as actual JSON (not escaped strings), you can add
 * `no.liflig.logging.LoggingContextJsonFieldWriter` as an `mdcEntryWriter` for
 * [`logstash-logback-encoder`](https://github.com/logfellow/logstash-logback-encoder), like this:
 * ```xml
 * <!-- Example Logback config (in src/main/resources/logback.xml) -->
 * <?xml version="1.0" encoding="UTF-8"?>
 * <configuration>
 *   <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
 *     <encoder class="net.logstash.logback.encoder.LogstashEncoder">
 *       <!-- Writes object values from logging context as actual JSON (not escaped) -->
 *       <mdcEntryWriter class="no.liflig.logging.LoggingContextJsonFieldWriter"/>
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
 *   withLoggingContext(field("event", event)) {
 *     log.debug { "Started processing event" }
 *     // ...
 *     log.debug { "Finished processing event" }
 *   }
 * }
 * ```
 *
 * If you have configured `no.liflig.logging.LoggingContextJsonFieldWriter`, the field from
 * `withLoggingContext` will then be attached to every log as follows:
 * ```json
 * { "message": "Started processing event", "event": { ... } }
 * { "message": "Finished processing event", "event": { ... } }
 * ```
 */
public inline fun <ReturnT> withLoggingContext(
    vararg logFields: LogField,
    block: () -> ReturnT
): ReturnT {
  return withLoggingContextInternal(logFields, block)
}

/**
 * Adds the given [log fields][LogField] to every log made by a [Logger] in the context of the given
 * [block]. Use the [field]/[rawJsonField] functions to construct log fields.
 *
 * An example of when this is useful is when processing an event, and you want the event to be
 * attached to every log while processing it. Instead of manually attaching the event to each log,
 * you can wrap the event processing in `withLoggingContext` with the event as a log field, and then
 * all logs inside that context will include the event.
 *
 * ### Field value encoding with SLF4J
 *
 * The implementation uses `MDC` from SLF4J, which only supports String values by default. To encode
 * object values as actual JSON (not escaped strings), you can add
 * `no.liflig.logging.LoggingContextJsonFieldWriter` as an `mdcEntryWriter` for
 * [`logstash-logback-encoder`](https://github.com/logfellow/logstash-logback-encoder), like this:
 * ```xml
 * <!-- Example Logback config (in src/main/resources/logback.xml) -->
 * <?xml version="1.0" encoding="UTF-8"?>
 * <configuration>
 *   <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
 *     <encoder class="net.logstash.logback.encoder.LogstashEncoder">
 *       <!-- Writes object values from logging context as actual JSON (not escaped) -->
 *       <mdcEntryWriter class="no.liflig.logging.LoggingContextJsonFieldWriter"/>
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
 *   withLoggingContext(field("event", event)) {
 *     log.debug { "Started processing event" }
 *     // ...
 *     log.debug { "Finished processing event" }
 *   }
 * }
 * ```
 *
 * If you have configured `no.liflig.logging.LoggingContextJsonFieldWriter`, the field from
 * `withLoggingContext` will then be attached to every log as follows:
 * ```json
 * { "message": "Started processing event", "event": { ... } }
 * { "message": "Finished processing event", "event": { ... } }
 * ```
 */
public inline fun <ReturnT> withLoggingContext(
    logFields: List<LogField>,
    block: () -> ReturnT
): ReturnT {
  return withLoggingContextInternal(logFields.toTypedArray(), block)
}

/**
 * Shared implementation for the `vararg` and `List` versions of [withLoggingContext].
 *
 * This function must be kept internal, since [LoggingContext] assumes that the given array is not
 * modified from the outside. We uphold this invariant in both versions of [withLoggingContext]:
 * - For the `vararg` version: Varargs always give a new array to the called function, even when
 *   called with an existing array:
 *   https://discuss.kotlinlang.org/t/hidden-allocations-when-using-vararg-and-spread-operator/1640/2
 * - For the `List` version: Here we call [Collection.toTypedArray], which creates a new array.
 *
 * We could just call the `vararg` version of [withLoggingContext] from the `List` overload, since
 * you can pass an array to a function taking varargs. But this actually copies the array twice:
 * once in [Collection.toTypedArray], and again in the defensive copy that varargs make in Kotlin.
 */
@PublishedApi
internal inline fun <ReturnT> withLoggingContextInternal(
    logFields: Array<out LogField>,
    block: () -> ReturnT
): ReturnT {
  val overwrittenFields = LoggingContext.addFields(logFields)
  try {
    return block()
  } finally {
    LoggingContext.removeFields(logFields, overwrittenFields)
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
 * import no.liflig.logging.getLogger
 * import no.liflig.logging.getLoggingContext
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
 *   // This is where getLoggingContext comes in.
 *   private fun updateStatistics(order: Order) {
 *     // We call getLoggingContext here, to copy the context fields from the parent thread
 *     val loggingContext = getLoggingContext()
 *
 *     thread {
 *       // We then pass the parent context to withLoggingContext here in the child thread
 *       withLoggingContext(loggingContext) {
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
public fun getLoggingContext(): List<LogField> {
  return LoggingContext.getFieldList()
}

/**
 * Thread-local log fields that will be included on every log within a given context.
 *
 * This object encapsulates SLF4J's [MDC] (Mapped Diagnostic Context), allowing the rest of our code
 * to not concern itself with SLF4J-specific APIs.
 */
@PublishedApi
internal object LoggingContext {
  @PublishedApi
  @JvmStatic
  internal fun addFields(fields: Array<out LogField>): OverwrittenContextFields {
    var overwrittenFields = OverwrittenContextFields(null)

    for (index in fields.indices) {
      val field = fields[index]
      val keyForLoggingContext = field.getKeyForLoggingContext()

      // Skip duplicate keys in the field array
      if (isDuplicateField(field, index, fields)) {
        continue
      }

      var existingValue: String? = MDC.get(field.key)
      when (existingValue) {
        // If there is no existing entry for our key, we continue down to MDC.put
        null -> {}
        // If the existing value matches the value we're about to insert, we can skip inserting it
        field.value -> continue
        // If there is an existing entry that does not match our new field value, we add it to
        // overwrittenFields so we can restore the previous value after our withLoggingContext scope
        else -> {
          overwrittenFields = overwrittenFields.set(index, field.key, existingValue, fields.size)
          /**
           * If we get a [JsonLogField] whose key matches a non-JSON field in the context, then we
           * want to overwrite "key" with "key (json)" (adding [LOGGING_CONTEXT_JSON_KEY_SUFFIX] to
           * identify the JSON value). But since "key (json)" does not match "key", calling
           * `MDC.put` below will not overwrite the previous field, so we have to manually remove it
           * here. The previous field will then be restored by [LoggingContext.removeFields] after
           * the context exits.
           */
          if (field.key != keyForLoggingContext) {
            MDC.remove(field.key)
          }
        }
      }

      /**
       * [JsonLogField] adds a suffix to `keyForLoggingContext`, i.e. it will be different from
       * [LogField.key]. In this case, we want to check existing context field values for both `key`
       * _and_ `keyForLoggingContext`.
       */
      if (field.key != keyForLoggingContext && existingValue == null) {
        existingValue = MDC.get(keyForLoggingContext)
        when (existingValue) {
          null -> {}
          field.value -> continue
          else -> {
            overwrittenFields =
                overwrittenFields.set(index, keyForLoggingContext, existingValue, fields.size)
          }
        }
      }

      MDC.put(keyForLoggingContext, field.value)
    }

    return overwrittenFields
  }

  /**
   * Takes the array of overwritten field values returned by [LoggingContext.addFields], to restore
   * the previous context values after the current context exits.
   */
  @PublishedApi
  @JvmStatic
  internal fun removeFields(
      fields: Array<out LogField>,
      overwrittenFields: OverwrittenContextFields
  ) {
    for (index in fields.indices) {
      val field = fields[index]
      val keyForLoggingContext = field.getKeyForLoggingContext()

      // Skip duplicate keys, like we do in addFields
      if (isDuplicateField(field, index, fields)) {
        continue
      }

      val overwrittenKey = overwrittenFields.getKey(index)
      if (overwrittenKey != null) {
        MDC.put(overwrittenKey, overwrittenFields.getValue(index))
        /**
         * If the overwritten key matched the current key in the logging context, then we don't want
         * to call `MDC.remove` below (these may not always match for [JsonLogField] - see docstring
         * over `MDC.remove` in [LoggingContext.addFields]).
         */
        if (overwrittenKey == keyForLoggingContext) {
          continue
        }
      }

      MDC.remove(keyForLoggingContext)
    }
  }

  @JvmStatic
  private fun isDuplicateField(field: LogField, index: Int, fields: Array<out LogField>): Boolean {
    for (previousFieldIndex in 0 until index) {
      if (fields[previousFieldIndex].key == field.key) {
        return true
      }
    }
    return false
  }

  @JvmStatic
  internal fun contains(field: LogField): Boolean {
    val existingValue: String? = MDC.get(field.getKeyForLoggingContext())
    return existingValue == field.value
  }

  @JvmStatic
  internal fun getFieldList(): List<LogField> {
    val fieldMap = getFieldMap()
    if (fieldMap.isNullOrEmpty()) {
      return emptyList()
    }

    val fieldList = ArrayList<LogField>(getNonNullFieldCount(fieldMap))
    mapFieldMapToList(fieldMap, fieldList)
    return fieldList
  }

  @JvmStatic
  internal fun combineFieldListWithContextFields(fields: List<LogField>): List<LogField> {
    val contextFields = getFieldMap()

    // If logging context is empty, we just use the given field list, to avoid allocating an
    // additional list
    if (contextFields.isNullOrEmpty()) {
      return fields
    }

    val combinedFields = ArrayList<LogField>(fields.size + getNonNullFieldCount(contextFields))

    // Add exception log fields first, so they show first in the log output
    combinedFields.addAll(fields)
    mapFieldMapToList(contextFields, target = combinedFields)
    return combinedFields
  }

  @JvmStatic
  internal fun getFieldMap(): Map<String, String?>? {
    return MDC.getCopyOfContextMap()
  }

  @JvmStatic
  private fun mapFieldMapToList(fieldMap: Map<String, String?>, target: ArrayList<LogField>) {
    for ((key, value) in fieldMap) {
      if (value == null) {
        continue
      }

      target.add(createLogFieldFromContext(key, value))
    }
  }

  @JvmStatic
  private fun getNonNullFieldCount(fieldMap: Map<String, String?>): Int {
    return fieldMap.count { field -> field.value != null }
  }
}

/**
 * Fields (key/value pairs) that were overwritten by [LoggingContext.addFields], passed to
 * [LoggingContext.removeFields] so we can restore the previous field values after the current
 * logging context exits.
 *
 * We want this object to be as efficient as possible, since it will be kept around for the whole
 * span of a [withLoggingContext] scope, which may last a while. To support this goal, we:
 * - Use an array, to store the fields as compactly as possible
 *     - We store key/values inline, alternating - so an initialized array will look like:
 *       `key1-value1-key2-value2`
 *     - We initialize the array to twice the size of the current logging context fields, since we
 *       store 2 elements (key/value) for every field in the current context. This is not a concern,
 *       since there will typically be few elements in the context, and storing `null`s in the array
 *       does not take up much space.
 * - Initialize the array to `null`, so we don't allocate anything for the common case of there
 *   being no overwritten fields
 * - Use an inline value class, so we don't allocate a redundant wrapper object
 *     - To avoid the array being boxed, we always use this object as its concrete type. We also
 *       make the `fields` array nullable instead of the outer object, as making the outer object
 *       nullable boxes it (like `Int` and `Int?`). See
 *       [Kotlin docs](https://kotlinlang.org/docs/inline-classes.html#representation) for more on
 *       when inline value classes are boxed.
 */
@JvmInline
internal value class OverwrittenContextFields(private val fields: Array<String?>?) {
  /**
   * If the overwritten context field array has not been initialized yet, we initialize it before
   * setting the key/value, and return the new array. It is an error not to use the return value
   * (unfortunately,
   * [Kotlin can't disallow unused return values yet](https://youtrack.jetbrains.com/issue/KT-12719)).
   */
  internal fun set(
      index: Int,
      key: String,
      value: String,
      totalFields: Int
  ): OverwrittenContextFields {
    val fields = this.fields ?: arrayOfNulls(totalFields * 2)
    fields[index * 2] = key
    fields[index * 2 + 1] = value
    return OverwrittenContextFields(fields)
  }

  internal fun getKey(index: Int): String? {
    return fields?.get(index * 2)
  }

  internal fun getValue(index: Int): String? {
    return fields?.get(index * 2 + 1)
  }
}

/** Adds the given map of log fields to the logging context for the scope of the given [block]. */
internal inline fun <ReturnT> withLoggingContextMap(
    fieldMap: Map<String, String?>,
    block: () -> ReturnT
): ReturnT {
  val previousFieldMap = LoggingContext.getFieldMap()
  if (previousFieldMap != null) {
    MDC.setContextMap(previousFieldMap + fieldMap)
  } else {
    MDC.setContextMap(fieldMap)
  }

  try {
    return block()
  } finally {
    if (previousFieldMap != null) {
      MDC.setContextMap(previousFieldMap)
    } else {
      MDC.clear()
    }
  }
}

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
    val contextFields = LoggingContext.getFieldMap()

    if (contextFields.isNullOrEmpty()) {
      return callable
    }

    return Callable { withLoggingContextMap(contextFields) { callable.call() } }
  }

  private fun wrapRunnable(runnable: Runnable): Runnable {
    // Copy context fields here, to get the logging context of the parent thread.
    // We then pass this to withLoggingContext in the returned Runnable below, which will be invoked
    // in the child thread, thus inheriting the parent's context fields.
    val contextFields = LoggingContext.getFieldMap()

    if (contextFields.isNullOrEmpty()) {
      return runnable
    }

    return Runnable { withLoggingContextMap(contextFields) { runnable.run() } }
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
