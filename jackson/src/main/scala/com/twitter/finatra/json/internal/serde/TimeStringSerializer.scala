package com.twitter.finatra.json.internal.serde

import __foursquare_shaded__.com.fasterxml.jackson.core.JsonGenerator
import __foursquare_shaded__.com.fasterxml.jackson.databind.SerializerProvider
import __foursquare_shaded__.com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.twitter.util.Time

private[finatra] object TimeStringSerializer extends StdSerializer[Time](classOf[Time]) {

  override def serialize(
    value: Time,
    jgen: JsonGenerator,
    provider: SerializerProvider
  ): Unit = {
    jgen.writeString(value.toString)
  }
}
