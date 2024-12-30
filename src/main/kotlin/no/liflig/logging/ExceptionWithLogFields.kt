package no.liflig.logging

/**
 * Base exception class to allow you to attach [log fields][LogField] to exceptions. When passing a
 * `cause` exception to one of the methods on [Logger], it will check if the given exception is an
 * instance of this class, and if it is, these fields will be added to the log.
 *
 * This is useful when you are throwing an exception from somewhere down in the stack, but do
 * logging further up the stack, and you have structured data that you want to attach to the
 * exception log. In this case, one may typically resort to string concatenation, but this class
 * allows you to have the benefits of structured logging for exceptions as well.
 *
 * The exception also includes any log fields from [withLoggingContext], from the scope in which the
 * exception is constructed. If you don't want this behavior, you can create a custom exception and
 * implement the [WithLogFields] interface.
 *
 * ### Example
 *
 * ```
 * fun storeUser(user: User) {
 *   withLoggingContext(field("user", user)) {
 *     if (usernameTaken(user.name)) {
 *       throw ExceptionWithLogFields(
 *           "Invalid user data",
 *           logFields = listOf(field("reason", "Username taken")),
 *       )
 *     }
 *   }
 * }
 *
 * private val log = getLogger {}
 *
 * fun example(user: User) {
 *   try {
 *     storeUser(user)
 *   } catch (e: Exception) {
 *     log.error {
 *       cause = e
 *       "Failed to store user"
 *     }
 *   }
 * }
 * ```
 *
 * The `log.error` would then give the following log output (using `logstash-logback-encoder`), with
 * both the `reason` field from the exception and the `user` field from the logging context:
 * ```
 * {
 *   "message": "Failed to store user",
 *   "reason": "Username taken",
 *   "user": {
 *     "id": 1,
 *     "name": "John Doe"
 *   },
 *   "stack_trace": "...",
 *   // ...timestamp etc.
 * }
 * ```
 */
open class ExceptionWithLogFields(
    override val message: String?,
    logFields: List<LogField> = emptyList(),
    override val cause: Throwable? = null,
) : RuntimeException(), WithLogFields {
  // Final, since we want to ensure that fields from logging context are included
  final override val logFields: List<LogField> = combineFieldsWithLoggingContext(logFields)
}

/** Combines the given log fields with any fields from [withLoggingContext]. */
private fun combineFieldsWithLoggingContext(logFields: List<LogField>): List<LogField> {
  val contextFields = LoggingContext.getFields()

  // If logging context is empty, we just use the given field list, to avoid allocating an
  // additional list
  if (contextFields.isEmpty()) {
    return logFields
  }

  // We have to copy the fields from the logging context into a new list, because the exception may
  // escape the logging context, which would remove fields from the list that we want to include on
  // the exception
  val combinedFields = ArrayList<LogField>(logFields.size + contextFields.size)
  // Add exception log fields first, so they show first in the log output
  combinedFields.addAll(logFields)
  contextFields.forEach { field -> combinedFields.add(field) }
  return combinedFields
}

/**
 * Interface to allow you to attach [log fields][LogField] to exceptions. When passing a `cause`
 * exception to one of the methods on [Logger], it will check if the given exception implements this
 * interface, and if it does, these fields will be added to the log.
 *
 * This is useful when you are throwing an exception from somewhere down in the stack, but do
 * logging further up the stack, and you have structured data that you want to attach to the
 * exception log. In this case, one may typically resort to string concatenation, but this interface
 * allows you to have the benefits of structured logging for exceptions as well.
 *
 * If you want to include log fields from [withLoggingContext] on the exception, you should instead
 * throw or extend the [ExceptionWithLogFields] base class.
 *
 * ### Example
 *
 * ```
 * import no.liflig.logging.WithLogFields
 * import no.liflig.logging.field
 * import no.liflig.logging.getLogger
 *
 * class InvalidUserData(user: User) : RuntimeException(), WithLogFields {
 *   override val message = "Invalid user data"
 *   override val logFields = listOf(field("user", user))
 * }
 *
 * fun storeUser(user: User) {
 *   if (!user.isValid()) {
 *     throw InvalidUserData(user)
 *   }
 * }
 *
 * private val log = getLogger {}
 *
 * fun example(user: User) {
 *   try {
 *     storeUser(user)
 *   } catch (e: Exception) {
 *     log.error {
 *       cause = e
 *       "Failed to store user"
 *     }
 *   }
 * }
 * ```
 *
 * The `log.error` would then give the following log output (using `logstash-logback-encoder`), with
 * the `user` log field from `InvalidUserData` attached:
 * ```
 * {
 *   "message": "Failed to store user",
 *   "user": {
 *     "id": 1,
 *     "name": "John Doe"
 *   },
 *   "stack_trace": "...",
 *   // ...timestamp etc.
 * }
 * ```
 */
interface WithLogFields {
  /** Will be attached to the log when passed through `cause` to one of [Logger]'s methods. */
  val logFields: List<LogField>
}
