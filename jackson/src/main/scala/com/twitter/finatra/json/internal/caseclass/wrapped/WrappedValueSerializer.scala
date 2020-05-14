package com.twitter.finatra.json.internal.caseclass.wrapped

import __foursquare_shaded__.com.fasterxml.jackson.core.JsonGenerator
import __foursquare_shaded__.com.fasterxml.jackson.databind.SerializerProvider
import __foursquare_shaded__.com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.twitter.inject.domain.WrappedValue

private[finatra] object WrappedValueSerializer
    extends StdSerializer[WrappedValue[_]](classOf[WrappedValue[_]]) {

  override def serialize(
    wrappedValue: WrappedValue[_],
    jgen: JsonGenerator,
    provider: SerializerProvider
  ): Unit = {
    jgen.writeObject(wrappedValue.onlyValue)
  }
}
