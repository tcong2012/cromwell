package cromwell.engine.workflow.lifecycle.execution

import cromwell.backend.BackendJobDescriptorKey
import cromwell.core.ExecutionStatus.{apply => _, _}
import cromwell.core.{ExecutionStatus, JobKey}
import cromwell.util.SampleWdl
import org.scalameter.api._
import wdl4s.{TaskCall, WdlNamespaceWithWorkflow}
import org.scalameter.picklers.Implicits._

object ExecutionStoreBenchmark extends Bench[Double] {

  /* Benchmark configuration */
  lazy val measurer = new Measurer.Default
  lazy val executor = SeparateJvmsExecutor(new Executor.Warmer.Default, Aggregator.average, measurer)
  lazy val reporter = new LoggingReporter[Double]
  lazy val persistor = Persistor.None
  
  val wdl = WdlNamespaceWithWorkflow.load(SampleWdl.PrepareScatterGatherWdl().wdlSource(), Seq.empty).get
  val call: TaskCall = wdl.workflow.findCallByName("do_prepare").get.asInstanceOf[TaskCall]
  val call2: TaskCall = wdl.workflow.findCallByName("do_scatter").get.asInstanceOf[TaskCall]
  
  def makeKey(call: TaskCall, executionStatus: ExecutionStatus)(index: Int) = {
    BackendJobDescriptorKey(call, Option(index), 1) -> executionStatus
  }
  
  val sizes: Gen[Int] = Gen.range("size")(1000, 10000, 1000)
  
  val executionStores: Gen[ExecutionStore] = for {
    size <- sizes
    doneMap = (0 until size map makeKey(call, ExecutionStatus.Done)).toMap
    notStartedMap = (0 until size map makeKey(call2, ExecutionStatus.NotStarted)).toMap
    finalMap: Map[JobKey, ExecutionStatus] = doneMap ++ notStartedMap
  } yield new ExecutionStore(finalMap, true)
  
  performance of "ExecutionStore" in {
    measure method "runnableCalls" in {
      using(executionStores) in { es =>
        es.runnableScopes
      }
    }
  }
}
