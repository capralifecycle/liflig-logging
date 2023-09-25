# liflig-logging

Kotlin library to consistently produce HTTP logs from Liflig-services.

This library is currently only distributed in Liflig
internal repositories.

## Contributing

This project follows
https://confluence.capraconsulting.no/x/fckBC

To check build before pushing:

```bash
mvn verify
```

The CI server will automatically release new version for builds on master.

## Linting

This project uses ktfmt along with spotless to lint the kotlin code.

Only check lint: `mvn spotless:check`

Fix: `mvn spotless:apply`

For IntelliJ you may use the "ktfmt"-plugin in order to consolidate with its "reformat"-tool:
https://plugins.jetbrains.com/plugin/14912-ktfmt

## Using the library

Requirements:

- Using Logback for logging with SLF4J
- Using http4k
- Kotlin with Kotlinx Serialization

For convenience, this library also contains some filters for basic
error handling.

### Example logback.xml

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

### Adding the filters to an application

Create a class that extends `no.liflig.logging.PrincipalLog` that
can be used to hold details about the principal:

```kotlin
@Serializable
data class MyPrincipalLog(
  val id: String,
) : PrincipalLog
```

Add filters to chain:

```kotlin
val contexts = RequestContexts()

val requestIdChainLens = createRequestIdChainLens(contexts)
val errorLogLens = createErrorLogLens(contexts)
val normalizedStatusLens = createNormalizedStatusLens(contexts)

val errorResponseRenderer = ErrorResponseRendererWithLogging(
  errorLogLens,
  normalizedStatusLens,
  JsonErrorResponseRenderer(Jackson),
)

val principalLog = { request: Request ->
  // Hook into however you store the principal and
  // map it to an instance of your MyPrincipalLog.
  principalLens(request)?.toLog()
}

val filters = ServerFilters
  .InitialiseRequestContext(contexts)
  .then(RequestIdMdcFilter(requestIdChainLens))
  .then(CatchAllExceptionFilter())
  .then(
    LoggingFilter(
      principalLog,
      errorLogLens,
      normalizedStatusLens,
      requestIdChainLens,
      LoggingFilter.createLogHandler(
        printStacktraceToConsole = false,
        principalLogSerializer = MyPrincipalLog.serializer(),
      ),
    ),
  )
  // <-- CORS filter here
  .then(ErrorHandlerFilter(errorLogLens))
  .then(RequestLensFailureFilter(errorResponseRenderer))
  // Rest of your filters/handlers.
```

Requests will now be logged as JSON to stdout. See `Http4kTest.kt` for
a full demo, including correct setup for using http4k contracts that
requires more configuration to log lens errors.

Example log line:

```json
{
  "@timestamp": "2021-04-26T04:06:07.558+02:00",
  "@version": "1",
  "message": "HTTP request (200) (18 ms): GET /",
  "logger_name": "no.liflig.logging.http4k.LoggingFilter",
  "thread_name": "main",
  "level": "INFO",
  "level_value": 20000,
  "requestIdChain": "41687ab9-c76e-45de-bccc-eebf9683f695",
  "requestInfo": {
    "timestamp": "2021-04-26T02:06:07.430307Z",
    "requestId": "41687ab9-c76e-45de-bccc-eebf9683f695",
    "requestIdChain": ["41687ab9-c76e-45de-bccc-eebf9683f695"],
    "request": {
      "timestamp": "2021-04-26T02:06:07.409573Z",
      "method": "GET",
      "uri": "/",
      "headers": [
        {
          "name": "x-http4k-context",
          "value": "41687ab9-c76e-45de-bccc-eebf9683f695"
        }
      ],
      "size": 0,
      "body": ""
    },
    "response": {
      "timestamp": "2021-04-26T02:06:07.427743Z",
      "statusCode": 200,
      "headers": [],
      "size": 0,
      "body": ""
    },
    "principal": { "id": "dummy-principal" },
    "durationMs": 18,
    "throwable": null,
    "status": { "code": "OK" },
    "thread": "main"
  }
}
```
