package cromwell

import com.typesafe.config.ConfigFactory
import cromwell.core.path.{DefaultPathBuilder, Path}
import cromwell.server.CromwellServer

object CommandLineParser extends App {

  sealed trait Command

  case object Run extends Command

  case object Server extends Command

  case class Config(command: Option[Command] = None,
                    workflowSource: Option[Path] = None,
                    workflowInputs: Option[Path] = None,
                    workflowOptions: Option[Path] = None,
                    workflowType: Option[String] = Option("WDL"), // ADT this, somehow.
                    workflowTypeVersion: Option[String] = Option("v2.0-draft"), // maybe ADT this
                    labels: Option[Path] = None,
                    imports: Option[Path] = None,
                    metadataOutputPath: Option[Path] = None
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
        opt[String]('w', "workflow-descriptor").text("Workflow source file.").
          action((s, c) =>
            c.copy(workflowSource = Option(DefaultPathBuilder.get(s)))).required(),
        opt[String]('i', "workflow-inputs").text("Workflow inputs file.").
          action((s, c) =>
            c.copy(workflowInputs = Option(DefaultPathBuilder.get(s)))),
        opt[String]('o', "workflow-options").text("Workflow options file.").
          action((s, c) =>
            c.copy(workflowOptions = Option(DefaultPathBuilder.get(s)))),
        opt[String]('t', "workflow-type").text("Workflow type.").
          action((s, c) =>
            c.copy(workflowType = Option(s))),
        opt[String]('v', "workflow-type-version").text("Workflow type version.").
          action((s, c) =>
            c.copy(workflowTypeVersion = Option(s))),
        opt[String]('l', "labels").text("Labels file.").
          action((s, c) =>
            c.copy(labels = Option(DefaultPathBuilder.get(s)))),
        opt[String]('p', "imports").text(
          "A directory to search for WDL file imports, required if the primary workflow imports workflows that are outside of the root directory of the Cromwell project.").
          action((s, c) =>
            c.copy(imports = Option(DefaultPathBuilder.get(s)))),
        opt[String]('m', "metadata-output-path").text(
          "An optional file path to output metadata.").
          action((s, c) =>
            c.copy(metadataOutputPath = Option(DefaultPathBuilder.get(s))))
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
              val runSingle = RunSingle.apply(config)
              CromwellCommandLine.runWorkflow(runSingle, cromwellSystem)
            case Server => CromwellCommandLine.waitAndExit(CromwellServer.run, cromwellSystem)
          }
        case None => // a cry for help
      }
  }
}
