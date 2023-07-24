package no.liflig.logging.internal.json

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.UUID

internal object UUIDSerializer : KSerializer<UUID> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("UUIDSerializer", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: UUID): Unit =
    encoder.encodeString(value.toString())

  override fun deserialize(decoder: Decoder): UUID {
    return UUID.fromString(decoder.decodeString())
  }
}
