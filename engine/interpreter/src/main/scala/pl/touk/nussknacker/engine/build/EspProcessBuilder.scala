package pl.touk.nussknacker.engine.build

import pl.touk.nussknacker.engine.api.{MetaData, StandaloneMetaData, StreamMetaData}
import pl.touk.nussknacker.engine.build.GraphBuilder.Creator
import pl.touk.nussknacker.engine.graph.exceptionhandler.ExceptionHandlerRef
import pl.touk.nussknacker.engine.graph.expression.Expression
import pl.touk.nussknacker.engine.graph.{EspProcess, evaluatedparam}

class ProcessMetaDataBuilder private[build](metaData: MetaData) {

  //TODO: exception when non-streaming process?
  def parallelism(p: Int) =
   new ProcessMetaDataBuilder(metaData.copy(typeSpecificData = StreamMetaData(Some(p))))

  //TODO: exception when non-standalone process?
  def path(p: Option[String]) =
   new ProcessMetaDataBuilder(metaData.copy(typeSpecificData = StandaloneMetaData(p)))

  def subprocessVersions(subprocessVersions: Map[String, Long]) =
   new ProcessMetaDataBuilder(metaData.copy(subprocessVersions = subprocessVersions))


  def exceptionHandler(params: (String, Expression)*) =
    new ProcessExceptionHandlerBuilder(ExceptionHandlerRef(params.map(evaluatedparam.Parameter.tupled).toList))

  class ProcessExceptionHandlerBuilder private[ProcessMetaDataBuilder](exceptionHandlerRef: ExceptionHandlerRef) {

    def source(id: String, typ: String, params: (String, Expression)*): ProcessGraphBuilder =
      new ProcessGraphBuilder(GraphBuilder.source(id, typ, params: _*).creator
          .andThen(EspProcess(metaData, exceptionHandlerRef, _)))

    class ProcessGraphBuilder private[ProcessExceptionHandlerBuilder](val creator: Creator[EspProcess])
      extends GraphBuilder[EspProcess] {

      override def build(inner: Creator[EspProcess]) = new ProcessGraphBuilder(inner)
    }

  }

}

object EspProcessBuilder {

  def id(id: String) =
    new ProcessMetaDataBuilder(MetaData(id, StreamMetaData()))

}

object StandaloneProcessBuilder {

  def id(id: String) =
    new ProcessMetaDataBuilder(MetaData(id, StandaloneMetaData(None)))

}