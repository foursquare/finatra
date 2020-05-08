package com.twitter.finatra.json.internal.serde

import __foursquare_shaded__.com.fasterxml.jackson.core.JsonParser
import __foursquare_shaded__.com.fasterxml.jackson.databind.{DeserializationContext, JsonDeserializer}
import com.twitter.finatra.json.internal.caseclass.exceptions.FinatraJsonMappingException
import com.twitter.util.Duration

private[finatra] object DurationStringDeserializer extends JsonDeserializer[Duration] {

  override def deserialize(
    p: JsonParser,
    ctxt: DeserializationContext
  ): Duration =
    try {
      Duration.parse(p.getValueAsString)
    } catch {
      case e: NumberFormatException =>
        throw new FinatraJsonMappingException(e.getMessage)
    }
}
