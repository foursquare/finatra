package com.twitter.finatra.json.internal.caseclass.jackson

import __foursquare_shaded__.com.fasterxml.jackson.annotation.JsonProperty
import __foursquare_shaded__.com.fasterxml.jackson.core.ObjectCodec
import __foursquare_shaded__.com.fasterxml.jackson.databind._
import __foursquare_shaded__.com.fasterxml.jackson.databind.`type`.TypeFactory
import __foursquare_shaded__.com.fasterxml.jackson.databind.annotation.JsonDeserialize
import __foursquare_shaded__.com.fasterxml.jackson.databind.node.TreeTraversingParser
import __foursquare_shaded__.com.fasterxml.jackson.databind.util.ClassUtil
import com.twitter.finatra.json.internal.caseclass.exceptions.{CaseClassValidationException, FinatraJsonMappingException}
import com.twitter.finatra.json.internal.caseclass.utils.{DefaultMethodUtils, FieldInjection}
import com.twitter.finatra.request.{FormParam, Header, QueryParam}
import com.twitter.finatra.validation.ValidationResult.Invalid
import com.twitter.finatra.validation.{ErrorCode, ValidationProvider}
import com.twitter.inject.Logging
import com.twitter.inject.conversions.string._
import com.twitter.inject.utils.AnnotationUtils
import com.twitter.inject.utils.AnnotationUtils._
import java.lang.annotation.Annotation
import java.lang.reflect.Type
import org.json4s.reflect.{ClassDescriptor, Reflector, ScalaSigReader}
import scala.annotation.tailrec
import scala.reflect.NameTransformer

private[finatra] object CaseClassField {

  def createFields(
    clazz: Class[_],
    namingStrategy: PropertyNamingStrategy,
    typeFactory: TypeFactory,
    validationProvider: ValidationProvider
  ): Seq[CaseClassField] = {
    /*
     In order to handle generics, we need to keep track of any type params defined on the case
     class and the type parameter names. We will then keep track of any passed type bindings when
     parsing and map the type parameter name to its bound JavaType, e.g. for a case class,
     `case class Page[T](data: List[T])` and given a parsing type: `mapper.parse[Page[Person]]`
     we want to be able to map the type param `T` to the `Person` JavaType.
     */
    val constructorTypes: Array[Type] =
      clazz.getConstructors.head.getGenericParameterTypes

    val clazzDescriptor = Reflector.describe(clazz).asInstanceOf[ClassDescriptor]
    // nested classes inside of other class is not supported
    if (clazz.getName.contains("$") &&
      clazzDescriptor.mostComprehensive.exists(_.name == ScalaSigReader.OuterFieldName)) {
      throw new MissingExpectedType(clazz)
    }

    val constructorParams: Seq[ConstructorParam] =
      clazzDescriptor.constructors.head.params.map { param =>
        ConstructorParam(param.name, param.argType)
      }
    assert(
      clazz.getConstructors.head.getParameterCount == constructorParams.size,
      "Non-static inner 'case classes' not supported"
    )

    // field name to list of parsed annotations
    val annotationsMap: Map[String, Seq[Annotation]] = findAnnotations(clazz, constructorParams)

    for ((constructorParam, idx) <- constructorParams.zipWithIndex) yield {
      val fieldAnnotations: Seq[Annotation] = annotationsMap.getOrElse(constructorParam.name, Nil)
      val name = jsonNameForField(fieldAnnotations, namingStrategy, constructorParam.name)
      val deserializer = deserializerOrNone(fieldAnnotations)

      CaseClassField(
        name = name,
        reflectionType = constructorTypes(idx),
        scalaType = constructorParam.scalaType,
        javaType = JacksonTypes.javaType(typeFactory, constructorParam.scalaType),
        parentClass = clazz,
        defaultFuncOpt = DefaultMethodUtils.defaultFunction(clazzDescriptor, idx),
        annotations = fieldAnnotations,
        deserializer = deserializer,
        validationProvider = validationProvider
      )
    }
  }

  /** Finds the sequence of Annotations per field in the clazz, keyed by field name */
  private[finatra] def findAnnotations(
    clazz: Class[_],
    constructorParams: Seq[ConstructorParam]
  ): Map[String, Seq[Annotation]] =
    AnnotationUtils.findAnnotations(clazz, constructorParams.map(_.name))

  private[this] def jsonNameForField(
    annotations: Seq[Annotation],
    namingStrategy: PropertyNamingStrategy,
    name: String
  ): String = {
    findAnnotation[JsonProperty](annotations) match {
      case Some(jsonProperty) if jsonProperty.value.nonEmpty =>
        jsonProperty.value
      case _ =>
        val decodedName = NameTransformer.decode(name) // decode unicode escaped field names
        namingStrategy.nameForField( // apply json naming strategy (e.g. snake_case)
          /* config = */ null,
          /* field = */ null,
          /* defaultName = */ decodedName)
    }
  }

  private[this] def deserializerOrNone(
    annotations: Seq[Annotation]
  ): Option[JsonDeserializer[Object]] = {
    for {
      jsonDeserializer <- findAnnotation[JsonDeserialize](annotations)
      if jsonDeserializer.using != classOf[JsonDeserializer.None]
    } yield
      ClassUtil.createInstance(jsonDeserializer.using, false).asInstanceOf[JsonDeserializer[Object]]
  }
}

private[finatra] case class CaseClassField(
  name: String,
  reflectionType: Type,
  scalaType: org.json4s.reflect.ScalaType,
  javaType: JavaType,
  parentClass: Class[_],
  defaultFuncOpt: Option[() => Object],
  annotations: Seq[Annotation],
  deserializer: Option[JsonDeserializer[Object]],
  validationProvider: ValidationProvider)
    extends Logging {

  private val isOption = javaType.getRawClass == classOf[Option[_]]
  private val isString = javaType.getRawClass == classOf[String]
  private val AttributeInfo(attributeType, attributeName) = findAttributeInfo(name, annotations)
  private val fieldInjection = new FieldInjection(name, javaType, parentClass, annotations)
  private val parameterizedTypeBindings: Seq[String] = reflectionType match {
    case pti: sun.reflect.generics.reflectiveObjects.ParameterizedTypeImpl =>
      pti.getActualTypeArguments.collect {
        case tvi: sun.reflect.generics.reflectiveObjects.TypeVariableImpl[_]
            if tvi.getTypeName != null =>
          tvi.getTypeName
      }
    case tvi: sun.reflect.generics.reflectiveObjects.TypeVariableImpl[_] =>
      Seq(tvi.getTypeName)
    case _ =>
      Seq.empty[String]
  }

  private lazy val firstTypeParam = javaType.containedType(0)
  private lazy val requiredFieldException = CaseClassValidationException(
    CaseClassValidationException.PropertyPath.leaf(attributeName),
    Invalid(s"$attributeType is required", ErrorCode.RequiredFieldMissing)
  )

  /* Public */

  lazy val missingValue: AnyRef = {
    if (javaType.isPrimitive)
      ClassUtil.defaultValue(javaType.getRawClass)
    else
      null
  }

  val validationAnnotations: Seq[Annotation] =
    annotations.filter(
      _.annotationType.isAnnotationPresent(validationProvider.validationAnnotation))

  /**
   * Parse the field from a JsonNode representing a JSON object
   * NOTE: I'd normally return a Try[Object], but instead I'm using exceptions to optimize the non-failure case
   * NOTE: Option fields default to None even if no default is specified
   *
   * @param context DeserializationContext for deserialization
   * @param codec Codec for field
   * @param objectJsonNode The JSON object
   * @return The parsed object for this field
   * @throws CaseClassValidationException with reason for the parsing error
   */
  def parse(
    context: DeserializationContext,
    codec: ObjectCodec,
    objectJsonNode: JsonNode,
    typeBindings: Map[String, JavaType]
  ): Object = {
    if (fieldInjection.isInjectable)
      fieldInjection
        .inject(context, codec)
        .orElse(defaultValue)
        .getOrElse(throwRequiredFieldException())
    else {
      val fieldJsonNode = objectJsonNode.get(name)
      if (fieldJsonNode != null && !fieldJsonNode.isNull)
        if (isOption)
          Option(parseFieldValue(codec, fieldJsonNode, firstTypeParam, context))
        else
          assertNotNull(
            fieldJsonNode,
            parseFieldValue(
              codec,
              fieldJsonNode,
              if (parameterizedTypeBindings.nonEmpty)
                JacksonTypes.javaType(
                  context.getTypeFactory,
                  this.scalaType,
                  parameterizedTypeBindings,
                  typeBindings)
              else javaType,
              context
            )
          )
      else if (defaultFuncOpt.isDefined)
        defaultFuncOpt.get.apply()
      else if (isOption)
        None
      else
        throwRequiredFieldException()
    }
  }

  /* Private */

//  private[this]

  //optimized
  private[this] def parseFieldValue(
    fieldCodec: ObjectCodec,
    field: JsonNode,
    fieldType: JavaType,
    context: DeserializationContext
  ): Object = {
    if (isString) {
      field.asText()
    } else {
      val treeTraversingParser = new TreeTraversingParser(field, fieldCodec)
      if (deserializer.isDefined) {
        deserializer.get.deserialize(treeTraversingParser, context)
      } else {
        fieldCodec.readValue[Object](treeTraversingParser, fieldType)
      }
    }
  }

  //optimized
  private[this] def assertNotNull(field: JsonNode, value: Object): Object = {
    value match {
      case null => throw new FinatraJsonMappingException("error parsing '" + field.asText + "'")
      case traversable: Traversable[_] => assertNotNull(traversable)
      case array: Array[_] => assertNotNull(array)
      case _ => // no-op
    }
    value
  }

  private def assertNotNull(traversable: Traversable[_]): Unit = {
    if (traversable.exists(_ == null)) {
      throw new FinatraJsonMappingException(
        "Literal null values are not allowed as json array elements."
      )
    }
  }

  private def defaultValue: Option[Object] = {
    if (defaultFuncOpt.isDefined)
      defaultFuncOpt map { _() } else if (isOption)
      Some(None)
    else
      None
  }

  private def throwRequiredFieldException() = {
    throw requiredFieldException
  }

  private case class AttributeInfo(`type`: String, fieldName: String)

  @tailrec
  private def findAttributeInfo(fieldName: String, annotations: Seq[Annotation]): AttributeInfo = {
    if (annotations.isEmpty) {
      AttributeInfo("field", fieldName)
    } else {
      val found = extractAttributeInfo(fieldName, annotations.head)
      if (found.isDefined) {
        found.get
      } else {
        findAttributeInfo(fieldName, annotations.tail)
      }
    }
  }

  private def extractAttributeInfo(
    fieldName: String,
    annotation: Annotation
  ): Option[AttributeInfo] = annotation match {
    case queryParam: QueryParam =>
      Some(AttributeInfo("queryParam", queryParam.value.getOrElse(fieldName)))
    case formParam: FormParam =>
      Some(AttributeInfo("formParam", formParam.value.getOrElse(fieldName)))
    case header: Header =>
      Some(AttributeInfo("header", header.value.getOrElse(fieldName)))
    case _ =>
      None
  }
}
