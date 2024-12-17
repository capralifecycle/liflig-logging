# liflig-logging

Logging library for Kotlin JVM, that thinly wraps SLF4J and Logback to provide a more ergonomic API,
and to use `kotlinx.serialization` for log marker serialization instead of Jackson.

**Contents:**

- [Usage](#usage)
  - [Setting up with Logback](#setting-up-with-logback)
- [Implementation](#implementation)
- [Credits](#credits)

## Usage

The `Logger` class is the entry point to `liflig-logging`'s API. You can construct a `Logger`
by providing an empty lambda, which automatically gives the logger the name of its containing class
(or file, if defined at the top level).

```kotlin
// File Example.kt
package com.example

import no.liflig.logging.Logger

// Gets the name "com.example.Example"
private val log = Logger {}
```

`Logger` provides methods for logging at various log levels (`info`, `warn`, `error`, `debug` and
`trace`). The methods take a lambda to construct the log, which is only called if the log level is
enabled (see [Implementation](#implementation) for how this is done efficiently).

```kotlin
fun example() {
  log.info { "Example message" }
}
```

You can also add _log markers_ (structured key-value data) to your logs. The `addMarker` method uses
`kotlinx.serialization` to serialize the value.

```kotlin
import kotlinx.serialization.Serializable

@Serializable data class User(val id: Long, val name: String)

fun example() {
  val user = User(id = 1, name = "John Doe")

  log.info {
    addMarker("user", user)
    "Registered new user"
  }
}
```

This will give the following log output (if outputting logs as JSON with
`logstash-logback-encoder`):

```jsonc
{ "message": "Registered new user", "user": { "id": 1, "name": "John Doe" } }
```

If you want to add markers to all logs within a scope, you can use `withLoggingContext`:

```kotlin
import no.liflig.logging.marker
import no.liflig.logging.withLoggingContext

fun processEvent(event: Event) {
  withLoggingContext(marker("event", event)) {
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

Note that `withLoggingContext` uses a thread-local to provide markers to the scope, so it won't work
with Kotlin coroutines and `suspend` functions (though it does work with Java virtual threads). An
alternative that supports coroutines may be added in a future version of the library.

Finally, you can attach a `cause` exception to logs:

```kotlin
fun example(user: User) {
  try {
    storeUser(user)
  } catch (e: Exception) {
    log.error {
      cause = e
      addMarker("user", user)
      "Failed to store user in database"
    }
  }
}
```

### Setting up with Logback

This library is designed to work with Logback and the
[`logstash-logback-encoder`](https://github.com/logfellow/logstash-logback-encoder) for JSON output.
You can configure this logger by creating a `logback.xml` file under `src/main/resources`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
  </appender>

  <root level="INFO">
    <appender-ref ref="STDOUT"/>
  </root>
</configuration>
```

See the [Usage docs](https://github.com/logfellow/logstash-logback-encoder#usage) for
`logstash-logback-encoder` for more configuration options.

## Implementation

All the methods on `Logger` are `inline`, and don't do anything if the log level is disabled - so
you only pay for marker serialization and log message concatenation if it's actually logged.

Elsewhere in the library, we use inline value classes to wrap Logback APIs, to get as close as
possible to a zero-cost abstraction.

## Credits

v2 of the library is a fork of [hermannm/devlog-kotlin](https://github.com/hermannm/devlog-kotlin),
to make maintenance and distribution by Liflig easier. Licensed under MIT:

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
which inspired the `Logger {}` syntax using a lambda to get the logger name.
[This kotlin-logging issue](https://github.com/oshai/kotlin-logging/issues/34) (by
[kosiakk](https://github.com/kosiakk)) also inspired the implementation using `inline` methods for
minimal overhead.
