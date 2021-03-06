package pl.touk.nussknacker.engine.process.compiler

import cats.data.Validated.{Invalid, Valid}
import cats.data.ValidatedNel
import com.typesafe.config.Config
import pl.touk.nussknacker.engine.api.ProcessListener
import pl.touk.nussknacker.engine.api.exception.EspExceptionInfo
import pl.touk.nussknacker.engine.api.process.ProcessConfigCreator
import pl.touk.nussknacker.engine.compile._
import pl.touk.nussknacker.engine.definition.DefinitionExtractor.ObjectWithMethodDef
import pl.touk.nussknacker.engine.definition.ProcessDefinitionExtractor
import pl.touk.nussknacker.engine.definition.ProcessDefinitionExtractor.ProcessDefinition
import pl.touk.nussknacker.engine.flink.api.exception.{DelegatingFlinkEspExceptionHandler, FlinkEspExceptionHandler}
import pl.touk.nussknacker.engine.flink.api.process.{FlinkProcessSignalSenderProvider, SignalSenderKey}
import pl.touk.nussknacker.engine.flink.api.signal.FlinkProcessSignalSender
import pl.touk.nussknacker.engine.flink.util.async.DefaultAsyncExecutionConfigPreparer
import pl.touk.nussknacker.engine.flink.util.listener.NodeCountMetricListener
import pl.touk.nussknacker.engine.graph.EspProcess
import pl.touk.nussknacker.engine.process.FlinkProcessRegistrar
import pl.touk.nussknacker.engine.util.LoggingListener

import scala.concurrent.duration.FiniteDuration

abstract class FlinkProcessCompiler(creator: ProcessConfigCreator, config: Config) extends Serializable {

  import net.ceedubs.ficus.Ficus._
  import net.ceedubs.ficus.readers.ArbitraryTypeReader._
  import pl.touk.nussknacker.engine.util.Implicits._

  def compileProcess(process: EspProcess)(userCodeClassLoader: ClassLoader): CompiledProcessWithDeps = {

    //TODO: this should be somewhere else?
    val timeout = config.as[FiniteDuration]("timeout")

    //TODO: should this be the default?
    val asyncExecutionContextPreparer = creator.asyncExecutionContextPreparer(config).getOrElse(
      config.as[DefaultAsyncExecutionConfigPreparer]("asyncExecutionConfig")
    )

    val listenersToUse = listeners()

    val compiledProcess = validateOrFailProcessCompilation(
      CompiledProcess.compile(process, definitions(), listenersToUse, userCodeClassLoader, timeout))

    val listeningExceptionHandler = new ListeningExceptionHandler(listenersToUse,
      //FIXME: remove casting...
      compiledProcess.parts.exceptionHandler.asInstanceOf[FlinkEspExceptionHandler])

    new CompiledProcessWithDeps(
      compiledProcess,
      listeningExceptionHandler,
      new FlinkProcessSignalSenderProvider(signalSenders),
      asyncExecutionContextPreparer,
      timeout
    )
  }

  private def validateOrFailProcessCompilation[T](validated: ValidatedNel[ProcessCompilationError, T]): T = validated match {
    case Valid(r) => r
    case Invalid(err) => throw new scala.IllegalArgumentException(err.toList.mkString("Compilation errors: ", ", ", ""))
  }

  protected def definitions(): ProcessDefinition[ObjectWithMethodDef] = {
    ProcessDefinitionExtractor.extractObjectWithMethods(creator, config)
  }

  protected def listeners(): Seq[ProcessListener] = {
    //TODO: should this be configurable somehow?
    //if it's configurable, it also has to affect NodeCountMetricFunction!
    List(LoggingListener, new NodeCountMetricListener) ++ creator.listeners(config)
  }

  protected def signalSenders: Map[SignalSenderKey, FlinkProcessSignalSender]
    = definitions().signalsWithTransformers.mapValuesNow(_._1.as[FlinkProcessSignalSender])
      .map { case (k, v) => SignalSenderKey(k, v.getClass) -> v }

  def createFlinkProcessRegistrar() = FlinkProcessRegistrar(this, config)

  //TODO: consider moving to CompiledProcess??
  private class ListeningExceptionHandler(listeners: Seq[ProcessListener], exceptionHandler: FlinkEspExceptionHandler)
    extends DelegatingFlinkEspExceptionHandler(exceptionHandler) {

    override def handle(exceptionInfo: EspExceptionInfo[_ <: Throwable]): Unit = {
      listeners.foreach(_.exceptionThrown(exceptionInfo))
      delegate.handle(exceptionInfo)
    }
  }
}

class StandardFlinkProcessCompiler(creator: ProcessConfigCreator, config: Config)
  extends FlinkProcessCompiler(creator, config)
