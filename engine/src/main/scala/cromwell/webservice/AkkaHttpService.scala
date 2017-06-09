package cromwell.webservice

import akka.actor.ActorRef
import akka.http.scaladsl.server.Directives._

import scala.concurrent.{ExecutionContext, Future}
import akka.http.scaladsl.model.{Multipart, StatusCodes, Uri}
import akka.http.scaladsl.model.Multipart.BodyPart
import akka.stream.ActorMaterializer
import cromwell.engine.backend.BackendConfiguration
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import cromwell.core.{WorkflowAborted, WorkflowId, WorkflowSourceFilesCollection, WorkflowSubmitted}
import cromwell.engine.workflow.workflowstore.{WorkflowStoreActor, WorkflowStoreEngineActor, WorkflowStoreSubmitActor}
import akka.pattern.ask
import akka.util.Timeout
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
import spray.json.JsObject

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

// FIXME: rename once the cutover happens
trait AkkaHttpService extends PerRequestCreator {

  import cromwell.webservice.AkkaHttpService._

  implicit val materializer: ActorMaterializer
  implicit val ec: ExecutionContext

  val workflowStoreActor: ActorRef
  val workflowManagerActor: ActorRef
  val serviceRegistryActor: ActorRef

  // FIXME: make this bigger and elsewhere?
  val duration = 5.seconds
  implicit val timeout: Timeout = duration

  // FIXME: these should live elsewhere (WorkflowJsonSupport currently)
  implicit val BackendResponseFormat = jsonFormat2(BackendResponse)
  implicit val BuiltStatusResponseFormat = jsonFormat1(BuiltMetadataResponse)
  val backendResponse = BackendResponse(BackendConfiguration.AllBackendEntries.map(_.name).sorted, BackendConfiguration.DefaultBackendEntry.name)


  // FIXME: This is missing the 'api' stuff
  val routes =
    path("workflows" / Segment / "backends") { version =>
      get {
        complete(backendResponse)
      }
    } ~
      path("engine" / Segment / "stats") { version =>
        get {
          onComplete(workflowManagerActor.ask(WorkflowManagerActor.EngineStatsCommand).mapTo[EngineStatsActor.EngineStats]) {
            case Success(stats) => complete(stats)
            case Failure(blah) => complete((StatusCodes.InternalServerError, APIResponse.fail(new RuntimeException("Unable to gather engine stats"))))
          }
        }
      } ~
      path("engine" / Segment / "version") { version =>
        get {
          complete {
            lazy val versionConf = ConfigFactory.load("cromwell-version.conf").getConfig("version")
            versionResponse(versionConf)
          }
        }
      } ~
      path("workflows" / Segment / Segment / "status") { (version, possibleWorkflowId) =>
        get {
          metadataBuilderRequest(possibleWorkflowId, (w: WorkflowId) => GetStatus(w))
        }
      } ~
      path("workflows" / Segment / Segment / "outputs") { (version, possibleWorkflowId) =>
        get {
          metadataBuilderRequest(possibleWorkflowId, (w: WorkflowId) => WorkflowOutputs(w))
        }
      } ~
      path("workflows" / Segment / Segment / "logs") { (version, possibleWorkflowId) =>
        get {
          metadataBuilderRequest(possibleWorkflowId, (w: WorkflowId) => GetLogs(w))
        }
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
              case (Some(_), Some(_)) => complete((StatusCodes.BadRequest, APIResponse.fail(new IllegalArgumentException("includeKey and excludeKey may not be specified together"))))
              // FIXME: not sure why we're maintaining the options
              case (_, _) => metadataBuilderRequest(possibleWorkflowId, (w: WorkflowId) => GetSingleWorkflowMetadataAction(w, includeKeysOption, excludeKeysOption, expandSubWorkflows))
            }
          }
        }
      } ~
      path("workflows" / Segment / Segment / "timing") { (version, possibleWorkflowId) =>
        onComplete(validateWorkflowId(possibleWorkflowId)) {
          case Success(_) => getFromResource("workflowTimings/workflowTimings.html")
          case Failure(e) => complete((StatusCodes.InternalServerError, APIResponse.fail(e)))
        }
      } ~
      path("workflows" / Segment / Segment / "abort") { (version, possibleWorkflowId) =>
        post {
          val z = validateWorkflowId(possibleWorkflowId) flatMap { w =>
            workflowStoreActor.ask(WorkflowStoreActor.AbortWorkflow(w, workflowManagerActor)).mapTo[WorkflowStoreEngineActorResponse]
          }
          // FIXME: Clean up WorkflowStoreEngineActorResponse if there's some combination possible?
          onComplete(z) {
            case Success(WorkflowStoreEngineActor.WorkflowAborted(id)) => complete(WorkflowAbortResponse(id.toString, WorkflowAborted.toString))
            case Success(WorkflowStoreEngineActor.WorkflowAbortFailed(_, e: IllegalStateException)) => complete((StatusCodes.Forbidden, APIResponse.fail(e)))
            // This shouldn't happen as we've already checked the ID, but including it for completeness' sake
            case Success(WorkflowStoreEngineActor.WorkflowAbortFailed(_, e: WorkflowNotFoundException)) => complete((StatusCodes.NotFound, APIResponse.fail(e)))
            case Success(WorkflowStoreEngineActor.WorkflowAbortFailed(_, e)) => complete((StatusCodes.InternalServerError, APIResponse.fail(e)))
            case Failure(e: UnrecognizedWorkflowException) => complete((StatusCodes.NotFound, APIResponse.fail(e)))
            // Something went awry with the actual request
            case Failure(e) => complete((StatusCodes.InternalServerError, APIResponse.fail(e)))
            case (_) => complete("incomplete")
          }
        }
      } ~
      path("workflows" / Segment) { version => // FIXME: This doesn't handle all of the new workflow inputs files nor the zip file. See hte PartialSources stuff
        post {
          entity(as[Multipart.FormData]) { shite =>
            val allParts: Future[Map[String, String]] = shite.parts.mapAsync[(String, String)](1) {
              case b: BodyPart => b.toStrict(duration).map(strict => b.name -> strict.entity.data.utf8String)
            }.runFold(Map.empty[String, String])((map, tuple) => map + tuple)

            onSuccess(allParts) { files =>
              val wdlSource = files("wdlSource")
              val workflowInputs = files.getOrElse("workflowInputs", "{}")
              val workflowOptions = files.getOrElse("workflowOptions", "{}")
              val workflowLabels = files.getOrElse("customLabels", "{}")
              val workflowSourceFiles = WorkflowSourceFilesCollection(wdlSource, workflowInputs, workflowOptions, workflowLabels, None)
              // FIXME: blows up on wdlSource, doesn't check for other inputs
              onComplete(workflowStoreActor.ask(WorkflowStoreActor.SubmitWorkflow(workflowSourceFiles)).mapTo[WorkflowStoreSubmitActor.WorkflowSubmittedToStore]) {
                case Success(w) => complete((StatusCodes.Created, WorkflowSubmitResponse(w.workflowId.toString, WorkflowSubmitted.toString)))
                case Failure(e) => complete("oopsie daisy")
              }
            }
          }
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
      case Success(r: BuiltMetadataResponse) => complete(r)
      case Success(r: FailedMetadataResponse) => complete((StatusCodes.InternalServerError, APIResponse.fail(r.reason)))
      case Failure(e: UnrecognizedWorkflowException) => complete((StatusCodes.NotFound, APIResponse.fail(e)))
      case Failure(e) => complete((StatusCodes.InternalServerError, APIResponse.fail(e)))
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
      case Success(w: WorkflowQueryFailure) => complete((StatusCodes.BadRequest, APIResponse.fail(w.reason)))
      case Failure(e) => complete((StatusCodes.InternalServerError, APIResponse.fail(e)))
    }
  }
}

object AkkaHttpService {
  import spray.json._

  case class BackendResponse(supportedBackends: List[String], defaultBackend: String)

  final case class UnrecognizedWorkflowException(message: String) extends Exception(message)

  def versionResponse(versionConf: Config) = JsObject(Map(
    "cromwell" -> versionConf.getString("cromwell").toJson
  ))
}
