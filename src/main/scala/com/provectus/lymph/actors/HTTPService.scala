package com.provectus.lymph.actors

import akka.actor.{Props, ActorRef, ActorSystem}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server._
import akka.stream.ActorMaterializer
import akka.pattern.ask
import com.provectus.lymph.LymphConfig

import spray.json._
import org.json4s.DefaultFormats
import org.json4s.native.Json

import com.provectus.lymph.jobs.{JobResult, JobConfiguration}

import scala.concurrent.ExecutionContext.Implicits.global

import scala.language.reflectiveCalls

/** HTTP interface */
private[lymph] trait HTTPService extends Directives with SprayJsonSupport with DefaultJsonProtocol {

  /** We must implement json parse/serializer for [[Any]] type */
  implicit object AnyJsonFormat extends JsonFormat[Any] {
    def write(x: Any) = x match {
      case number: Int => JsNumber(number)
      case string: String => JsString(string)
      case sequence: Seq[_] => seqFormat[Any].write(sequence)
      case map: Map[String, _] => mapFormat[String, Any] write map
      case boolean: Boolean if boolean => JsTrue
      case boolean: Boolean if !boolean => JsFalse
      case unknown => serializationError("Do not understand object of type " + unknown.getClass.getName)
    }
    def read(value: JsValue) = value match {
      case JsNumber(number) => number.toBigInt()
      case JsString(string) => string
      case array: JsArray => listFormat[Any].read(value)
      case jsObject: JsObject => mapFormat[String, Any].read(value)
      case JsTrue => true
      case JsFalse => false
      case unknown => deserializationError("Do not understand how to deserialize " + unknown)
    }
  }

  implicit val system: ActorSystem
  implicit val materializer: ActorMaterializer

  // JSON to JobConfiguration mapper (5 fields)
  implicit val jobCreatingRequestFormat = jsonFormat5(JobConfiguration)

  // actor which is used for running jobs according to request
  lazy val jobRequestActor:ActorRef = system.actorOf(Props[JobRunner], name = "SyncJobRunner")

  // /jobs
  def route : Route = path("jobs") {
    // POST /jobs
    post {
      // POST body must be JSON mapable into JobConfiguration
      entity(as[JobConfiguration]) { jobCreatingRequest =>
        complete {

          println(jobCreatingRequest.parameters)

          // TODO: catch timeout exception
          // Run job asynchronously
          val future = jobRequestActor.ask(jobCreatingRequest)(timeout = LymphConfig.Contexts.timeout(jobCreatingRequest.name))

          future.map[ToResponseMarshallable] {
            // Map is result, so everything is ok
            case result: Map[String, Any] =>
              val jobResult = JobResult(success = true, payload = result, request = jobCreatingRequest, errors = List.empty)
              Json(DefaultFormats).write(jobResult)
            // String is always error
            case error: String =>
              val jobResult = JobResult(success = false, payload = Map.empty, request = jobCreatingRequest, errors = List(error))
              Json(DefaultFormats).write(jobResult)
            // Something we don't know what
            case _ => BadRequest -> "Something went wrong"
          }
        }
      }
    }
  }
}