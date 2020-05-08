package __foursquare_shaded__.com.fasterxml.jackson.databind

import __foursquare_shaded__.com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper

object ObjectMapperCopier {

  // Workaround since calling objectMapper.copy on "ObjectMapper with ScalaObjectMapper" fails the _checkInvalidCopy check
  def copy(objectMapper: ObjectMapper): ObjectMapper with ScalaObjectMapper = {
    new ObjectMapper(objectMapper) with ScalaObjectMapper
  }
}
