package no.liflig.logging

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ThrowableSerializerTest {

  @Test
  fun `should serialize`() {
    val t = try {
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
