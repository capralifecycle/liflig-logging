package no.liflig.logging

/**
 * Exception that carries [log fields][LogField], to provide structured logging context when the
 * exception is logged. When passing an exception to one of [Logger]'s methods, it will check if the
 * given exception is an instance of this class, and if it is, these fields will be added to the
 * log.
 *
 * Use the [field]/[rawJsonField] functions to construct log fields.
 *
 * This class is useful when you are throwing an exception from somewhere down in the stack, but do
 * logging further up the stack, and you have structured data that you want to attach to the
 * exception log. In this case, one may typically resort to string concatenation, but this class
 * allows you to have the benefits of structured logging for exceptions as well.
 *
 * You can extend this class for your own custom exception types. If you'd rather implement an
 * interface than extend a class, use [HasLoggingContext].
 *
 * If you just want to attach log fields to an existing exception, without wrapping it, you can call
 * the [Throwable.withLoggingContext] extension function instead.
 *
 * ### Example
 *
 * ```
 * import no.liflig.logging.ExceptionWithLoggingContext
 * import no.liflig.logging.field
 * import no.liflig.logging.getLogger
 *
 * private val log = getLogger()
 *
 * fun example(event: OrderUpdateEvent) {
 *   try {
 *     processOrderUpdate(event)
 *   } catch (e: Exception) {
 *     log.error(e) { "Failed to process order update event" }
 *   }
 * }
 *
 * fun processOrderUpdate(event: OrderUpdateEvent) {
 *   withLoggingContext(field("event", event)) {
 *     val order = getOrder(event.orderId)
 *
 *     if (!order.canBeUpdated()) {
 *       throw ExceptionWithLoggingContext(
 *           "Received update event for finalized order",
 *           field("order", order),
 *       )
 *     }
 *   }
 * }
 * ```
 *
 * The `log.error` would then give the following log output (using `logstash-logback-encoder`), with
 * both the `order` field from the exception and the `event` field from the logging context:
 * ```
 * {
 *   "message": "Failed to process order update event",
 *   "stack_trace": "...ExceptionWithLoggingContext: Received update event for finalized order...",
 *   "order": { ... },
 *   "event": { ... },
 *   // ...timestamp etc.
 * }
 * ```
 *
 * ### Constructors
 *
 * `ExceptionWithLoggingContext` provides 4 constructor overloads:
 * - `(message: String?, logFields: Collection<LogField>, cause: Throwable?)`
 *     - Primary constructor taking an exception message, a collection of log fields and an optional
 *       cause exception
 * - `(message: String?, vararg logFields: LogField, cause: Throwable?)`
 *     - Takes log fields as varargs, so you don't have to wrap them in a list
 *     - To pass `cause`, use a named argument
 * - `(logFields: Collection<LogField>, cause: Throwable?)`
 *     - Defaults `message` to `cause.message`. This lets you:
 *         - Wrap a cause exception with log fields, and use the cause exception's message
 *         - Extend `ExceptionWithLoggingContext` and override `message`, without having to pass it
 *           through the constructor
 * - `(vararg logFields: LogField, cause: Throwable?)`
 *     - Combines the two previous constructors, to let you extend `ExceptionWithLoggingContext` and
 *       override `message` while also passing log fields as varargs
 */
public open class ExceptionWithLoggingContext : RuntimeException {
  public constructor(
      message: String?,
      logFields: Collection<LogField> = emptyList(),
      cause: Throwable? = null,
  ) : super(cause) {
    this._message = message
    this.logFields = logFields
  }

  public constructor(
      message: String?,
      vararg logFields: LogField,
      cause: Throwable? = null,
  ) : super(cause) {
    this._message = message
    this.logFields = logFields
  }

  public constructor(
      logFields: Collection<LogField> = emptyList(),
      cause: Throwable? = null,
  ) : super(cause) {
    this._message = null
    this.logFields = logFields
  }

  public constructor(
      vararg logFields: LogField,
      cause: Throwable? = null,
  ) : super(cause) {
    this._message = null
    this.logFields = logFields
  }

  /**
   * Backing field for [message]. Uses leading underscore as per
   * [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html#names-for-backing-properties).
   *
   * Marked as volatile, because if this is not set in the constructor, then we set it in
   * [buildMessageFromCauseException], and we want that to be thread-safe.
   */
  @kotlin.concurrent.Volatile private var _message: String?

  /** The exception message. */
  override val message: String?
    get() {
      val message = this._message
      if (message != null) {
        // If an exception message was provided in the constructor, return that
        return message
      } else {
        // Otherwise, use message from cause exception (if any)
        return buildMessageFromCauseException()
      }
    }

  /**
   * If no exception message was provided in the constructor, and [message] is not overridden, we
   * use the message from the cause exception (if any). We prepend the class name of the cause
   * exception (to make it clear where it comes from), on the following format:
   * ```
   * CauseExceptionClassName: Cause exception message
   * ```
   *
   * The built message is stored in [_message], so we don't have to rebuild it.
   */
  private fun buildMessageFromCauseException(): String? {
    val cause = this.cause ?: return null

    val causeClass = cause::class.simpleName
    val causeMessage = cause.message
    val message: String =
        when {
          causeClass != null && causeMessage != null -> "${causeClass}: ${causeMessage}"
          causeClass == null && causeMessage != null -> causeMessage
          causeClass != null && causeMessage == null -> causeClass
          else -> return null
        }
    this._message = message
    return message
  }

  /**
   * We want to allow users to pass log fields as a Collection or as varargs (Array). But we don't
   * want to convert one to the other, as that would involve allocations through boxing/copying. And
   * unfortunately, `Array` and `Collection` do not share a common parent class besides `Any`.
   *
   * So to avoid allocations, we type this field as `Any`, so it can be used for both `Collection`
   * and `Array`. We then check the actual type in [addFieldsToLog]. We know that this type must be
   * either `Collection<LogField>` or `Array<out LogField>` (the type for varargs), as those are the
   * only ways to pass log fields in this class's constructors.
   */
  @JvmField internal val logFields: Any

  internal fun addFieldsToLog(logBuilder: LogBuilder) {
    // Safe cast: `logFields` can only be initialized as `Collection<LogField>` or
    // `Array<out LogField>`. See comment on `logFields` for why we do this.
    @Suppress("UNCHECKED_CAST")
    when (val logFields = this.logFields) {
      is Array<*> -> {
        logBuilder.addFields(logFields as Array<out LogField>)
      }
      is Collection<*> -> {
        logBuilder.addFields(logFields as Collection<LogField>)
      }
    }

    val contextFields = this.contextFields
    if (contextFields != null) {
      logBuilder.addFields(contextFields)
    }
  }

  /**
   * Used by [withLoggingContext] to attach logging context if this exception escapes a context
   * scope.
   */
  @kotlin.concurrent.Volatile private var contextFields: Array<out LogField>? = null

  internal fun addLoggingContext(newContextFields: Array<out LogField>) {
    val existingContextFields = this.contextFields
    if (existingContextFields == null) {
      this.contextFields = newContextFields
      return
    }

    // We need to cast to `Array<LogField>` in order to use the `+` operator here (which we want to
    // use, since it uses optimized `System.arraycopy` on JVM).
    // We can safely cast from  `Array<out LogField>` to `Array<LogField>`, since `LogField` has no
    // subclasses, so this doesn't break covariance.
    @Suppress("UNCHECKED_CAST")
    this.contextFields = (existingContextFields as Array<LogField>) + newContextFields
  }
}

/**
 * Interface for exceptions that carry [log fields][LogField], to provide structured logging context
 * when an exception is logged. When passing an exception to one of [Logger]'s methods, it will
 * check if the given exception implements this interface, and if it does, these fields will be
 * added to the log.
 *
 * Use the [field]/[rawJsonField] functions to construct log fields.
 *
 * This is useful when you are throwing an exception from somewhere down in the stack, but do
 * logging further up the stack, and you have structured data that you want to attach to the
 * exception log. In this case, one may typically resort to string concatenation, but this interface
 * allows you to have the benefits of structured logging for exceptions as well.
 *
 * You can implement this interface for your custom exception types. If you just want to throw an
 * exception with logging context and don't need a custom type, throw [ExceptionWithLoggingContext].
 *
 * ### Example
 *
 * ```
 * import no.liflig.logging.HasLoggingContext
 * import no.liflig.logging.field
 * import no.liflig.logging.getLogger
 *
 * private val log = getLogger()
 *
 * fun example(order: Order) {
 *   try {
 *     updateOrder(order)
 *   } catch (e: Exception) {
 *     log.error(e) { "Failed to update order" }
 *   }
 * }
 *
 * fun updateOrder(order: Order) {
 *   if (!order.canBeUpdated()) {
 *     throw OrderUpdateException("Cannot update finalized order", order)
 *   }
 * }
 *
 * class OrderUpdateException(
 *     override val message: String,
 *     order: Order,
 * ) : RuntimeException(), HasLoggingContext {
 *   override val logFields = listOf(field("order", order))
 * }
 * ```
 *
 * The `log.error` would then give the following log output (using `logstash-logback-encoder`), with
 * the `order` log field from `OrderUpdateException` added:
 * ```
 * {
 *   "message": "Failed to update order",
 *   "stack_trace": "...OrderUpdateException: Cannot update finalized order...",
 *   "order": { ... },
 *   // ...timestamp etc.
 * }
 * ```
 */
public interface HasLoggingContext {
  /** Will be added to the log when this exception is passed to one of [Logger]'s methods. */
  public val logFields: Collection<LogField>
}

/**
 * Attaches the given [log fields][LogField] to the exception, to provide structured logging context
 * when the exception is logged. When the exception is passed to one of [Logger]'s methods, these
 * fields will be added to the log.
 *
 * Use the [field]/[rawJsonField] functions to construct log fields.
 *
 * This function returns the given exception, without wrapping it. If you _want_ to wrap your
 * exception, you should instead construct an [ExceptionWithLoggingContext], and pass your exception
 * as the `cause`.
 */
public fun <T : Throwable> T.withLoggingContext(vararg logFields: LogField): T {
  addLoggingContextToException(this, logFields)
  return this
}

/**
 * Attaches the given [log fields][LogField] to the exception, to provide structured logging context
 * when the exception is logged. When the exception is passed to one of [Logger]'s methods, these
 * fields will be added to the log.
 *
 * Use the [field]/[rawJsonField] functions to construct log fields.
 *
 * This function returns the given exception, without wrapping it. If you _want_ to wrap your
 * exception, you should instead construct an [ExceptionWithLoggingContext], and pass your exception
 * as the `cause`.
 */
public fun <T : Throwable> T.withLoggingContext(logFields: Collection<LogField>): T {
  addLoggingContextToException(this, logFields.toTypedArray())
  return this
}

/**
 * In [withLoggingContext], we want the ability to attach log fields to exceptions, so that we don't
 * lose context when an exception escapes the context scope (which is when we need it most!).
 *
 * However, exceptions don't really have a mechanism to attach arbitrary data. We could wrap
 * exceptions in a new exception type, but that would mean that the underlying exception could not
 * be caught as its original exception type. This would not be good, as we want the exception
 * handling of [withLoggingContext] to be transparent, and wrapping exceptions is intrusive.
 *
 * But exceptions do _kind of_ provide a way to attach arbitrary data: suppressed exceptions (read
 * [the `Throwable.addSuppressed` docs for more](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Throwable.html#addSuppressed(java.lang.Throwable))).
 * We can create this `LoggingContextProvider` exception with log fields attached, to "smuggle" the
 * logging context through a suppressed exception, added to the original exception. Then, when the
 * original exception is logged, we can look for our exception type among its suppressed exceptions,
 * in order to add these log fields.
 *
 * A couple issues with this approach:
 * - Exceptions are expensive to create, because they record a stack trace on construction. We don't
 *   want to pay the cost of a stack trace for our synthetic exception that only exists to carry log
 *   fields.
 *     - Solution: We can avoid this cost by overriding `Throwable.fillInStackTrace` to be a no-op.
 *       Then our exception will be just as cheap to create as any other object!
 * - Using a suppressed exception means that our `LoggingContextProvider` will show up in the logs
 *   when the exception it's added to is logged.
 *     - This is not ideal, as `LoggingContextProvider` is an internal implementation detail of the
 *       library, which we don't want to leak out to users.
 *     - Solution: When Logback is used as the SLF4J implementation, we extend Logback's
 *       `LoggingEvent` to make some special-case optimizations. Logback does not use exceptions
 *       directly, instead using an `IThrowableProxy` interface, in order to avoid reference cycles
 *       in exception cause chains. We can create our own custom implementation of this interface
 *       (see `CustomLogbackThrowableProxy` in `LogEvent.kt`), and exclude our
 *       `LoggingContextProvider` when translating `Throwable.getSuppressed` to
 *       `IThrowableProxy.getSuppressed`. Then, when the exception is logged,
 *       `LoggingContextProvider` won't show up!
 *     - Drawback: The above solution only works for Logback. For other SLF4J implementations, we
 *       use SLF4J's `DefaultLoggingEvent`, which uses `Throwable` directly. We can't override or
 *       mutate the suppressed exceptions on an existing exception, so in this case,
 *       `LoggingContextProvider` will show up in the logs. We deem this acceptable, because:
 *         - Logback is the most popular SLF4J implementation, so this will not affect many users.
 *         - Since we don't record a stack trace for `LoggingContextProvider`, it will only show up
 *           as a single line in the log: `no.liflig.logging.LoggingContextProvider: Added log
 *           fields from exception context`. Though not ideal, this at least explains to the user
 *           why it shows up, and still gives the significant benefit of providing more context to
 *           an exception.
 */
internal class LoggingContextProvider(
    @kotlin.concurrent.Volatile private var contextFields: Array<out LogField>,
) : RuntimeException() {
  /**
   * We set this to true when [addFieldsToLog] is called.
   *
   * See [getLoggingContextProviderMessage] for why we want this state.
   */
  @kotlin.concurrent.Volatile private var fieldsAddedToLog = false

  fun addLoggingContext(newContextFields: Array<out LogField>) {
    // We need to cast to `Array<LogField>` in order to use the `+` operator here (which we want to
    // use, since it uses optimized `System.arraycopy` on JVM).
    // We can safely cast from `Array<out LogField>` to `Array<LogField>`, since `LogField` has no
    // subclasses, so this doesn't break covariance.
    @Suppress("UNCHECKED_CAST")
    this.contextFields = (this.contextFields as Array<LogField>) + newContextFields
  }

  fun addFieldsToLog(logBuilder: LogBuilder) {
    logBuilder.addFields(contextFields)
    this.fieldsAddedToLog = true
  }

  // We don't want to promise that this will always be non-nullable
  @Suppress("RedundantNullableReturnType")
  override val message: String?
    get() = getLoggingContextProviderMessage(contextFields, fieldsAddedToLog)

  override fun fillInStackTrace(): Throwable {
    return this
  }

  override fun getStackTrace(): Array<out StackTraceElement> {
    return EMPTY_STACK_TRACE
  }

  override fun setStackTrace(stackTrace: Array<out StackTraceElement>) {
    return
  }
}

/**
 * `emptyArray` may allocate, so we initialize a static empty stack trace here, so it only allocates
 * once.
 */
private val EMPTY_STACK_TRACE: Array<out StackTraceElement> = emptyArray()

/**
 * Builds the exception message for [LoggingContextProvider].
 *
 * If [LoggingContextProvider.addFieldsToLog] has been called on the exception, then you should set
 * [fieldsAddedToLog] to `true`. If `addFieldsToLog` has not been called, then that may be because
 * this exception is logged by a logger outside of this library. In that case, we include the
 * logging context fields in the exception message, so we don't lose context.
 */
internal fun getLoggingContextProviderMessage(
    contextFields: Array<out LogField>,
    fieldsAddedToLog: Boolean,
): String {
  if (fieldsAddedToLog) {
    return "Added log fields from exception logging context"
  }

  val messagePrefix = "Fields from exception logging context:"

  // Pre-calculate capacity, so that StringBuilder only has to allocate once
  val capacity =
      messagePrefix.length +
          contextFields.sumOf { field ->
            // +5 chars for "\n\t\t" before each field and ": " between key and value
            field.key.length + field.value.length + 5
          }

  val message = StringBuilder(capacity)
  message.append(messagePrefix)

  for (field in contextFields) {
    // Use double tabs here, since we want the fields to be indented, and suppressed exceptions
    // (which we use for `LoggingContextProvider`) are already indented by one tab
    message.append('\n')
    message.append('\t')
    message.append('\t')
    message.append(field.key)
    message.append(':')
    message.append(' ')
    message.append(field.value)
  }

  return message.toString()
}

/**
 * Traverses the exception tree from the given root exception: Its cause exceptions and suppressed
 * exceptions, and any of their own cause or suppressed exceptions. The traversal is
 * [pre-order (depth-first)](https://en.wikipedia.org/wiki/Tree_traversal#Depth-first_search),
 * visiting the root exception first, then cause exceptions, then suppressed exceptions in order.
 *
 * The implementation tries to keep allocations to a minimum, prioritizing the happy path (see
 * [ExceptionParent] for how we do this). We also set a [MAX_EXCEPTION_TRAVERSAL_DEPTH], so we don't
 * fall victim to malicious cyclical cause exceptions.
 */
internal inline fun traverseExceptionTree(root: Throwable, action: (Throwable) -> Unit) {
  var currentException: Throwable = root
  var parent: ExceptionParent? = null
  var depth = 0

  exceptionLoop@ while (true) {
    action(currentException)

    // If we are not at the max depth, traverse child exceptions (cause + suppressed exceptions)
    if (depth < MAX_EXCEPTION_TRAVERSAL_DEPTH - 1) {
      val suppressedExceptions = getSuppressedExceptions(currentException)
      val hasSuppressedExceptions = !suppressedExceptions.isNullOrEmpty()

      // First, check if we have a cause exception. If so, set that as the next to traverse
      val causeException = currentException.cause
      if (causeException != null) {
        // We only have to set the parent if there are sibling exceptions to traverse. If we already
        // have a parent but no siblings, we can leave the parent as-is (as a "grandparent"), since
        // `ExceptionParent.depth` allows us to jump up multiple levels in the tree
        if (hasSuppressedExceptions) {
          parent =
              ExceptionParent(
                  currentException,
                  depth = depth,
                  suppressedExceptions,
                  // Since we traverse the cause exception now, the next child exception to traverse
                  // is the first suppressed exception
                  nextSuppressedExceptionIndex = 0,
                  parent,
              )
        }
        currentException = causeException
        depth++
        continue@exceptionLoop
      }

      // If there's no cause exception, look for suppressed exceptions. If we have suppressed
      // exceptions, set the first suppressed exception as the next to traverse
      if (hasSuppressedExceptions) {
        // We only have to initialize the parent if there are more suppressed exceptions to
        // traverse afterwards
        if (suppressedExceptions.size > 1) {
          parent =
              ExceptionParent(
                  currentException,
                  depth = depth,
                  suppressedExceptions,
                  // Since we traverse the first suppressed exception now, the next to traverse is
                  // the second suppressed exception
                  nextSuppressedExceptionIndex = 1,
                  parent,
              )
        }
        currentException = suppressedExceptions.first()
        depth++
        continue@exceptionLoop
      }
    }

    // If there were no child exceptions to traverse, move up to the parent to check if there are
    // sibling exceptions left further up the tree that we need to traverse
    while (parent != null) {
      val index = parent.nextSuppressedExceptionIndex
      if (index < parent.suppressedExceptions.size) {
        currentException = parent.suppressedExceptions[index]
        // The tree depth of a child exception is one deeper than its parent
        depth = parent.depth + 1
        // Increment the index for the next suppressed exception to traverse
        parent.nextSuppressedExceptionIndex = index + 1
        continue@exceptionLoop
      }

      // If we get here, there were no siblings left to traverse, so we move further up the tree
      parent = parent.parent
    }

    // If we get here, then we have traversed all cause and suppressed exceptions
    return
  }
}

/**
 * State object to allow us to move back up the exception tree in [traverseExceptionTree]. We need
 * this in order to move between sibling exceptions (cause + suppressed exceptions).
 *
 * This is essentially a singly linked list. We use this instead of pre-allocating a collection data
 * structure, so that the base case of there not being multiple children (i.e. both cause and
 * suppressed exceptions) remains allocation-free.
 */
internal class ExceptionParent(
    /** The parent exception. */
    @JvmField val exception: Throwable,
    /**
     * The depth in the exception tree we're currently traversing where this exception is located.
     */
    @JvmField val depth: Int,
    /**
     * The suppressed exceptions of [exception]. We store this here, because getting suppressed
     * exceptions potentially does an array copy, so we can avoid that allocation by keeping the
     * result here.
     *
     * This is non-null, since we only need to allocate an `ExceptionParent` if we have suppressed
     * exceptions to traverse on it.
     */
    @JvmField val suppressedExceptions: List<Throwable>,
    /**
     * The index of the next exception in [suppressedExceptions] that should be traversed.
     *
     * We include this here in order to avoid exception cycles between sibling exceptions. One can
     * imagine a malicious actor constructing an exception with both the cause and a suppressed
     * exception set to the same exception instance, which may cause an infinite loop in a naive
     * implementation of exception traversal.
     */
    @JvmField var nextSuppressedExceptionIndex: Int,
    /** The parent of this parent exception (i.e., the grandparent). */
    @JvmField val parent: ExceptionParent?,
)

/**
 * The maximum depth of child exceptions to traverse in [traverseExceptionTree]. We expect a depth
 * of 10 exceptions to cover most realistic cases of exception wrapping.
 */
private const val MAX_EXCEPTION_TRAVERSAL_DEPTH = 10

/**
 * Kotlin provides a platform-independent [Throwable.suppressedExceptions] extension function, which
 * call's Java's `Throwable.getSuppressed` method, and wraps the returned array in a list. But this
 * allocates a wrapper object for the normal case of there being no suppressed exceptions! Since we
 * check suppressed exceptions in [traverseExceptionTree], we want to avoid these redundant
 * allocations. So we write our own platform-specific function to return `null` when Java's
 * `Throwable.getSuppressed` returns an empty array.
 */
internal fun getSuppressedExceptions(exception: Throwable): List<Throwable>? {
  val suppressedExceptions: Array<Throwable> = exception.suppressed
  if (suppressedExceptions.isEmpty()) {
    return null
  } else {
    return suppressedExceptions.asList()
  }
}
