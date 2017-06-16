package cromwell

import java.io.File

import cats.data.Validated._
import cats.syntax.cartesian._
import cats.syntax.validated._
import com.typesafe.config.ConfigFactory
import cromwell.CommandLineParser._
import cromwell.core.path.{DefaultPathBuilder, Path}
import cromwell.core.{WorkflowSourceFilesCollection, WorkflowSourceFilesWithDependenciesZip, WorkflowSourceFilesWithoutImports}
import cromwell.server.CromwellSystem
import lenthall.exception.MessageAggregation
import lenthall.validation.ErrorOr._
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, TimeoutException}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

sealed abstract class CromwellCommandLine
case object UsageAndExit extends CromwellCommandLine
case object RunServer extends CromwellCommandLine
case object VersionAndExit extends CromwellCommandLine

// There are subcommands here for `run` and `server` (and maybe `version`?).  `server` doesn't take any arguments.

// Cross check all these parameter names with WES so we're not needlessly GA4GH hostile.

// run --workflow-descriptor <workflow source file>
//    [--workflow-params <inputs> (default none, but seriously uninteresting without this)]
//    [--key-values (default none)]
//    [--workflow-type <workflow type> (default "WDL")]
//    [--workflow-type-version <workflow type version> (default "haha version whats that")]
//    [--labels <labels> (default none)]
//    [--imports <workflow import bundle> (default none)[
//    [--metadata-output-path <metadata output path> (default none)]

case class CommandLine(source: File, inputs: Option[File])

object CromwellCommandLine {

  object LogMode {
    sealed trait Mode {
      def logbackSetting: String
    }
    final case class Standard(override val logbackSetting: String = "STANDARD") extends Mode
    final case class Pretty(override val logbackSetting: String = "PRETTY") extends Mode
  }
  /**
    * If a cromwell server is going to be run, makes adjustments to the default logback configuration.
    * Overwrites LOG_MODE system property used in our logback.xml, _before_ the logback classes load.
    * Restored from similar functionality in
    *   https://github.com/broadinstitute/cromwell/commit/2e3f45b#diff-facc2160a82442932c41026c9a1e4b2bL28
    * TODO: Logback is configurable programmatically. We don't have to overwrite system properties like this.
    *
    * Also copies variables from config/system/environment/defaults over to the system properties.
    * Fixes issue where users are trying to specify Java properties as environment variables.
    */
  def initLogging(command: Command): Unit = {
    val logMode = command match {
      case Server => LogMode.Standard()
      case Run => LogMode.Pretty()
    }

    val defaultProps = Map("LOG_MODE" -> logMode.logbackSetting, "LOG_LEVEL" -> "INFO")

    val config = ConfigFactory.load
      .withFallback(ConfigFactory.systemEnvironment())
      .withFallback(ConfigFactory.parseMap(defaultProps.asJava, "Defaults"))

    val props = sys.props
    defaultProps.keys foreach { key =>
      props += key -> config.getString(key)
    }

    /*
    We've possibly copied values from the environment, or our defaults, into the system properties.
    Make sure that the next time one uses the ConfigFactory that our updated system properties are loaded.
     */
    ConfigFactory.invalidateCaches()
  }

  def buildCromwellSystem: CromwellSystem = {
    lazy val Log = LoggerFactory.getLogger("cromwell")

    Try {
      new CromwellSystem {}
    } recoverWith {
      case t: Throwable =>
        Log.error("Failed to instantiate Cromwell System. Shutting down Cromwell.")
        Log.error(t.getMessage)
        System.exit(1)
        Failure(t)
    } get
  }

  def waitAndExit(runner: CromwellSystem => Future[Any], workflowManagerSystem: CromwellSystem): Unit = {
    val futureResult = runner(workflowManagerSystem)
    Await.ready(futureResult, Duration.Inf)

    try {
      Await.ready(workflowManagerSystem.shutdownActorSystem(), 30 seconds)
    } catch {
      case timeout: TimeoutException => Console.err.println("Timed out trying to shutdown actor system")
      case other: Exception => Console.err.println(s"Unexpected error trying to shutdown actor system: ${other.getMessage}")
    }

    val returnCode = futureResult.value.get match {
      case Success(_) => 0
      case Failure(e) =>
        Console.err.println(e.getMessage)
        1
    }

    sys.exit(returnCode)
  }
}

// We cannot initialize the logging until after we parse the command line in Main.scala. So we have to bundle up and pass back this information, just for logging.
case class SingleRunPathParameters(wdlPath: Path, inputsPath: Option[Path], optionsPath: Option[Path], metadataPath: Option[Path], importPath: Option[Path], labelsPath: Option[Path]) {
  def logMe(log: org.slf4j.Logger) = {
    log.info(s"  WDL file: $wdlPath")
    inputsPath foreach { i => log.info(s"  Inputs: $i") }
    optionsPath foreach { o => log.info(s"  Workflow Options: $o") }
    metadataPath foreach { m => log.info(s"  Workflow Metadata Output: $m") }
    importPath foreach { i => log.info(s"  Workflow import bundle: $i") }
    labelsPath foreach { o => log.info(s"  Custom labels: $o") }
  }
}

final case class RunSingle(sourceFiles: WorkflowSourceFilesCollection, paths: SingleRunPathParameters) extends CromwellCommandLine

object RunSingle {

  lazy val Log = LoggerFactory.getLogger("cromwell")

  def apply(args: Seq[String]): RunSingle = {
    val pathParameters = SingleRunPathParameters(
      wdlPath = DefaultPathBuilder.get(args.head).toAbsolutePath,
      inputsPath = argPath(args, 1, Option("inputs"), checkDefaultExists = false),
      optionsPath = argPath(args, 2, Option("options")),
      metadataPath = argPath(args, 3, None),
      importPath = argPath(args, 4, None),
      labelsPath = argPath(args, 5, None)
    )

    val workflowSource = readContent("Workflow source", pathParameters.wdlPath)
    val inputsJson = readJson("Workflow inputs", pathParameters.inputsPath)
    val optionsJson = readJson("Workflow options", pathParameters.optionsPath)
    val labelsJson = readJson("Labels", pathParameters.labelsPath)

    val sourceFileCollection = pathParameters.importPath match {
      case Some(p) => (workflowSource |@| inputsJson |@| optionsJson |@| labelsJson) map { (w, i, o, l) =>
        WorkflowSourceFilesWithDependenciesZip.apply(
          workflowSource = w,
          workflowType = Option("WDL"),
          workflowTypeVersion = None,
          inputsJson = i,
          workflowOptionsJson = o,
          labelsJson = l,
          importsZip = p.loadBytes)
      }
      case None => (workflowSource |@| inputsJson |@| optionsJson |@| labelsJson) map { (w, i, o, l) =>
        WorkflowSourceFilesWithoutImports.apply(
          workflowSource = w,
          workflowType = Option("WDL"),
          workflowTypeVersion = None,
          inputsJson = i,
          workflowOptionsJson = o,
          labelsJson = l
        )
      }
    }

    val runSingle: ErrorOr[RunSingle] = for {
      sources <- sourceFileCollection
      _ <- writeableMetadataPath(pathParameters.metadataPath)
    } yield RunSingle(sources, pathParameters)

    runSingle match {
      case Valid(r) => r
      case Invalid(nel) => throw new RuntimeException with MessageAggregation {
        override def exceptionContext: String = "ERROR: Unable to run Cromwell:"
        override def errorMessages: Traversable[String] = nel.toList
      }
    }
  }

  private def writeableMetadataPath(path: Option[Path]): ErrorOr[Unit] = {
    path match {
      case Some(p) if !metadataPathIsWriteable(p) => s"Unable to write to metadata directory: $p".invalidNel
      case _ => ().validNel
    }
  }

  /** Read the path to a string. */
  private def readContent(inputDescription: String, path: Path): ErrorOr[String] = {
    if (!path.exists) {
      s"$inputDescription does not exist: $path".invalidNel
    } else if (!path.isReadable) {
      s"$inputDescription is not readable: $path".invalidNel
    } else path.contentAsString.validNel
  }

  /** Read the path to a string, unless the path is None, in which case returns "{}". */
  private def readJson(inputDescription: String, pathOption: Option[Path]): ErrorOr[String] = {
    pathOption match {
      case Some(path) => readContent(inputDescription, path)
      case None => "{}".validNel
    }
  }

  private def metadataPathIsWriteable(metadataPath: Path): Boolean = {
    Try(metadataPath.createIfNotExists(createParents = true).append("")) match {
      case Success(_) => true
      case Failure(_) => false
    }
  }

  /**
    * Retrieve the arg at index as path, or return some default. Args specified as "-" will be returned as None.
    *
    * @param args The run command arguments, with the wdl path at arg.head.
    * @param index The index of the path we're looking for.
    * @param defaultExt The default extension to use if the argument was not specified at all.
    * @param checkDefaultExists If true, verify that our computed default file exists before using it.
    * @return The argument as a Path resolved as a sibling to the wdl path.
    */
  private def argPath(args: Seq[String], index: Int, defaultExt: Option[String],
                      checkDefaultExists: Boolean = true): Option[Path] = {

    // To return a default, swap the extension, and then maybe check if the file exists.
    def defaultPath = defaultExt
      .map(ext => DefaultPathBuilder.get(args.head).swapExt("wdl", ext))
      .filter(path => !checkDefaultExists || path.exists)
      .map(_.pathAsString)

    // Return the path for the arg index, or the default, but remove "-" paths.
    for {
      path <- args.lift(index) orElse defaultPath filterNot (_ == "-")
    } yield DefaultPathBuilder.get(path).toAbsolutePath
  }
}
