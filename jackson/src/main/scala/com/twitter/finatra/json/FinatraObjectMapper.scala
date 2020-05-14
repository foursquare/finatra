package com.twitter.finatra.json

import __foursquare_shaded__.com.fasterxml.jackson.core.JsonParser
import __foursquare_shaded__.com.fasterxml.jackson.databind._
import __foursquare_shaded__.com.fasterxml.jackson.databind.util.ByteBufferBackedInputStream
import __foursquare_shaded__.com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.google.inject.Injector
import com.twitter.finatra.json.internal.serde.ArrayElementsOnNewLinesPrettyPrinter
import com.twitter.finatra.json.modules.FinatraJacksonModule
import com.twitter.io.Buf
import java.io.{ByteArrayOutputStream, InputStream, OutputStream}
import java.nio.ByteBuffer

object FinatraObjectMapper {

  /**
   * When not using injection, this factory method can be used but be aware that the @JsonInject annotation will not work.
   * NOTE: The preferred way of obtaining a FinatraObjectMapper is through injection using FinatraJacksonModule.
   */
  def create(injector: Injector = null): FinatraObjectMapper = {
    val jacksonModule = new FinatraJacksonModule()
    new FinatraObjectMapper(jacksonModule.provideScalaObjectMapper(injector))
  }
}

case class FinatraObjectMapper(objectMapper: ObjectMapper with ScalaObjectMapper) {

  lazy val prettyObjectMapper: ObjectWriter = {
    objectMapper.writer(ArrayElementsOnNewLinesPrettyPrinter)
  }

  def propertyNamingStrategy: PropertyNamingStrategy = {
    objectMapper.getPropertyNamingStrategy
  }

  def reader[T: Manifest]: ObjectReader = {
    objectMapper.readerFor[T]
  }

  def parse[T: Manifest](byteBuffer: ByteBuffer): T = {
    val is = new ByteBufferBackedInputStream(byteBuffer)
    objectMapper.readValue[T](is)
  }

  def parse[T: Manifest](buf: Buf): T = {
    parse[T](Buf.ByteBuffer.Shared.extract(buf))
  }

  def parse[T: Manifest](jsonNode: JsonNode): T = {
    convert[T](jsonNode)
  }

  /** Parse InputStream (caller must close) */
  def parse[T: Manifest](inputStream: InputStream): T = {
    objectMapper.readValue[T](inputStream)
  }

  def parse[T: Manifest](bytes: Array[Byte]): T = {
    objectMapper.readValue[T](bytes)
  }

  def parse[T: Manifest](string: String): T = {
    objectMapper.readValue[T](string)
  }

  def parse[T: Manifest](jsonParser: JsonParser): T = {
    objectMapper.readValue[T](jsonParser)
  }

  /*
   When Finatra's CaseClassMappingException is thrown inside 'convertValue', newer versions of Jackson wrap
   CaseClassMappingException inside an IllegalArgumentException. As such, we unwrap to restore the original exception here.

   Details:
   See https://github.com/FasterXML/jackson-databind/blob/2.4/src/main/java/com/fasterxml/jackson/databind/ObjectMapper.java#L2773
   The wrapping occurs because CaseClassMappingException is an IOException (because we extend JsonMappingException which extends JsonProcessingException which extends IOException).
   We must extend JsonMappingException otherwise CaseClassMappingException is not properly handled when deserializing into nested case-classes
   */
  def convert[T: Manifest](any: Any): T = {
    try {
      objectMapper.convertValue[T](any)
    } catch {
      case e: IllegalArgumentException if e.getCause != null =>
        throw e.getCause
    }
  }

  /* See scaladoc from convert above */
  def convert(from: Any, toValueType: JavaType): AnyRef = {
    try {
      objectMapper.convertValue(from, toValueType)
    } catch {
      case e: IllegalArgumentException if e.getCause != null =>
        throw e.getCause
    }
  }

  def writeValueAsBytes(any: Any): Array[Byte] = {
    objectMapper.writeValueAsBytes(any)
  }

  def writeValue(any: Any, outputStream: OutputStream): Unit = {
    objectMapper.writeValue(outputStream, any)
  }

  def writeValueAsString(any: Any): String = {
    objectMapper.writeValueAsString(any)
  }

  def writePrettyString(any: Any): String = any match {
    case str: String =>
      val jsonNode = objectMapper.readValue[JsonNode](str)
      prettyObjectMapper.writeValueAsString(jsonNode)
    case _ =>
      prettyObjectMapper.writeValueAsString(any)
  }

  def writeValueAsBuf(any: Any): Buf = {
    Buf.ByteArray.Owned(objectMapper.writeValueAsBytes(any))
  }

  // optimized
  def writeStringMapAsBuf(stringMap: Map[String, String]): Buf = {
    val os = new ByteArrayOutputStream()

    val jsonGenerator = objectMapper.getFactory.createGenerator(os)
    try {
      jsonGenerator.writeStartObject()
      for ((key, value) <- stringMap) {
        jsonGenerator.writeStringField(key, value)
      }
      jsonGenerator.writeEndObject()
      jsonGenerator.flush()

      Buf.ByteArray.Owned(os.toByteArray)
    } finally {
      jsonGenerator.close()
    }
  }

  def registerModule(module: Module): ObjectMapper = {
    objectMapper.registerModule(module)
  }
}
