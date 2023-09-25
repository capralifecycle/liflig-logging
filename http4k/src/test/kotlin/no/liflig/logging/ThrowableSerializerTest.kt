package no.liflig.logging

import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.liflig.logging.internal.json.ThrowableSerializer
import org.junit.jupiter.api.Test

class ThrowableSerializerTest {

  @Test
  fun `should serialize`() {
    val t =
        try {
          throw RuntimeException("inner")
        } catch (e: Exception) {
          RuntimeException("outer", e)
        }

    val s = Json.encodeToString(ThrowableSerializer, t)

    val obj = Json.parseToJsonElement(s).jsonObject
    assertEquals("java.lang.RuntimeException: outer", obj["value"]!!.jsonPrimitive.content)

    assertEquals(
        "java.lang.RuntimeException: inner",
        obj["cause"]!!.jsonObject["value"]!!.jsonPrimitive.content,
    )

    assertEquals(
        "ThrowableSerializerTest.kt",
        obj["stackTrace"]!!.jsonArray[0].jsonObject["fileName"]!!.jsonPrimitive.content,
    )

    assertEquals(
        listOf("cause", "stackTrace", "suppressed", "value"),
        obj.keys.sorted(),
    )
  }
}
