
package leon
package evaluators

import purescala.Common._
import purescala.Expressions._
import purescala.Definitions._
import purescala.Types._

import leon.verification.VerificationContext
import leon.verification.theorem._

/** Evaluator of proof functions.
  *
  * Adds support for all "magic" functions defined in the leon.theorem library.
  */
class ProofEvaluator(ctx: VerificationContext, prog: Program)
  extends DefaultEvaluator(ctx, prog) {

  // Set of verification conditions generated during evaluations.
  private var vcs: Seq[Expr] = Seq()

  private val library = new Library(prog)
  private val encoder = new ExprEncoder(ctx)

  override protected[evaluators] def e(expr: Expr)(implicit rctx: RC, gctx: GC): Expr = expr match {
    // Invocation of prove.
    case FunctionInvocation(TypedFunDef(fd, Seq()), Seq(arg)) if (fd == library.prove.get) => {
      ctx.reporter.info("Called solve.")
      val evaluatedArg = e(arg)
      vcs = vcs :+ evaluatedArg
      super.e(FunctionInvocation(TypedFunDef(fd, Seq()), Seq(evaluatedArg)))
    }
    // Invocation of fresh.
    case FunctionInvocation(TypedFunDef(fd, Seq()), Seq(arg)) if (fd == library.fresh.get) => {
      ctx.reporter.info("Called fresh.")
      val StringLiteral(name) = e(arg)
      val freshName = FreshIdentifier(name, Untyped, true).uniqueName
      encoder.caseClass(library.Identifier, StringLiteral(freshName))
    }
    // Any other expressions.
    case _ => super.e(expr)
  }

  /** Pops the list of verification condition expressions generated. */
  def popVCExprs: Seq[Expr] = { 
    val ret = vcs
    vcs = Seq()
    ret
  }
}