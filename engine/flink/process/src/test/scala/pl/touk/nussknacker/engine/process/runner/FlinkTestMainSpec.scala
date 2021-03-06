package pl.touk.nussknacker.engine.process.runner

import java.util.Date

import argonaut.PrettyParams
import com.typesafe.config.ConfigFactory
import org.apache.flink.runtime.client.JobExecutionException
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Inside, Matchers}
import pl.touk.nussknacker.engine.api.deployment.test._
import pl.touk.nussknacker.engine.build.{EspProcessBuilder, GraphBuilder}
import pl.touk.nussknacker.engine.flink.test.FlinkTestConfiguration
import pl.touk.nussknacker.engine.marshall.ProcessMarshaller
import pl.touk.nussknacker.engine.process.ProcessTestHelpers._
import pl.touk.nussknacker.engine.util.loader.ModelClassLoader
import pl.touk.nussknacker.engine.{ClassLoaderModelData, spel}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class FlinkTestMainSpec extends FlatSpec with Matchers with Inside with BeforeAndAfterEach {

  import spel.Implicits._

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    MonitorEmptySink.clear()
    LogService.clear()
    RecordingExceptionHandler.clear()
  }

  val ProcessMarshaller = new ProcessMarshaller

  val modelData = ClassLoaderModelData(ConfigFactory.load(), ModelClassLoader.empty)
  
  it should "be able to return test results" in {
    val process =
      EspProcessBuilder
        .id("proc1")
        .exceptionHandler()
        .source("id", "input")
        .filter("filter1", "#input.value1 > 1")
        .buildSimpleVariable("v1", "variable1", "'ala'")
        .processor("proc2", "logService", "all" -> "#input.id")
        .sink("out", "#input.value1", "monitor")

    val input = SimpleRecord("0", 1, "2", new Date(3), Some(4), 5, "6")
    val input2 = SimpleRecord("0", 11, "2", new Date(3), Some(4), 5, "6")

    val results = FlinkTestMain.run(modelData, ProcessMarshaller.toJson(process, PrettyParams.spaces2),
      TestData(List("0|1|2|3|4|5|6", "0|11|2|3|4|5|6").mkString("\n")), FlinkTestConfiguration.configuration, identity)

    val nodeResults = results.nodeResults

    nodeResults("id") shouldBe List(nodeResult(0, "input" -> input), nodeResult(1, "input" -> input2))
    nodeResults("filter1") shouldBe List(nodeResult(0, "input" -> input), nodeResult(1, "input" -> input2))
    nodeResults("v1") shouldBe List(nodeResult(1, "input" -> input2))
    nodeResults("proc2") shouldBe List(nodeResult(1, "input" -> input2, "variable1" -> "ala"))
    nodeResults("out") shouldBe List(nodeResult(1, "input" -> input2, "variable1" -> "ala"))

    val invocationResults = results.invocationResults

    invocationResults("proc2") shouldBe
      List(ExpressionInvocationResult(ResultContext[Any]("proc1-id-0-1", Map("input" -> input2, "variable1" -> "ala")), "all", "0"))
    invocationResults("out") shouldBe
      List(ExpressionInvocationResult(ResultContext[Any]("proc1-id-0-1", Map("input" -> input2, "variable1" -> "ala")), "expression", 11))

    results.mockedResults("proc2") shouldBe List(MockedResult(ResultContext("proc1-id-0-1", Map()), "logService", "0-collectedDuringServiceInvocation"))
    results.mockedResults("out") shouldBe List(MockedResult(ResultContext("proc1-id-0-1", Map("input" -> input2, "variable1" -> "ala")), "monitor", "11"))
    MonitorEmptySink.invocationsCount.get() shouldBe 0
    LogService.invocationsCount.get() shouldBe 0
  }

  it should "collect results for split" in {
    val process =
      EspProcessBuilder
        .id("proc1")
        .exceptionHandler()
        .source("id", "input")
        .split("splitId1",
          GraphBuilder.sink("out1", "'123'", "monitor"),
          GraphBuilder.sink("out2", "'234'", "monitor")
        )

    val results = FlinkTestMain.run(modelData, ProcessMarshaller.toJson(process, PrettyParams.spaces2),
      TestData(List("0|1|2|3|4|5|6", "0|11|2|3|4|5|6").mkString("\n")), FlinkTestConfiguration.configuration, identity)

    results.nodeResults("splitId1") shouldBe List(nodeResult(0), nodeResult(1))
  }

  it should "return correct result for custom node" in {
    val process =
      EspProcessBuilder
        .id("proc1")
        .exceptionHandler()
        .source("id", "input")
        .customNode("cid", "out", "stateCustom", "keyBy" -> "#input.id", "stringVal" -> "'s'")
        .sink("out", "#input.value1 + ' ' + #out.previous", "monitor")

    val input = SimpleRecord("0", 1, "2", new Date(3), Some(4), 5, "6")
    val input2 = SimpleRecord("0", 11, "2", new Date(3), Some(4), 5, "6")

    val aggregate = SimpleRecordWithPreviousValue(input, 0, "s")
    val aggregate2 = SimpleRecordWithPreviousValue(input2, 1, "s")


    val results = FlinkTestMain.run(modelData, ProcessMarshaller.toJson(process, PrettyParams.spaces2),
      TestData(List("0|1|2|3|4|5|6", "0|11|2|3|4|5|6").mkString("\n")), FlinkTestConfiguration.configuration, identity)

    val nodeResults = results.nodeResults

    nodeResults("id") shouldBe List(nodeResult(0, "input" -> input), nodeResult(1, "input" -> input2))

    val resultsAfterCid = List(
      nodeResult(0, "input" -> input, "out" -> aggregate),
      nodeResult(1, "input" -> input2, "out" -> aggregate2))

    nodeResults("cid") shouldBe resultsAfterCid
    nodeResults("out") shouldBe resultsAfterCid

    val invocationResults = results.invocationResults

    invocationResults("cid") shouldBe
      List(
        ExpressionInvocationResult(ResultContext("", Map()), "stringVal", "s"),
        ExpressionInvocationResult(ResultContext("proc1-id-0-0", Map("input" -> input)), "keyBy", "0"),
        ExpressionInvocationResult(ResultContext("proc1-id-0-1", Map("input" -> input2)), "keyBy", "0")
      )
    invocationResults("out") shouldBe
      List(
        ExpressionInvocationResult(ResultContext("proc1-id-0-0", Map("input" -> input, "out" -> aggregate)), "expression", "1 0"),
        ExpressionInvocationResult(ResultContext("proc1-id-0-1", Map("input" -> input2, "out" -> aggregate2)), "expression", "11 1")
      )

    results.mockedResults("out") shouldBe
      List(
        MockedResult(ResultContext("proc1-id-0-0", Map("input" -> input, "out" -> aggregate)), "monitor", "1 0"),
        MockedResult(ResultContext("proc1-id-0-1", Map("input" -> input2, "out" -> aggregate2)), "monitor", "11 1")
      )

  }

  it should "handle large parallelism" in {
    val process =
      EspProcessBuilder
        .id("proc1")
        .parallelism(4)
        .exceptionHandler()
        .source("id", "input")
        .sink("out", "#input", "monitor")

    val results = FlinkTestMain.run(modelData, ProcessMarshaller.toJson(process, PrettyParams.spaces2),
      TestData(List("0|1|2|3|4|5|6", "0|11|2|3|4|5|6", "0|11|2|3|4|5|6", "0|11|2|3|4|5|6", "0|11|2|3|4|5|6").mkString("\n")), FlinkTestConfiguration.configuration, identity)

    val nodeResults = results.nodeResults

    nodeResults("id") should have length 5

  }

  it should "detect errors" in {
    val process =
      EspProcessBuilder
        .id("proc1")
        .exceptionHandler()
        .source("id", "input")
        .processor("failing", "throwingService", "throw" -> "#input.value1 == 2")
        .filter("filter", "1 / #input.value1 >= 0")
        .sink("out", "#input", "monitor")

    val results = FlinkTestMain.run(modelData, ProcessMarshaller.toJson(process, PrettyParams.spaces2),
      TestData(List("0|1|2|3|4|5|6", "1|0|2|3|4|5|6", "2|2|2|3|4|5|6", "3|4|2|3|4|5|6").mkString("\n")), FlinkTestConfiguration.configuration, identity)

    val nodeResults = results.nodeResults

    nodeResults("id") should have length 4
    nodeResults("out") should have length 2

    results.exceptions should have length 2

    val exceptionFromExpression = results.exceptions.head
    exceptionFromExpression.nodeId shouldBe Some("filter")
    exceptionFromExpression.context.variables("input").asInstanceOf[SimpleRecord].id shouldBe "1"
    exceptionFromExpression.throwable.getMessage shouldBe "/ by zero"

    val exceptionFromService = results.exceptions.last
    exceptionFromService.nodeId shouldBe Some("failing")
    exceptionFromService.context.variables("input").asInstanceOf[SimpleRecord].id shouldBe "2"
    exceptionFromService.throwable.getMessage shouldBe "Thrown as expected"
  }

  it should "ignore real exception handler" in {
    val process =
      EspProcessBuilder
        .id("proc1")
        .exceptionHandler()
        .source("id", "input")
        .processor("failing", "throwingService", "throw" -> "#input.value1 == 2")
        .filter("filter", "1 / #input.value1 >= 0")
        .sink("out", "#input", "monitor")

    val results = FlinkTestMain.run(modelData, ProcessMarshaller.toJson(process, PrettyParams.spaces2),
      TestData(List("0|1|2|3|4|5|6", "1|0|2|3|4|5|6", "2|2|2|3|4|5|6", "3|4|2|3|4|5|6").mkString("\n")), FlinkTestConfiguration.configuration, identity)

    val nodeResults = results.nodeResults

    nodeResults("id") should have length 4
    nodeResults("out") should have length 2

    results.exceptions should have length 2
    RecordingExceptionHandler.data shouldBe 'empty
  }

  it should "handle transient errors" in {
    val process =
      EspProcessBuilder
        .id("proc1")
        .exceptionHandler()
        .source("id", "input")
        .processor("failing", "throwingTransientService", "throw" -> "#input.value1 == 2")
        .sink("out", "#input", "monitor")

    val run = Future {
      FlinkTestMain.run(modelData, ProcessMarshaller.toJson(process, PrettyParams.spaces2),
        TestData(List("2|2|2|3|4|5|6").mkString("\n")), FlinkTestConfiguration.configuration, identity)
    }

    intercept[JobExecutionException](Await.result(run, 5 seconds))


  }

  it should "handle custom multiline source input" in {
    val process =
      EspProcessBuilder
        .id("proc1")
        .exceptionHandler()
        .source("id", "jsonInput")
        .sink("out", "#input", "monitor")
    val testJsonData = TestData(
      """{
        | "id": "1",
        | "field": "11"
        |}
        |
        |
        |{
        | "id": "2",
        | "field": "22"
        |}
        |
        |{
        | "id": "3",
        | "field": "33"
        |}
        |""".stripMargin)

    val results = FlinkTestMain.run(modelData, ProcessMarshaller.toJson(process, PrettyParams.spaces2),
      testJsonData, FlinkTestConfiguration.configuration, identity)

    results.nodeResults("id") should have size 3
    results.mockedResults("out") shouldBe
      List(
        MockedResult(ResultContext[Any]("proc1-id-0-0", Map("input" -> SimpleJsonRecord("1", "11"))), "monitor", "SimpleJsonRecord(1,11)"),
        MockedResult(ResultContext[Any]("proc1-id-0-1", Map("input" -> SimpleJsonRecord("2", "22"))), "monitor", "SimpleJsonRecord(2,22)"),
        MockedResult(ResultContext[Any]("proc1-id-0-2", Map("input" -> SimpleJsonRecord("3", "33"))), "monitor", "SimpleJsonRecord(3,33)")
      )
  }

  it should "give meaningful error messages for sink errors" in {
    val process =
      EspProcessBuilder
        .id("proc1")
        .exceptionHandler()
        .source("id", "input")
        .sink("out", "#input", "sinkForInts")

    val run = Future {
      FlinkTestMain.run(modelData, ProcessMarshaller.toJson(process, PrettyParams.spaces2),
        TestData("2|2|2|3|4|5|6"), FlinkTestConfiguration.configuration, identity)
    }

    val results = Await.result(run, 5 seconds)

    results.exceptions should have length 1
    results.exceptions.head.nodeId shouldBe Some("out")
    results.exceptions.head.throwable.getMessage should include ("For input string: ")

    SinkForInts.data should have length 0
  }

  it should "be able to test process with signals" in {
    val process =
      EspProcessBuilder
        .id("proc1")
        .exceptionHandler()
        .source("id", "input")
        .customNodeNoOutput("cid", "signalReader")
        .sink("out", "#input.value1", "monitor")

    val input = SimpleRecord("0", 1, "2", new Date(3), Some(4), 5, "6")


    val results = FlinkTestMain.run(modelData, ProcessMarshaller.toJson(process, PrettyParams.spaces2),
      TestData(List("0|1|2|3|4|5|6").mkString("\n")), FlinkTestConfiguration.configuration, identity)

    val nodeResults = results.nodeResults

    nodeResults("out") shouldBe List(nodeResult(0, "input" -> input))

  }

  it should "be able to test process with time windows" in {
    val process =
      EspProcessBuilder
        .id("proc1")
        .exceptionHandler()
        .source("id", "input")
        .customNode("cid", "count", "transformWithTime", "seconds" -> "10")
        .sink("out", "#count", "monitor")

    def recordWithSeconds(duration: FiniteDuration) = s"0|0|0|${duration.toMillis}|0|0|0"

    val results = FlinkTestMain.run(modelData, ProcessMarshaller.toJson(process, PrettyParams.spaces2),
      TestData(List(
        recordWithSeconds(1 second),
        recordWithSeconds(2 second),
        recordWithSeconds(5 second),
        recordWithSeconds(9 second),
        recordWithSeconds(20 second)
      ).mkString("\n")), FlinkTestConfiguration.configuration, identity)

    val nodeResults = results.nodeResults

    nodeResults("out").map(_.context.variables) shouldBe List(Map("count" -> 4), Map("count" -> 1))

  }

  def nodeResult(count: Int, vars: (String, Any)*) 
    = NodeResult(ResultContext[Any](s"proc1-id-0-$count", Map(vars: _*)))

}

