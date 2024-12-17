package no.liflig.logging

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private val log = Logger {}

class LoggingContextTest {
  @Test
  fun `marker from logging context is included in log`() {
    val markers = captureLogMarkers {
      withLoggingContext(
          marker("key", "value"),
      ) {
        log.info { "Test" }
      }
    }

    markers shouldBe
        """
          "key":"value"
        """
            .trimIndent()
  }

  @Test
  fun `logging context applies to all logs in scope`() {
    val markers = arrayOfNulls<String>(2)

    withLoggingContext(
        marker("key", "value"),
    ) {
      markers[0] = captureLogMarkers { log.info { "Test" } }
      markers[1] = captureLogMarkers { log.info { "Test 2" } }
    }

    markers.forEach {
      it shouldBe
          """
            "key":"value"
          """
              .trimIndent()
    }
  }

  @Test
  fun `rawJsonMarker works with logging context`() {
    val userJson = """{"id":1,"name":"John Doe"}"""

    val markers = captureLogMarkers {
      withLoggingContext(rawJsonMarker("user", userJson)) { log.info { "Test" } }
    }

    markers shouldBe
        """
          "user":${userJson}
        """
            .trimIndent()
  }

  @Test
  fun `logging context does not apply to logs outside scope`() {
    withLoggingContext(
        marker("key", "value"),
    ) {
      log.info { "Inside scope" }
    }

    val markers = captureLogMarkers { log.info { "Outside scope" } }
    markers shouldBe ""
  }

  @Test
  fun `multiple context markers combined with log marker have expected order`() {
    val markers = captureLogMarkers {
      withLoggingContext(
          marker("contextMarker1", "value"),
          marker("contextMarker2", "value"),
      ) {
        log.info {
          addMarker("logMarker", "value")
          "Test"
        }
      }
    }

    markers shouldBe
        """
          "logMarker":"value","contextMarker1":"value","contextMarker2":"value"
        """
            .trimIndent()
  }

  @Test
  fun `duplicate context markers only includes the newest marker`() {
    var markersFromInnerContext: String? = null
    // We want to verify that after exiting the inner logging context, the markers from the outer
    // context are used again
    var markersFromOuterContext: String? = null

    withLoggingContext(
        marker("duplicateKey", "outer"),
    ) {
      withLoggingContext(
          marker("duplicateKey", "inner1"),
          marker("duplicateKey", "inner2"),
      ) {
        markersFromInnerContext = captureLogMarkers { log.info { "Test" } }
      }

      markersFromOuterContext = captureLogMarkers { log.info { "Test" } }
    }

    markersFromInnerContext shouldBe
        """
          "duplicateKey":"inner1"
        """
            .trimIndent()

    markersFromOuterContext shouldBe
        """
          "duplicateKey":"outer"
        """
            .trimIndent()
  }

  /**
   * Priority for duplicate keys in log markers is Log event -> Exception -> Context, so log event
   * marker should override context marker.
   */
  @Test
  fun `context marker does not override duplicate log event marker`() {
    val markers = captureLogMarkers {
      withLoggingContext(
          marker("duplicateKey", "from context"),
      ) {
        log.info {
          addMarker("duplicateKey", "from log event")
          "Test"
        }
      }
    }

    markers shouldBe
        """
          "duplicateKey":"from log event"
        """
            .trimIndent()
  }
}
