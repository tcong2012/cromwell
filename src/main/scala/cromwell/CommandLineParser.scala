package cromwell

import java.io.File

import com.typesafe.config.ConfigFactory
import cromwell.server.CromwellServer

object CommandLineParser extends App {

  sealed trait Command

  case object Run extends Command

  case object Server extends Command

  case class Config(command: Option[Command] = None,
                    workflowSource: Option[File] = None,
                    workflowInputs: Option[File] = None,
                    workflowOptions: Option[File] = None,
                    workflowType: Option[String] = Option("WDL"), // ADT this, somehow.
                    workflowTypeVersion: Option[String] = Option("v2.0-draft"), // maybe ADT this
                    labels: Option[File] = None,
                    imports: Option[File] = None,
                    metadataOutputPath: Option[File] = None
                   )

  lazy val cromwellVersion = ConfigFactory.load("cromwell-version.conf").getConfig("version").getString("cromwell")

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
  val parser = new scopt.OptionParser[Config]("cromwell") {
    head("cromwell", cromwellVersion)

    help("help").text("Cromwell - Lord Protector / Workflow Execution Engine")

    version("version")

    cmd("server").action((_, c) => c.copy(command = Option(Server))).text(
      "Starts a web server on port 8000.  See the web server documentation for more details about the API endpoints.")

    cmd("run").
      action((_, c) => c.copy(command = Option(Run))).
      text("Run the workflow locally and print out the outputs in JSON format.").
      children(
        opt[File]('w', "workflow-descriptor").text("Workflow source file.").
          action((f, c) =>
            c.copy(workflowSource = Option(f))).required(),
        opt[File]('i', "workflow-inputs").text("Workflow inputs file.").
          action((f, c) =>
            c.copy(workflowInputs = Option(f))),
        opt[File]('o', "workflow-options").text("Workflow options file.").
          action((f, c) =>
            c.copy(workflowOptions = Option(f))),
        opt[String]('t', "workflow-type").text("Workflow type.").
          action((s, c) =>
            c.copy(workflowType = Option(s))),
        opt[String]('v', "workflow-type-version").text("Workflow type version.").
          action((s, c) =>
            c.copy(workflowTypeVersion = Option(s))),
        opt[File]('l', "labels").text("Labels file.").
          action((f, c) =>
            c.copy(labels = Option(f))),
        opt[File]('p', "imports").text(
          "A directory to search for WDL file imports, required if the primary workflow imports workflows that are outside of the root directory of the Cromwell project.").
          action((f, c) =>
            c.copy(imports = Option(f))),
        opt[File]('m', "metadata-output-path").text(
          "An optional file path to output metadata.").
          action((f, c) =>
            c.copy(metadataOutputPath = Option(f)))
      )
  }

  // parser.parse returns Option[C].  If this is `None` the default behavior should be to print help text, which is what we want.
  parser.parse(args, Config()) foreach {
    config =>
      config.command match {
        case Some(cmd) =>
          CromwellCommandLine.initLogging(cmd)
          val cromwellSystem = CromwellCommandLine.buildCromwellSystem
          cmd match {
            case Run =>
            case Server => CromwellCommandLine.waitAndExit(CromwellServer.run, cromwellSystem)
          }
        case None => // a cry for help
      }
  }
}
