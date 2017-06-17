package cromwell.webservice

import akka.actor.{ActorRef, ActorRefFactory}
import akka.http.scaladsl.server.Directives._

import scala.concurrent.{ExecutionContext, Future}
import akka.http.scaladsl.model.{Multipart, StatusCodes, Uri}
import akka.http.scaladsl.model.Multipart.BodyPart
import akka.stream.ActorMaterializer
import cromwell.engine.backend.BackendConfiguration
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import cromwell.core.{WorkflowAborted, WorkflowId, WorkflowSubmitted}
import cromwell.engine.workflow.workflowstore.{WorkflowStoreActor, WorkflowStoreEngineActor, WorkflowStoreSubmitActor}
import akka.pattern.ask
import akka.util.{ByteString, Timeout}
import cromwell.engine.workflow.WorkflowManagerActor
import cromwell.services.metadata.MetadataService._
import cromwell.webservice.metadata.{MetadataBuilderActor, WorkflowQueryPagination}
import cromwell.webservice.metadata.MetadataBuilderActor.{BuiltMetadataResponse, FailedMetadataResponse, MetadataBuilderActorResponse}
import WorkflowJsonSupport._
import akka.http.scaladsl.server.Route
import cats.data.NonEmptyList
import com.typesafe.config.{Config, ConfigFactory}
import cromwell.engine.workflow.WorkflowManagerActor.WorkflowNotFoundException
import cromwell.engine.workflow.workflowstore.WorkflowStoreEngineActor.WorkflowStoreEngineActorResponse

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

// FIXME: rename once the cutover happens
trait AkkaHttpService {
  // FIXME: check HTTP codes both before/after
  import cromwell.webservice.AkkaHttpService._

  implicit def actorRefFactory: ActorRefFactory
  implicit val materializer: ActorMaterializer
  implicit val ec: ExecutionContext

  val workflowStoreActor: ActorRef
  val workflowManagerActor: ActorRef
  val serviceRegistryActor: ActorRef

  // FIXME: make this bigger and elsewhere?
  val duration = 5.seconds
  implicit val timeout: Timeout = duration

  private val backendResponse = BackendResponse(BackendConfiguration.AllBackendEntries.map(_.name).sorted, BackendConfiguration.DefaultBackendEntry.name)

  // FIXME: Missing thib's new call cache stuff and ruchi's label stuff
  val routes =
    path("workflows" / Segment / "backends") { version =>
      get { complete(backendResponse) }
    } ~
    path("engine" / Segment / "stats") { version =>
      get {
        onComplete(workflowManagerActor.ask(WorkflowManagerActor.EngineStatsCommand).mapTo[EngineStatsActor.EngineStats]) {
          case Success(stats) => complete(stats)
          case Failure(blah) => failInternalServerError(new RuntimeException("Unable to gather engine stats"))
        }
      }
    } ~
    path("engine" / Segment / "version") { version =>
      get { complete(versionResponse(ConfigFactory.load("cromwell-version.conf").getConfig("version"))) }
    } ~
    path("workflows" / Segment / Segment / "status") { (version, possibleWorkflowId) =>
      get { metadataBuilderRequest(possibleWorkflowId, (w: WorkflowId) => GetStatus(w)) }
    } ~
    path("workflows" / Segment / Segment / "outputs") { (version, possibleWorkflowId) =>
      get { metadataBuilderRequest(possibleWorkflowId, (w: WorkflowId) => WorkflowOutputs(w)) }
    } ~
    path("workflows" / Segment / Segment / "logs") { (version, possibleWorkflowId) =>
      get { metadataBuilderRequest(possibleWorkflowId, (w: WorkflowId) => GetLogs(w)) }
    } ~
    path("workflows" / Segment / "query") { version =>
      get {
        parameterSeq { parameters =>
          extractUri { uri =>
            metadataQueryRequest(parameters, uri)
          }
        }
      }
    } ~
    path("workflows" / Segment / "query") { version =>
      post {
        parameterSeq { parameters =>
          extractUri { uri =>
            metadataQueryRequest(parameters, uri)
          }
        }
      }
    } ~
    encodeResponse {
      path("workflows" / Segment / Segment / "metadata") { (version, possibleWorkflowId) =>
        parameters('includeKeys.*, 'excludeKeys.*, 'expandSubWorkflows.as[Boolean].?) { (includeKeys, excludeKeys, expandSubWorkflowsOption) =>
          val includeKeysOption = NonEmptyList.fromList(includeKeys.toList)
          val excludeKeysOption = NonEmptyList.fromList(excludeKeys.toList)
          val expandSubWorkflows = expandSubWorkflowsOption.getOrElse(false)

          (includeKeysOption, excludeKeysOption) match {
            case (Some(_), Some(_)) => failBadRequest(new IllegalArgumentException("includeKey and excludeKey may not be specified together"))
            // FIXME: not sure why we're maintaining the options
            case (_, _) => metadataBuilderRequest(possibleWorkflowId, (w: WorkflowId) => GetSingleWorkflowMetadataAction(w, includeKeysOption, excludeKeysOption, expandSubWorkflows))
          }
        }
      }
    } ~
    path("workflows" / Segment / Segment / "timing") { (version, possibleWorkflowId) =>
      onComplete(validateWorkflowId(possibleWorkflowId)) {
        case Success(_) => getFromResource("workflowTimings/workflowTimings.html")
        case Failure(e) => failInternalServerError(e)
      }
    } ~
    path("workflows" / Segment / Segment / "abort") { (version, possibleWorkflowId) =>
      post {
        val response = validateWorkflowId(possibleWorkflowId) flatMap { w =>
          workflowStoreActor.ask(WorkflowStoreActor.AbortWorkflow(w, workflowManagerActor)).mapTo[WorkflowStoreEngineActorResponse]
        }
        // FIXME: Clean up WorkflowStoreEngineActorResponse if there's some combination possible?
        onComplete(response) {
          case Success(WorkflowStoreEngineActor.WorkflowAborted(id)) => complete(WorkflowAbortResponse(id.toString, WorkflowAborted.toString))
          case Success(WorkflowStoreEngineActor.WorkflowAbortFailed(_, e: IllegalStateException)) => complete((StatusCodes.Forbidden, APIResponse.fail(e)))
          // This shouldn't happen as we've already checked the ID, but including it for completeness' sake
          case Success(WorkflowStoreEngineActor.WorkflowAbortFailed(_, e: WorkflowNotFoundException)) => complete((StatusCodes.NotFound, APIResponse.fail(e)))
          case Success(WorkflowStoreEngineActor.WorkflowAbortFailed(_, e)) => failInternalServerError(e)
          case Failure(e: UnrecognizedWorkflowException) => complete((StatusCodes.NotFound, APIResponse.fail(e)))
          // Something went awry with the actual request
          case Failure(e) => failInternalServerError(e)
          case (_) => complete("incomplete")
        }
      }
    } ~
    path("workflows" / Segment) { version =>
      post {
        entity(as[Multipart.FormData]) { formData =>
          submitRequest(formData, true)
        }
      }
    } ~
  path("workflows" / Segment / "batch") { version =>
    post {
      entity(as[Multipart.FormData]) { formData =>
        submitRequest(formData, false)
      }
    }
  }

  private def submitRequest(formData: Multipart.FormData, isBatch: Boolean): Route = {
    val allParts: Future[Map[String, ByteString]] = formData.parts.mapAsync[(String, ByteString)](1) {
      case b: BodyPart => b.toStrict(duration).map(strict => b.name -> strict.entity.data)
    }.runFold(Map.empty[String, ByteString])((map, tuple) => map + tuple)

    // FIXME: make this less hokey
    onComplete(allParts) {
      case Success(formData) =>
        PartialWorkflowSources.fromSubmitRoute(formData, allowNoInputs = isBatch) match {
          case Success(workflowSourceFiles) if workflowSourceFiles.size == 1 =>
            onComplete(workflowStoreActor.ask(WorkflowStoreActor.SubmitWorkflow(workflowSourceFiles.head)).mapTo[WorkflowStoreSubmitActor.WorkflowSubmittedToStore]) {
              case Success(w) => complete((StatusCodes.Created, WorkflowSubmitResponse(w.workflowId.toString, WorkflowSubmitted.toString)))
              case Failure(e) => failInternalServerError(e)
            }
          case Success(workflowSourceFiles) => failBadRequest(new IllegalArgumentException("To submit more than one workflow at a time, use the batch endpoint."))
          case Failure(t) => failBadRequest(t)
        }
      case Failure(e) => failInternalServerError(e)
    }
  }

  private def validateWorkflowId(possibleWorkflowId: String): Future[WorkflowId] = {
    Try(WorkflowId.fromString(possibleWorkflowId)) match {
      case Success(w) =>
        serviceRegistryActor.ask(NewValidateWorkflowId(w)).mapTo[WorkflowValidationResponse] map {
          case RecognizedWorkflowId => w
          case UnrecognizedWorkflowId => throw new UnrecognizedWorkflowException(s"Unrecognized workflow ID: $w")
          case FailedToCheckWorkflowId(t) => throw t
        }
      case Failure(t) => Future.failed(new RuntimeException(s"Invalid workflow ID: '$possibleWorkflowId'."))
    }
  }

  private def metadataBuilderRequest(possibleWorkflowId: String, request: WorkflowId => ReadAction): Route = {
    val metadataBuilderActor = actorRefFactory.actorOf(MetadataBuilderActor.props(serviceRegistryActor))
    val response = validateWorkflowId(possibleWorkflowId) flatMap { w => metadataBuilderActor.ask(request(w)).mapTo[MetadataBuilderActorResponse] }

    onComplete(response) {
      case Success(r: BuiltMetadataResponse) => complete(r.response)
      case Success(r: FailedMetadataResponse) => failInternalServerError(r.reason)
      case Failure(e: UnrecognizedWorkflowException) => complete((StatusCodes.NotFound, APIResponse.fail(e)))
      case Failure(e) => failInternalServerError(e)
    }
  }

  private def metadataQueryRequest(parameters: Seq[(String, String)], uri: Uri): Route = {
    val response = serviceRegistryActor.ask(WorkflowQuery(parameters)).mapTo[MetadataQueryResponse]

    onComplete(response) {
      case Success(w: WorkflowQuerySuccess) =>
        val headers = WorkflowQueryPagination.generateLinkHeaders(uri, w.meta)
        respondWithHeaders(headers) {
          complete(w.response)
        }
      case Success(w: WorkflowQueryFailure) => failBadRequest(w.reason)
      case Failure(e) => failInternalServerError(e)
    }
  }
}

object AkkaHttpService {
  import spray.json._

  final case class BackendResponse(supportedBackends: List[String], defaultBackend: String)

  final case class UnrecognizedWorkflowException(message: String) extends Exception(message)

  def versionResponse(versionConf: Config) = JsObject(Map("cromwell" -> versionConf.getString("cromwell").toJson))

  def failInternalServerError(e: Throwable): Route = complete((StatusCodes.InternalServerError, APIResponse.fail(e)))
  def failBadRequest(e: Throwable): Route = complete((StatusCodes.BadRequest, APIResponse.fail(e)))
}
