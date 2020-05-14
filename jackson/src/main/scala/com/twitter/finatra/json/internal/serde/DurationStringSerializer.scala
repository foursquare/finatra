package com.twitter.finatra.json.internal.serde

import __foursquare_shaded__.com.fasterxml.jackson.core.JsonGenerator
import __foursquare_shaded__.com.fasterxml.jackson.databind.SerializerProvider
import __foursquare_shaded__.com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.twitter.util.Duration

private[finatra] object DurationStringSerializer
    extends StdSerializer[Duration](classOf[Duration]) {

  override def serialize(
    value: Duration,
    jgen: JsonGenerator,
    provider: SerializerProvider
  ): Unit = {
    jgen.writeString(value.toString)
  }
}
