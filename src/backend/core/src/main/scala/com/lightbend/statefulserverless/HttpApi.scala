/*
 * Copyright 2019 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lightbend.statefulserverless

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Failure}
import akka.ConfigurationException
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, HttpEntity, ContentTypes, HttpMethod, HttpMethods, RequestEntityAcceptance, IllegalRequestException, StatusCodes}
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.actor.{ActorRef, ActorSystem}
import akka.event.{Logging, LogSource}
import akka.util.{ByteString, Timeout}
import akka.pattern.ask
import akka.stream.Materializer
import akka.parboiled2.util.Base64
import com.google.api.{AnnotationsProto, CustomHttpPattern, HttpRule, HttpProto}
import com.google.protobuf.{DescriptorProtos, DynamicMessage, ByteString => ProtobufByteString, MessageOrBuilder}
import com.google.protobuf.Descriptors.{Descriptor, EnumValueDescriptor, FieldDescriptor, FileDescriptor, MethodDescriptor, ServiceDescriptor}
import com.google.protobuf.{descriptor => ScalaPBDescriptorProtos}
import com.google.protobuf.any.{Any => ProtobufAny}
import com.google.protobuf.util.JsonFormat
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType
import com.lightbend.statefulserverless.grpc._
import java.lang.{Short => JShort, Integer => JInteger, Long => JLong, Boolean => JBoolean, Float => JFloat, Double => JDouble}
import com.google.protobuf.{Value, ListValue, EnumValue, Struct}

// References:
// https://cloud.google.com/endpoints/docs/grpc-service-config/reference/rpc/google.api#httprule
// https://github.com/googleapis/googleapis/blob/master/google/api/http.proto
// https://github.com/googleapis/googleapis/blob/master/google/api/annotations.proto
object HttpApi {
  final val ParseShort: String => Option[JShort] =
    s => try Option(JShort.valueOf(s)) catch { case _: NumberFormatException => None }

  final val ParseInt: String => Option[JInteger] =
    s => try Option(JInteger.valueOf(s)) catch { case _: NumberFormatException => None }

  final val ParseLong: String => Option[JLong] =
    s => try Option(JLong.valueOf(s)) catch { case _: NumberFormatException => None }

  final val ParseFloat: String => Option[JFloat] =
    s => try Option(JFloat.valueOf(s)) catch { case _: NumberFormatException => None }

  final val ParseDouble: String => Option[JDouble] =
    s => try Option(JDouble.valueOf(s)) catch { case _: NumberFormatException => None }

  final val ParseString: String => Option[String] =
    s => Option(s)

  final val ParseBoolean: String => Option[JBoolean] =
    _.toLowerCase match {
      case  "true" => Some(JBoolean.TRUE)  // Cache this?
      case "false" => Some(JBoolean.FALSE) // Cache this?
      case       _ => None
    }

  // Reads a rfc2045 encoded Base64 string
  final val ParseBytes: String => Option[ProtobufByteString] =
    s => Some(ProtobufByteString.copyFrom(Base64.rfc2045.decode(s))) // Make cheaper? Protobuf has a Base64 decoder?

  final def suitableParserFor(field: FieldDescriptor)(whenIllegal: String => Nothing): String => Option[Any] =
    field.getJavaType match {
      case JavaType.BOOLEAN     => ParseBoolean
      case JavaType.BYTE_STRING => ParseBytes
      case JavaType.DOUBLE      => ParseDouble
      case JavaType.ENUM        => whenIllegal("Enum path parameters not supported!")
      case JavaType.FLOAT       => ParseFloat
      case JavaType.INT         => ParseInt
      case JavaType.LONG        => ParseLong
      case JavaType.MESSAGE     => whenIllegal("Message path parameters not supported!")
      case JavaType.STRING      => ParseString
    }

  // We use this to indicate problems with the configuration of the routes
  private final val configError: String => Nothing = s => throw new ConfigurationException("HTTP API Config: " + s)
  // We use this to signal to the requestor that there's something wrong with the request
  private final val requestError: String => Nothing = s => throw IllegalRequestException(StatusCodes.BadRequest, s)
  // This is so that we can reuse path comparisons for path value extraction
  private final val nofx: (Option[Any], FieldDescriptor) => Unit = (_,_) => ()

  // This is used to support the "*" custom pattern
  private final val ANY_METHOD = HttpMethod.custom(name = "ANY",
                                           safe = false,
                                           idempotent = false,
                                           requestEntityAcceptance = RequestEntityAcceptance.Tolerated)

  // A route which will not match anything
  private final val NoMatch = PartialFunction.empty[HttpRequest, Future[HttpResponse]]

  final class HttpEndpoint(final val methDesc: MethodDescriptor,
                           final val rule: HttpRule,
                           final val stateManager: ActorRef,
                           final val relayTimeout: Timeout)(implicit sys: ActorSystem, mat: Materializer, ec: ExecutionContext) extends PartialFunction[HttpRequest, Future[HttpResponse]] {
    private[this] final val log = Logging(sys, rule.pattern.toString) // TODO use other name?

    private[this] final val (methodPattern, urlPattern, bodyDescriptor, responseBodyDescriptor) = extractAndValidate()

    private[this] final val idExtractor = Serve.createEntityIdExtractorFor(methDesc.getInputType)

    private[this] final val jsonParser  = JsonFormat.
                                                    parser.
                                                    usingTypeRegistry(JsonFormat.TypeRegistry.
                                                                      newBuilder.
                                                                      add(bodyDescriptor).
                                                                      build())
                                                    //ignoringUnknownFields().
                                                    //usingRecursionLimit(…).

    private[this] final val jsonPrinter = JsonFormat.
                                                    printer.
                                                    usingTypeRegistry(JsonFormat.TypeRegistry.
                                                                      newBuilder.
                                                                      add(methDesc.getOutputType).
                                                                      build()).
                                                    includingDefaultValueFields()
                                                    //printingEnumsAsInts() // If you enable this, you need to fix the output for responseBody as well
                                                    //preservingProtoFieldNames(). // If you enable this, you need to fix the output for responseBody structs as well
                                                    //omittingInsignificantWhitespace().
                                                    //sortingMapKeys().

    // This method validates the configuration and returns values obtained by parsing the configuration
    private[this] final def extractAndValidate(): (HttpMethod, Path, Descriptor, Option[FieldDescriptor]) = {
      // Validate selector
      if (rule.selector != "" && rule.selector != methDesc.getFullName)
        configError(s"Rule selector [${rule.selector}] must be empty or [${methDesc.getFullName}]")

      // Validate pattern
      val (mp, up) = {
        import HttpRule.Pattern.{Empty, Get, Put, Post, Delete, Patch, Custom}
        import HttpMethods.{GET, PUT, POST, DELETE, PATCH}

        def validPath(pattern: String): Path = {
          val path = Uri.Path(pattern)
          if (!path.startsWithSlash) configError(s"Configured pattern [$pattern] does not start with slash") // FIXME better error description
          else {
            var p = path
            var found = Set[String]()
            while(!p.isEmpty) {
              p.head match {
                case '/' =>
                case vbl: String if vbl.head == '{' && vbl.last == '}' =>
                  // FIXME support more advanced variable declarations: x=*, x=**, x=/foo/** etc
                  val variable = vbl.substring(1, vbl.length - 1)
                  lookupFieldByName(methDesc.getInputType, variable) match {
                    case null => false
                    case field =>
                      if (field.isRepeated) configError("Repeated parameters [$field] are not allowed as path variables")
                      else if (field.isMapField) configError("Map parameters [$field] are not allowed as path variables")
                      else if (suitableParserFor(field)(configError) == null) () // Can't really happen
                      else if (found.contains(variable)) configError(s"Path parameter [$variable] occurs more than once")
                      else found += variable // Keep track of the variables we've seen so far
                  }
              }
              p = p.tail
            }
            path
          }
        }

        rule.pattern match {
          case               Empty => configError(s"Pattern missing for rule [$rule]!") // TODO improve error message
          case p @ Get(pattern)    => (GET,        validPath(pattern))
          case p @ Put(pattern)    => (PUT,        validPath(pattern))
          case p @ Post(pattern)   => (POST,       validPath(pattern))
          case p @ Delete(pattern) => (DELETE,     validPath(pattern))
          case p @ Patch(pattern)  => (PATCH,      validPath(pattern))
          case p @ Custom(chp)     =>
            if (chp.kind == "*")      (ANY_METHOD, validPath(chp.path)) // FIXME is "path" the same as "pattern" for the other kinds? Is an empty kind valid?
            else                      configError(s"Only Custom patterns with [*] kind supported but [${chp.kind}] found!")
        }
      }

      // Validate body value
      val bd =
        rule.body match {
          case "" => methDesc.getInputType
          case "*" =>
            if (!mp.isEntityAccepted)
              configError(s"Body configured to [*] but HTTP Method [$mp] does not have a request body.")
            else
              methDesc.getInputType
          case fieldName =>
            val field = lookupFieldByName(methDesc.getInputType, fieldName)
            if (field == null)
              configError(s"Body configured to [$fieldName] but that field does not exist on input type.")
            else if (field.isRepeated)
              configError(s"Body configured to [$fieldName] but that field is a repeated field.")
            else if (!mp.isEntityAccepted)
              configError(s"Body configured to [$fieldName] but HTTP Method $mp does not have a request body.")
            else
              field.getMessageType
        }

      // Validate response body value
      val rd =
        rule.responseBody match {
          case "" => None
          case fieldName =>
            lookupFieldByName(methDesc.getOutputType, fieldName) match {
              case null => configError("Response body field [$fieldName] does not exist on type [${methDesc.getOutputType.getFullName}]")
              case field => Some(field)
            }
        }

      if (rule.additionalBindings.exists(_.additionalBindings.nonEmpty))
        configError("Only one level of additionalBindings supported, but [$rule] has more than one!")

      (mp, up, bd, rd)
    }

    // TODO support more advanced variable declarations: x=*, x=**, x=/foo/** etc?
    @tailrec private[this] final def pathMatches(patPath: Path, reqPath: Path, effect: (Option[Any], FieldDescriptor) => Unit): Boolean =
      if (patPath.isEmpty && reqPath.isEmpty) true
      else if (patPath.isEmpty || reqPath.isEmpty) false
      else {
        if(log.isDebugEnabled)
          log.debug((if (effect eq nofx) "Matching: " else "Extracting: ") + patPath.head + " " + reqPath.head)
        val segmentMatch = (patPath.head, reqPath.head) match {
          case ('/', '/') => true
          case (vbl: String, seg: String) if !vbl.isEmpty && vbl.head == '{' && vbl.last == '}' =>
            val variable = vbl.substring(1, vbl.length - 1)
            lookupFieldByPath(methDesc.getInputType, variable) match {
              case null => false
              case field =>
                val value = suitableParserFor(field)(requestError)(seg)
                effect(value, field)
                value.isDefined
            }
          case (seg1, seg2) => seg1 == seg2
        }

        segmentMatch && pathMatches(patPath.tail, reqPath.tail, effect)
      }

    @tailrec private[this] final def lookupFieldByPath(desc: Descriptor, selector: String): FieldDescriptor =
      Names.splitNext(selector) match {
        case ("", "")          => null
        case (fieldName, "")   => lookupFieldByName(desc, fieldName)
        case (fieldName, next) =>
          val field = lookupFieldByName(desc, fieldName)
          if (field == null) null
          else if (field.getMessageType == null) null
          else lookupFieldByPath(field.getMessageType, next)
      }

    // Question: Do we need to handle conversion from JSON names?
    private[this] final def lookupFieldByName(desc: Descriptor, selector: String): FieldDescriptor =
      desc.findFieldByName(selector) // TODO potentially start supporting path-like selectors with maximum nesting level?

    private[this] final def parseRequestParametersInto(query: Map[String, List[String]], inputBuilder: DynamicMessage.Builder): Unit = {
      query.foreach {
        case (selector, values) =>
          if (values.nonEmpty) {
            lookupFieldByPath(methDesc.getInputType, selector) match {
              case null => requestError("Query parameter [$selector] refers to non-existant field")
              case field if field.getMessageType != null         => requestError("Query parameter [$selector] refers to a message type") // FIXME validate assumption that this is prohibited
              case field if !field.isRepeated && values.size > 1 => requestError("Multiple values sent for non-repeated field by query parameter [$selector]")
              case field => // FIXME verify that we can set nested fields from the inputBuilder type
                val x = suitableParserFor(field)(requestError)
                if (field.isRepeated) {
                  values foreach {
                    v => inputBuilder.addRepeatedField(field, x(v).getOrElse(requestError("Malformed Query parameter [$selector]")))
                  }
                } else inputBuilder.setField(field, x(values.head).getOrElse(requestError("Malformed Query parameter [$selector]")))
            }
          } // Ignore empty values
      }
    }

    private[this] final def parsePathParametersInto(requestPath: Path, inputBuilder: DynamicMessage.Builder): Unit =
      pathMatches(urlPattern, requestPath, (value, field) =>
        inputBuilder.setField(field, value.getOrElse(requestError("Path contains value of wrong type!")))
      )

    final def parseCommand(req: HttpRequest): Future[Command] = {
      if (rule.body.nonEmpty && req.entity.contentType != ContentTypes.`application/json`) {
        Future.failed(IllegalRequestException(StatusCodes.BadRequest, "Content-type must be application/json!"))
      } else {
        val inputBuilder = DynamicMessage.newBuilder(methDesc.getInputType)
        rule.body match {
          case "" => // Iff empty body rule, then only query parameters
            req.discardEntityBytes();
            parseRequestParametersInto(req.uri.query().toMultiMap, inputBuilder)
            parsePathParametersInto(req.uri.path, inputBuilder)
            Future.successful(createCommand(inputBuilder.build))
          case "*" => // Iff * body rule, then no query parameters, and only fields not mapped in path variables
            Unmarshal(req.entity).to[String].map(str => { 
              jsonParser.merge(str, inputBuilder)
              parsePathParametersInto(req.uri.path, inputBuilder)
              createCommand(inputBuilder.build)
            })
          case fieldName => // Iff fieldName body rule, then all parameters not mapped in path variables
            Unmarshal(req.entity).to[String].map(str => {
              val subField = lookupFieldByName(methDesc.getInputType, fieldName)
              val subInputBuilder = DynamicMessage.newBuilder(subField.getMessageType)
              jsonParser.merge(str, subInputBuilder)
              parseRequestParametersInto(req.uri.query().toMultiMap, inputBuilder)
              parsePathParametersInto(req.uri.path, inputBuilder)
              inputBuilder.setField(subField, subInputBuilder.build())
              createCommand(inputBuilder.build)
            })
        }
      }
    }

    override final def isDefinedAt(req: HttpRequest): Boolean =
      (methodPattern == ANY_METHOD || req.method == methodPattern) && pathMatches(urlPattern, req.uri.path, nofx)

    override final def apply(req: HttpRequest): Future[HttpResponse] =
      parseCommand(req).
        flatMap(command => sendCommand(command)(relayTimeout).map(createResponse)).
        recover {
          case ire: IllegalRequestException => HttpResponse(ire.status.intValue, entity = ire.status.reason)
        }

    private[this] final def debugMsg(msg: DynamicMessage, preamble: String): Unit =
      if(log.isDebugEnabled)
        log.debug(s"$preamble: ${msg}${msg.getAllFields().asScala.map(f => s"\n\r   * Request Field: [${f._1.getFullName}] = [${f._2}]").mkString}")

    private[this] final def createCommand(request: DynamicMessage): Command = {
      debugMsg(request, "Got request")
      Command(entityId = idExtractor(request),
               name    = methDesc.getName,
               payload = Some(ProtobufAny(typeUrl = Serve.AnyTypeUrlHostName + methDesc.getInputType.getFullName, value = request.toByteString)))
    }

    private[this] final def sendCommand(command: Command)(implicit timeout: Timeout): Future[DynamicMessage] = {
      (stateManager ? command).mapTo[ProtobufByteString].transform({
        case Success(bytes) =>
          val response = DynamicMessage.parseFrom(methDesc.getOutputType, bytes)
          debugMsg(response, "Got response")
          Success(response)
        case Failure(cf: StateManager.CommandFailure) => requestError(cf.getMessage) // TODO Should we handle CommandFailures like this?
        case Failure(t) => Failure(t)
      })
    }

    // FIXME Devise other way of supporting responseBody, this is waaay too costly and unproven
    // This method converts an arbitrary type to something which can be represented as JSON.
    private[this] final def responseBody(jType: JavaType, value: AnyRef, repeated: Boolean): com.google.protobuf.Value = {
      val result =
        if (repeated) {
          Value.newBuilder.setListValue(
            ListValue.
              newBuilder.
              addAllValues(
                value.asInstanceOf[java.lang.Iterable[AnyRef]].asScala.map(v => responseBody(jType, v, false)).asJava
              )
          )
        } else {
          val b = Value.newBuilder
          jType match {
            case JavaType.BOOLEAN     => b.setBoolValue(value.asInstanceOf[JBoolean])
            case JavaType.BYTE_STRING => b.setStringValueBytes(value.asInstanceOf[ProtobufByteString])
            case JavaType.DOUBLE      => b.setNumberValue(value.asInstanceOf[JDouble])
            case JavaType.ENUM        => b.setStringValue(value.asInstanceOf[EnumValueDescriptor].getName) // Switch to getNumber if enabling printingEnumsAsInts in the JSON Printer
            case JavaType.FLOAT       => b.setNumberValue(value.asInstanceOf[JFloat].toDouble)
            case JavaType.INT         => b.setNumberValue(value.asInstanceOf[JInteger].toDouble)
            case JavaType.LONG        => b.setNumberValue(value.asInstanceOf[JLong].toDouble)
            case JavaType.MESSAGE     =>
              val sb = Struct.newBuilder
              value.asInstanceOf[MessageOrBuilder].getAllFields.forEach(
                (k,v) => sb.putFields(k.getJsonName, responseBody(k.getJavaType, v, k.isRepeated)) //Switch to getName if enabling preservingProtoFieldNames in the JSON Printer
              )
              b.setStructValue(sb)
            case JavaType.STRING      => b.setStringValue(value.asInstanceOf[String])
          }
        }
      result.build()
    }

    private[this] final def createResponse(response: DynamicMessage): HttpResponse = {
      val output =
        responseBodyDescriptor match {
          case None        =>
            response
          case Some(field) =>
            response.getField(field) match {
              case m: MessageOrBuilder if !field.isRepeated => m // No need to wrap this
              case value => responseBody(field.getJavaType, value, field.isRepeated)
            }
        }
      HttpResponse(200, entity = HttpEntity(ContentTypes.`application/json`, jsonPrinter.print(output)))
    }
  }

 /**
  * ScalaPB doesn't do this conversion for us unfortunately.
  * By doing it, we can use HttpProto.entityKey.get() to read the entity key nicely.
  */
  private[this] final def convertMethodOptions(method: MethodDescriptor): ScalaPBDescriptorProtos.MethodOptions =
    ScalaPBDescriptorProtos.MethodOptions.fromJavaProto(method.toProto.getOptions).withUnknownFields(
      scalapb.UnknownFieldSet(method.getOptions.getUnknownFields.asMap.asScala.map {
        case (idx, f) => idx.toInt -> scalapb.UnknownFieldSet.Field(
          varint          = f.getVarintList.asScala.map(_.toLong),
          fixed64         = f.getFixed64List.asScala.map(_.toLong),
          fixed32         = f.getFixed32List.asScala.map(_.toInt),
          lengthDelimited = f.getLengthDelimitedList.asScala
        )
      }.toMap)
    )

  final def serve(stateManager: ActorRef, relayTimeout: Timeout, serviceDesc: ServiceDescriptor)(implicit sys: ActorSystem, mat: Materializer, ec: ExecutionContext): PartialFunction[HttpRequest, Future[HttpResponse]] = {
    val log = Logging(sys, "HttpApi")
    serviceDesc.getMethods.iterator.asScala.flatMap({
      method =>
        val rule = AnnotationsProto.http.get(convertMethodOptions(method)) match {
          case Some(rule) =>
            log.info(s"Using configured HTTP API endpoint using [$rule]")
            rule
          case None =>
            val rule = HttpRule.of(selector = method.getFullName, // We know what thing we are proxying
                               body = "*",                        // Parse all input
                               responseBody = "",                 // Include all output
                               additionalBindings = Nil,          // No need for additional bindings
                               pattern = HttpRule.Pattern.Post((Path / "v1" / method.getName).toString))
            log.info(s"Using generated HTTP API endpoint using [$rule]")
            rule
        }
        (rule +: rule.additionalBindings).map(r => new HttpEndpoint(method, r, stateManager, relayTimeout))
    }).foldLeft(NoMatch) {
      case (NoMatch,    first) => first
      case (previous, current) => current orElse previous // Last goes first
    }
  }
}