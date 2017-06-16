package cromwell

import com.typesafe.config.ConfigFactory

object CommandLineParser extends App {

  sealed trait Command
  case object UnspecifiedCommand extends Command
  case object Run extends Command
  case object Server extends Command

  case class Config(command: Command = UnspecifiedCommand)
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

    help("help").text("Cromwell - Lord Protector / Workflow Execution Engine Extraordinaire")

    version("version")

    cmd("server").action((_, c) => c.copy(command = Server)).text(
      "Starts a web server on port 8000.  See the web server documentation for more details about the API endpoints.")
    
    cmd("run").action((_, c) => c.copy(command = Run)).text(
      "Run a single workflow, accepts a bunch of options which I haven't documented yet."
    )
  }

  // parser.parse returns Option[C]
  parser.parse(args, Config()) match {
    case Some(config) =>
      println("hey those are good arguments")

    case None =>
      println("seriously wtf")
  }

}
