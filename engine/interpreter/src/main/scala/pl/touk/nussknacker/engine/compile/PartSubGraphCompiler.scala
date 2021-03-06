package pl.touk.nussknacker.engine.compile

import cats.data.Validated._
import cats.data.{NonEmptyList, ValidatedNel}
import cats.instances.list._
import cats.instances.option._
import pl.touk.nussknacker.engine.api.typed.ClazzRef
import pl.touk.nussknacker.engine.api.typed.typing.{Typed, TypedMapTypingResult, TypingResult, Unknown}
import pl.touk.nussknacker.engine.compile.PartSubGraphCompilerBase.{CompiledNode, ContextsForParts, NextWithContext}
import pl.touk.nussknacker.engine.compile.ProcessCompilationError._
import pl.touk.nussknacker.engine.compile.dumb._
import pl.touk.nussknacker.engine.compiledgraph.node.SubprocessEnd
import pl.touk.nussknacker.engine.definition.DefinitionExtractor._
import pl.touk.nussknacker.engine.definition._
import pl.touk.nussknacker.engine.graph.node._
import pl.touk.nussknacker.engine.splittedgraph._
import pl.touk.nussknacker.engine.splittedgraph.splittednode.SplittedNode
import pl.touk.nussknacker.engine.{compiledgraph, _}

import scala.util.Right

class PartSubGraphCompiler(protected val classLoader: ClassLoader,
                           protected val expressionCompiler: ExpressionCompiler,
                           protected val services: Map[String, ObjectWithMethodDef]) extends PartSubGraphCompilerBase {

  override type ParametersProviderT = ObjectWithMethodDef

  override protected def createServiceInvoker(obj: ObjectWithMethodDef) =
    ServiceInvoker(obj)

}

class PartSubGraphValidator(protected val classLoader: ClassLoader,
                            protected val expressionCompiler: ExpressionCompiler,
                            protected val services: Map[String, ObjectDefinition]) extends PartSubGraphCompilerBase {

  override type ParametersProviderT = ObjectDefinition

  override protected def createServiceInvoker(obj: ObjectDefinition) =
    DumbServiceInvoker

}

private[compile] trait PartSubGraphCompilerBase {

  type ParametersProviderT <: ObjectMetadata
  private val syntax = ValidatedSyntax[PartSubGraphCompilationError]

  def validate(n: splittednode.SplittedNode[_], ctx: ValidationContext): ValidatedNel[PartSubGraphCompilationError, ContextsForParts] = {
    doCompile(n, ctx).map(_.ctx)
  }

  def compile(n: splittednode.SplittedNode[_], ctx: ValidationContext): ValidatedNel[PartSubGraphCompilationError, CompiledNode] = {
    doCompile(n, ctx)
  }

  import syntax._

  protected def expressionCompiler: ExpressionCompiler

  protected def services: Map[String, ParametersProviderT]

  protected def classLoader: ClassLoader

  protected def createServiceInvoker(obj: ParametersProviderT): ServiceInvoker

  //TODO: make it simpler
  //this code compiles single Part (which will be single Flink node)
  //it returns info about next steps (PartRef) and variables withing each part
    type ValidatedWithCompiler[A] = ValidatedNel[PartSubGraphCompilationError, A]

    def doCompile(n: SplittedNode[_], ctx: ValidationContext): ValidatedNel[PartSubGraphCompilationError, CompiledNode] = {
      implicit val nodeId = NodeId(n.id)
      n match {
        case splittednode.SourceNode(graph.node.Source(id, _, _), next) =>
          compile(next, ctx).map(nwc => CompiledNode(compiledgraph.node.Source(id, nwc.next), nwc.ctx))
        case splittednode.SourceNode(SubprocessInputDefinition(id, _, _), next) =>
          //TODO: should we recognize we're compiling only subprocess?
          compile(next, ctx).map(nwc => CompiledNode(compiledgraph.node.Source(id, nwc.next), nwc.ctx))
        case splittednode.OneOutputSubsequentNode(data: OneOutputSubsequentNodeData, next) =>
          data match {
            case graph.node.Variable(id, varName, expression, _) =>
              compile(expression, None, ctx, ClazzRef[Any]).andThen { case (typingResult, compiled) =>
                ctx.withVariable(varName, typingResult)
                   .andThen(compile(next, _))
                   .map { compiledNext =>
                      CompiledNode(compiledgraph.node.VariableBuilder(id, varName, Left(compiled), compiledNext.next), compiledNext.ctx)
                  }
              }
            case graph.node.VariableBuilder(id, varName, fields, _) =>
              fields.map(f => compile(f, ctx)).sequence.andThen { compiledFields =>
                val variableType = TypedMapTypingResult(compiledFields.map(res => (res._1.name, res._2)).toMap)
                ctx.withVariable(varName, variableType).andThen { newCtx =>
                  compile(next, newCtx).map(nextWithCtx =>
                    CompiledNode(compiledgraph.node.VariableBuilder(id, varName, Right(compiledFields.map(_._1)), nextWithCtx.next), nextWithCtx.ctx))
                }
              }

            case graph.node.Processor(id, ref, isDisabled, _) =>
              A.map2(compile(ref, ctx), compile(next, ctx))((ref, nextWithCtx) =>
                CompiledNode(compiledgraph.node.Processor(id, ref, nextWithCtx.next, isDisabled.contains(true)), nextWithCtx.ctx))
            case graph.node.Enricher(id, ref, outName, _) =>
              services.get(ref.id).map { definition =>
                ctx.withVariable(outName, definition.returnType)
              }.getOrElse(Valid(ctx)).andThen { newCtx =>
                A.map2(compile(ref, newCtx), compile(next, newCtx))((ref, nextWithCtx) =>
                  CompiledNode(compiledgraph.node.Enricher(id, ref, outName, nextWithCtx.next), nextWithCtx.ctx))
              }

            //we don't put variable in context here, as it's handled in flink currently (maybe try to change it?)
            case graph.node.CustomNode(id, _, customNodeRef, parameters, _) =>
              //TODO: so far we assume we don't reset context, we get output var from outside
              //TODO: we don't do parameter context validation, because custom node can add any vars...
              val validParams = expressionCompiler.compileObjectParameters(parameters.map(p => Parameter(p.name, ClazzRef[Any])), parameters, None)

              A.map2(validParams, compile(next, ctx))((params, nextWithCtx) =>
                CompiledNode(compiledgraph.node.CustomNode(id, params, nextWithCtx.next), nextWithCtx.ctx))
            case SubprocessInput(id, ref, _, _) =>
              ref.parameters.foldLeft(Valid(ctx.pushNewContext()).asInstanceOf[ValidatedNel[PartSubGraphCompilationError, ValidationContext]])
                { case (accCtx, param) => accCtx.andThen(_.withVariable(param.name, Unknown))}.andThen { ctxWithVars =>
                  //TODO: [TYPER] checking types of input variables
                  val validParams =
                    expressionCompiler.compileObjectParameters(ref.parameters.map(p => Parameter(p.name, ClazzRef[Any])), ref.parameters, toOption(ctx))

                  A.map2(validParams, compile(next, ctxWithVars))((params, nextWithCtx) =>
                    CompiledNode(compiledgraph.node.SubprocessStart(id, params, nextWithCtx.next), nextWithCtx.ctx))
                  }
            case SubprocessOutput(id, _, _) =>
              ctx.popContext.andThen { popContext =>
                compile(next, popContext).map { nextWithCtx =>
                  CompiledNode(SubprocessEnd(id, nextWithCtx.next), nextWithCtx.ctx)
                }
              }
          }
        case splittednode.SplitNode(bareNode, nexts) =>
          nexts.map(n => compile(n.next, ctx)).sequence.map { parts =>
            CompiledNode(compiledgraph.node.SplitNode(bareNode.id), parts.flatMap(_.ctx).toMap)
          }
        case splittednode.FilterNode(f@graph.node.Filter(id, expression, _, _), nextTrue, nextFalse) =>
          A.map3(compile(expression, None, ctx, ClazzRef[Boolean]), compile(nextTrue, ctx), nextFalse.map(next => compile(next, ctx)).sequence)(
            (expr, nextWithCtx, nextWithCtxFalse) => CompiledNode(
              compiledgraph.node.Filter(id = id,
              expression = expr._2,
              nextTrue = nextWithCtx.next,
              nextFalse = nextWithCtxFalse.map(_.next),
              isDisabled = f.isDisabled.contains(true)),
              nextWithCtx.ctx ++ nextWithCtxFalse.map(_.ctx).getOrElse(Map())))
        case splittednode.SwitchNode(graph.node.Switch(id, expression, exprVal, _), nexts, defaultNext) =>
          compile(expression, None, ctx, ClazzRef[Any]).andThen { case (typingResult, compiledExpression) =>
            ctx.withVariable(exprVal, typingResult).andThen { newCtx =>
              A.map2(nexts.map(n => compile(n, newCtx)).sequence, defaultNext.map(dn => compile(dn, newCtx)).sequence)(
                (cases, nextWithCtx) => {
                  val defaultCtx = nextWithCtx.map(_.ctx).getOrElse(Map())
                  CompiledNode(compiledgraph.node.Switch(id, compiledExpression, exprVal, cases.unzip._1, nextWithCtx.map(_.next)),
                    cases.unzip._2.fold(defaultCtx)(_ ++ _))
                })
            }

          }

        case splittednode.EndingNode(data: EndingNodeData) =>
          //we give empty maps in CompiledNode, there shouldn't be anything here anyway
          data match {
            case graph.node.Processor(id, ref, disabled, _) =>
              compile(ref, ctx).map(compiledgraph.node.EndingProcessor(id, _, disabled.contains(true))).map(CompiledNode(_, Map()))
            case graph.node.Sink(id, ref, optionalExpression, _) =>
              optionalExpression.map(oe => compile(oe, None, ctx, ClazzRef[Any])).sequence.map(typed => compiledgraph.node.Sink(id, ref.typ, typed.map(_._2))).map(CompiledNode(_, Map()))
            //probably this shouldn't occur - otherwise we'd have empty subprocess?
            case SubprocessInput(id, _, _, _) => Invalid(NonEmptyList.of(UnresolvedSubprocess(id)))
            case SubprocessOutputDefinition(id, name, _) =>
              //TODO: should we validate it's process?
              Valid(CompiledNode(compiledgraph.node.Sink(id, name, None), Map()))
          }
      }
    }

    private def compile(next: splittednode.Next, ctx: ValidationContext): ValidatedNel[PartSubGraphCompilationError, NextWithContext] = {
      next match {
        case splittednode.NextNode(n) => doCompile(n, ctx).map(cn => NextWithContext(compiledgraph.node.NextNode(cn.node), cn.ctx))
        case splittednode.PartRef(ref) => valid(NextWithContext(compiledgraph.node.PartRef(ref), Map(ref -> ctx)))
      }
    }

    private def compile(n: graph.service.ServiceRef, ctx: ValidationContext)
                       (implicit nodeId: NodeId): ValidatedNel[PartSubGraphCompilationError, compiledgraph.service.ServiceRef] = {
      val service = services.get(n.id).map(valid).getOrElse(invalid(MissingService(n.id))).toValidatedNel

      service.andThen { obj =>
        expressionCompiler.compileObjectParameters(obj.parameters, n.parameters, toOption(ctx)).map { params =>
            val invoker = createServiceInvoker(obj)
            compiledgraph.service.ServiceRef(n.id, invoker, params)
        }
      }
    }


    private def compile(n: splittednode.Case, ctx: ValidationContext)
                       (implicit nodeId: NodeId): ValidatedNel[PartSubGraphCompilationError, (compiledgraph.node.Case, ContextsForParts)] =
      A.map2(compile(n.expression, None, ctx, ClazzRef[Boolean]), compile(n.node, ctx))((expr, nextWithCtx) => (compiledgraph.node.Case(expr._2, nextWithCtx.next), nextWithCtx.ctx))

    private def compile(n: graph.variable.Field, ctx: ValidationContext)
                       (implicit nodeId: NodeId): ValidatedNel[PartSubGraphCompilationError, (compiledgraph.variable.Field, TypingResult)] =
      compile(n.expression, Some(n.name), ctx, ClazzRef[Any])
        .map(typed => (compiledgraph.variable.Field(n.name, typed._2), typed._1))

    private def compile(n: graph.expression.Expression,
                        fieldName: Option[String],
                        ctx: ValidationContext,
                        expectedType: ClazzRef)
                       (implicit nodeId: NodeId): ValidatedNel[PartSubGraphCompilationError, (TypingResult, compiledgraph.expression.Expression)] = {
      expressionCompiler.compile(n, fieldName, toOption(ctx), expectedType)
    }

    private def toOption(ctx: ValidationContext) = Some(ctx)


}

object PartSubGraphCompilerBase {

  type PartId = String

  type ContextsForParts = Map[PartId, ValidationContext]

  case class CompiledNode(node: compiledgraph.node.Node, ctx: ContextsForParts)

  case class NextWithContext(next: compiledgraph.node.Next, ctx: ContextsForParts)

}