# liflig-logging

Structured logging library for Kotlin, that aims to provide a developer-friendly API with near-zero
runtime overhead. Wraps SLF4J and Logback on the JVM.

v2 of the library is a fork of
[`hermannm/devlog-kotlin`](https://github.com/hermannm/devlog-kotlin) (licensed under
[MIT](#credits)), to make maintenance and distribution by Liflig easier.

**Contents:**

- [Usage](#usage)
- [Adding to your project](#adding-to-your-project)
- [Implementation](#implementation)
  - [Performance](#performance)
  - [Automatic logger names](#automatic-logger-names)
- [Why another logging library?](#why-another-logging-library)
- [Credits](#credits)

## Usage

The `Logger` class is the entry point to `liflig-logging`'s API. You can get a `Logger` by calling
`getLogger()`, which automatically gives the logger the name of its containing class (or file, if
defined at the top level). See [Implementation](#automatic-logger-names) below for how this works.

```kotlin
// File Example.kt
package com.example

import no.liflig.logging.getLogger

// Gets the name "com.example.Example"
private val log = getLogger()
```

`Logger` provides methods for logging at various log levels (`info`, `warn`, `error`, `debug` and
`trace`). The methods take a lambda to construct the log, which is only called if the log level is
enabled (see [Implementation](#implementation) for how this is done efficiently).

```kotlin
fun example() {
  log.info { "Example message" }
}
```

You can also add _fields_ (structured key-value data) to your logs, by calling the `field` method in
the scope of a log lambda. It uses `kotlinx.serialization` to serialize the value.

```kotlin
import kotlinx.serialization.Serializable

@Serializable
data class Event(val id: Long, val type: String)

fun example() {
  val event = Event(id = 1001, type = "ORDER_UPDATED")

  log.info {
    field("event", event)
    "Processing event"
  }
}
```

When outputting logs as JSON, the key/value given to `field` is added to the logged JSON object (see
below). This allows you to filter and query on the field in the log analysis tool of your choice, in
a more structured manner than if you were to just use string concatenation.

<!-- prettier-ignore -->

```jsonc
{
  "message": "Processing event",
  "event": {
    "id": 1001,
    "type": "ORDER_UPDATED"
  },
  // ...timestamp etc.
}
```

If you want to add fields to all logs within a scope, you can use `withLoggingContext`:

```kotlin
import no.liflig.logging.field
import no.liflig.logging.withLoggingContext

fun processEvent(event: Event) {
  withLoggingContext(field("event", event)) {
    log.debug { "Started processing event" }
    // ...
    log.debug { "Finished processing event" }
  }
}
```

...giving the following output:

```jsonc
{ "message": "Started processing event", "event": { /* ... */ } }
{ "message": "Finished processing event", "event": { /* ... */ } }
```

Note that `withLoggingContext` uses a thread-local
([SLF4J's `MDC`](https://logback.qos.ch/manual/mdc.html)) to provide log fields to the scope, so it
won't work with Kotlin coroutines and `suspend` functions. If you use coroutines, you can solve this
with
[`MDCContext` from
`kotlinx-coroutines-slf4j`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-slf4j/kotlinx.coroutines.slf4j/-m-d-c-context/).

Lastly, you can attach a cause exception to the log like this:

```kotlin
fun example() {
  try {
    callExternalService()
  } catch (e: Exception) {
    log.error(e) { "Request to external service failed" }
  }
}
```

## Adding to your project

Like SLF4J, `liflig-logging` only provides a logging _API_, and you have to add a logging
_implementation_ to actually output logs. Any SLF4J logger implementation will work, but the
library is specially optimized for Logback.

To set up `liflig-logging` with
[Logback](https://mvnrepository.com/artifact/ch.qos.logback/logback-classic) and
[
`logstash-logback-encoder`](https://mvnrepository.com/artifact/net.logstash.logback/logstash-logback-encoder)
for JSON output, add the following dependencies:

- **Maven:**
  ```xml
  <dependencies>
    <!-- Logger API -->
    <dependency>
      <groupId>no.liflig</groupId>
      <artifactId>liflig-logging</artifactId>
      <version>${liflig-logging.version}</version>
    </dependency>
    <!-- Logger implementation -->
    <dependency>
      <groupId>ch.qos.logback</groupId>
      <artifactId>logback-classic</artifactId>
      <version>${logback.version}</version>
      <scope>runtime</scope>
    </dependency>
    <!-- JSON encoding of logs -->
    <dependency>
      <groupId>net.logstash.logback</groupId>
      <artifactId>logstash-logback-encoder</artifactId>
      <version>${logstash-logback-encoder.version}</version>
      <scope>runtime</scope>
    </dependency>
  </dependencies>
  ```

Then, configure Logback with a `logback.xml` file under `src/main/resources`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
      <!-- Writes object values from logging context as actual JSON (not escaped) -->
      <mdcEntryWriter class="no.liflig.logging.LoggingContextJsonFieldWriter"/>
    </encoder>
  </appender>

  <root level="INFO">
    <appender-ref ref="STDOUT"/>
  </root>
</configuration>
```

For more configuration options, see:

- [The Configuration chapter of the Logback manual](https://logback.qos.ch/manual/configuration.html)
- [The Usage docs for
  `logstash-logback-encoder`](https://github.com/logfellow/logstash-logback-encoder#usage)

## Implementation

### Performance

- All the methods on `Logger` take a lambda to build the log, which is only called if the log level
  is enabled - so you only pay for message string concatenation and log field serialization if it's
  actually logged.
- `Logger`'s methods are also `inline`, so we avoid the cost of allocating a function object for the
  lambda parameter.
- Elsewhere in the library, we use inline value classes when wrapping SLF4J/Logback APIs, to get as
  close as possible to a zero-cost abstraction.

### Automatic logger names

`getLogger()` calls `MethodHandles.lookup().lookupClass()`, which returns the calling class. Since
`getLogger` is inline, that will actually return the class that called `getLogger`, so we can use it
to get the name of the caller. When called at file scope, the calling class will be the synthetic
`Kt` class that Kotlin generates for the file, so we can use the file name in that case.

This is the pattern that
[the SLF4J docs recommends](https://www.slf4j.org/faq.html#declaration_pattern) for getting loggers
for a class in a generic manner.

## Why another logging library?

The inspiration for this library mostly came from some inconveniencies and limitations that we've
experienced with the [`kotlin-logging`](https://github.com/oshai/kotlin-logging) library (it's a
great library, these are just my subjective opinions!). Here are some of the things we wanted to
improve with this library:

- **Structured logging**
  - In `kotlin-logging`, going from a log _without_ structured log fields to a log _with_  them
    requires you to switch your logger method (`info` -> `atInfo`), use a different syntax
    (`message = ` instead of returning a string), and construct a map for the fields.
  - Having to switch syntax becomes a barrier for developers to do structured logging. In my
    experience, the key to making structured logging work in practice is to reduce such barriers.
  - So in `liflig-logging`, we wanted to make this easier: you use the same logger methods whether
    you
    are adding fields or not, and adding structured data to an existing log is as simple as just
    calling `field` in the scope of the log lambda.
- **Using `kotlinx.serialization` for log field serialization**
  - `kotlin-logging` also wraps SLF4J in the JVM implementation. It passes structured log fields as
    `Map<String, Any?>`, and leaves it to the logger backend to serialize them. Since most SLF4J
    logger implementations are Java-based, they typically use Jackson to serialize these fields (if
    they support structured logging at all).
  - But in Kotlin, we often use `kotlinx.serialization` instead of Jackson. There can be subtle
    differences between how Jackson and `kotlinx` serialize objects, so we would prefer to use
    `kotlinx` for our log fields, so that they serialize in the same way as in the rest of our
    application.
  - In `liflig-logging`, we solve this by serializing log fields _before_ sending them to the logger
    backend, which allows us to control the serialization process with `kotlinx.serialization`.
  - Controlling the serialization process also lets us handle failures better. One of the issues
    I've experienced with Jackson serialization of log fields, is that `logstash-logback-encoder`
    would drop an entire log line in some cases when one of the custom fields on that log failed
    to serialize. `liflig-logging` never drops logs on serialization failures, instead defaulting to
    `toString()`.
- **Inline logger methods**
  - One of the classic challenges for a logging library is how to handle calls to a logger method
    when the log level is disabled. We want this to have as little overhead as possible, so that
    we don't pay a runtime cost for a log that won't actually produce any output.
  - In Kotlin, we have the opportunity to create such zero-cost abstractions, using `inline`
    functions with lambda parameters. This lets us implement logger methods that compile down to a
    simple `if` statement to check if the log level is enabled, and that do no work if the level is
    disabled. Great!
  - However, `kotlin-logging` does not use inline logger methods. This is partly because of how the
    library is structured: `KLogger` is an interface, with different implementations for various
    platforms - and interfaces can't have inline methods. So the methods that take lambdas won't be
    inlined, which means that they may allocate function objects, which are not zero-cost.
    [This `kotlin-logging` issue](https://github.com/oshai/kotlin-logging/issues/34) discusses some
    of the performance implications.
  - `liflig-logging` solves this by dividing up the problem: we make our `Logger` a concrete class,
    which wraps SLF4J's logger interface. `Logger` provides the public API, and since it's a single
    concrete class, we can make its methods `inline`. We also make it a `value class`, so that it
    compiles down to just the underlying SLF4J logger at runtime. This makes the abstraction as
    close to zero-cost as possible.
  - One notable drawback of inline methods is that they don't work well with line numbers (i.e.,
    getting file location information inside an inlined lambda will show an incorrect line number).
    We deem this a worthy tradeoff for performance, because the class/file name + the log message is
    typically enough to find the source of a log. Also, `logstash-logback-encoder`
    [explicitly discourages enabling file locations](https://github.com/logfellow/logstash-logback-encoder/tree/logstash-logback-encoder-8.1#caller-info-fields),
    due to the runtime cost. Still, this is something to be aware of if you want line numbers
    included in your logs. This limitation is documented on all the methods on `Logger`.
- **Supporting arbitrary types for logging context values**
  - SLF4J's `MDC` has a limitation: values must be `String`. And the `withLoggingContext` function
    from `kotlin-logging`, which uses `MDC`, inherits this limitation.
  - But when doing structured logging, it can be useful to attach more than just strings in the
    logging context - for example, attaching the JSON of an event in the scope that it's being
    processed. If you pass serialized JSON to `MDC`, the resulting log output will include the JSON
    as an escaped string. This defeats the purpose, as an escaped string will not be parsed
    automatically by log analysis platforms - what we want is to include actual, unescaped JSON in
    the logging context, so that we can filter and query on its fields.
  - `liflig-logging` solves this limitation by instead taking a `LogField` type, which can have an
    arbitrary serializable value, as the parameter to our `withLoggingContext` function. We then
    provide `LoggingContextJsonFieldWriter` for interoperability with `MDC` when using Logback +
    `logstash-logback-encoder`.

## Credits

v2 of the library is a fork of
[`hermannm/devlog-kotlin`](https://github.com/hermannm/devlog-kotlin), to make maintenance and
distribution by Liflig easier. `devlog-kotlin` is licensed under MIT:

```
MIT License

Copyright (c) 2024 Hermann Mørkrid <https://hermannm.dev>

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

Credits to the [`kotlin-logging` library by Ohad Shai](https://github.com/oshai/kotlin-logging)
(licensed under
[Apache 2.0](https://github.com/oshai/kotlin-logging/blob/c91fe6ab71b9d3470fae71fb28c453006de4e584/LICENSE)),
which was a great inspiration for this library.

Also credits to [kosiakk](https://github.com/kosiakk) for
[this `kotlin-logging` issue](https://github.com/oshai/kotlin-logging/issues/34), which inspired the
implementation using `inline` methods for minimal overhead.
