package cromwell

import java.io.File

import com.typesafe.config.ConfigFactory

object CommandLineParser extends App {

  sealed trait Command
  case object Run extends Command
  case object Server extends Command

  case class Config(command: Option[Command] = None,
                    workflowSource: Option[File] = None,
                    workflowInputs: Option[File] = None,
                    workflowOptions: Option[File] = None,
                    workflowType: Option[String] = None, // ADT this, somehow.
                    workflowTypeVersion: Option[String] = None, // maybe ADT this
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

    cmd("run").action((_, c) => c.copy(command = Option(Run))).
      text("Run a single workflow.").
      children(
        opt[File]('w', "workflow-descriptor").text("Workflow source file").action((f, c) =>
          c.copy(workflowSource = Option(f))).required(),
        opt[File]('i', "workflow-inputs").text("Workflow inputs file").action((f, c) =>
          c.copy(workflowInputs = Option(f))),
        opt[File]('o', "workflow-options").text("Workflow options file").action((f, c) =>
            c.copy(workflowOptions = Option(f))),
        opt[String]('t', "workflow-type").text("Workflow type").action((s, c) =>
          c.copy(workflowType = Option(s))),
        opt[String]('v', "workflow-type-version").text("Workflow type version").action((s, c) =>
          c.copy(workflowTypeVersion = Option(s))),
        opt[File]('l', "labels").text("Labels file").action((f, c) =>
          c.copy(labels = Option(f))),
        opt[File]('p', "imports").text("Imports file").action((f, c) =>
          c.copy(imports = Option(f))),
        opt[File]('m', "metadata-output-path").text("Metadata output path").action((f, c) =>
          c.copy(metadataOutputPath = Option(f)))
      )
  }

  // parser.parse returns Option[C]
  parser.parse(args, Config()) match {
    case Some(config) =>
      println("you have a good argument")

    case None =>
      println("seriously wtf")
  }

}
