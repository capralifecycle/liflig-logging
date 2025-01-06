# liflig-logging

Logging library for Kotlin JVM, that thinly wraps SLF4J and Logback to provide a more ergonomic API.

**Contents:**

- [Usage](#usage)
- [Adding to your project](#adding-to-your-project)
- [Implementation](#implementation)
- [Credits](#credits)

## Usage

The `Logger` class is the entry point to `liflig-logging`'s API. You can get a `Logger` by calling
`getLogger`, with an empty lambda to automatically give the logger the name of its containing class
(or file, if defined at the top level).

```kotlin
// File Example.kt
package com.example

import no.liflig.logging.getLogger

// Gets the name "com.example.Example"
private val log = getLogger {}
```

`Logger` provides methods for logging at various log levels (`info`, `warn`, `error`, `debug` and
`trace`). The methods take a lambda to construct the log, which is only called if the log level is
enabled (see [Implementation](#implementation) for how this is done efficiently).

```kotlin
fun example() {
  log.info { "Example message" }
}
```

You can also add _fields_ (structured key-value data) to your logs. The `field` method uses
`kotlinx.serialization` to serialize the value.

```kotlin
import kotlinx.serialization.Serializable

@Serializable
data class User(val id: Long, val name: String)

fun example() {
  val user = User(id = 1, name = "John Doe")

  log.info {
    field("user", user)
    "Registered new user"
  }
}
```

When outputting logs as JSON, the key/value given to `field` is added to the logged JSON object (see
below). This allows you to filter and query on the field in the log analysis tool of your choice, in
a more structured manner than if you were to just use string concatenation.

<!-- prettier-ignore -->
```jsonc
{
  "message": "Registered new user",
  "user": {
    "id": 1,
    "name": "John Doe"
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

Note that `withLoggingContext` uses a thread-local to provide log fields to the scope, so it won't
work with Kotlin coroutines and `suspend` functions (though it does work with Java virtual threads).
An alternative that supports coroutines may be added in a future version of the library.

Finally, you can attach a `cause` exception to logs:

```kotlin
fun example() {
  try {
    callExternalService()
  } catch (e: Exception) {
    log.error {
      cause = e
      "Request to external service failed"
    }
  }
}
```

## Adding to your project

Like SLF4J, `liflig-logging` only provides a logging _API_, and you have to add a logging
_implementation_ to actually output logs. Any SLF4J logger implementation will work, but the
library is specially optimized for Logback.

To set up `liflig-logging` with Logback and JSON output, add the following dependencies:

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
    </dependency>
    <!-- JSON encoding of logs -->
    <dependency>
      <groupId>net.logstash.logback</groupId>
      <artifactId>logstash-logback-encoder</artifactId>
      <version>${logstash-logback-encoder.version}</version>
    </dependency>
  </dependencies>
  ```

Then, configure Logback with a `logback.xml` file under `src/main/resources`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
      <!-- Writes object values in logging context as actual JSON (not escaped) -->
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
- [The Usage docs of `logstash-logback-encoder`](https://github.com/logfellow/logstash-logback-encoder#usage)
  (the library to use for JSON encoding of logs)

## Implementation

- All the methods on `Logger` take a lambda argument to build the log, which is only called if the
  log level is enabled - so you only pay for log field serialization and message concatenation if
  it's actually logged.
- `Logger`'s methods are also `inline`, so we avoid the cost of allocating a function object for the
  lambda argument.
- Elsewhere in the library, we use inline value classes when wrapping Logback APIs, to get as close
  as possible to a zero-cost abstraction.

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

Credits also go to the
[kotlin-logging library by Ohad Shai](https://github.com/oshai/kotlin-logging) (licensed under
[Apache 2.0](https://github.com/oshai/kotlin-logging/blob/c91fe6ab71b9d3470fae71fb28c453006de4e584/LICENSE)),
which inspired the `getLogger {}` syntax using a lambda to get the logger name.
[This kotlin-logging issue](https://github.com/oshai/kotlin-logging/issues/34) (by
[kosiakk](https://github.com/kosiakk)) also inspired the implementation using `inline` methods for
minimal overhead.
