package cromwell.engine.workflow.lifecycle.execution.callcaching

import cats.data.{NonEmptyList, Validated}
import cats.implicits._
import cromwell.engine.workflow.lifecycle.execution.callcaching.CallCacheDiffQueryParameter.CallCacheDiffQueryCall

import scala.util.{Failure, Success, Try}

object CallCacheDiffQueryParameter {
  case class CallCacheDiffQueryCall(workflowId: String, callFqn: String, jobIndex: Option[Int])

  private def missingWorkflowError(attribute: String) = s"missing $attribute query parameter".invalidNel

  def fromParameters(parameters: Seq[(String, String)]): Validated[NonEmptyList[String], CallCacheDiffQueryParameter] = {
    def extractIndex(parameter: String): Validated[NonEmptyList[String], Option[Int]] = {
      parameters.find(_._1 == parameter) match {
        case Some((_, value)) => Try(value.toInt) match {
          case Success(index) => Option(index).validNel
          case Failure(f) => f.getMessage.invalidNel
        }
        case None => None.validNel
      }
    }

    def extractAttribute(parameter: String): Validated[NonEmptyList[String], String] = {
      parameters.find(_._1 == parameter) match {
        case Some((_, value)) => value.validNel
        case None => missingWorkflowError(parameter)
      }
    }
    
    val workflowAValidation = extractAttribute("workflowA")
    val workflowBValidation = extractAttribute("workflowB")

    val callAValidation = extractAttribute("callA")
    val callBValidation = extractAttribute("callB")

    val indexAValidation = extractIndex("indexA")
    val indexBValidation = extractIndex("indexB")

    (workflowAValidation |@| callAValidation |@| indexAValidation |@|
      workflowBValidation |@| callBValidation |@| indexBValidation) map {
      case ((workflowA, callA, indexA, workflowB, callB, indexB)) =>
        CallCacheDiffQueryParameter(
          CallCacheDiffQueryCall(workflowA, callA, indexA),
          CallCacheDiffQueryCall(workflowB, callB, indexB)
        )
    }
  }
}

case class CallCacheDiffQueryParameter(callA: CallCacheDiffQueryCall, callB: CallCacheDiffQueryCall)
