/* Copyright 2009-2014 EPFL, Lausanne */

package leon
package purescala

import Trees._
import TypeTrees._
import TreeOps._
import Extractors._
import solvers._

class SimplifierWithPaths(sf: SolverFactory[Solver]) extends TransformerWithPC {
  type C = List[Expr]

  val initC = Nil

  val solver = SimpleSolverAPI(sf)

  protected def register(e: Expr, c: C) = e :: c

  def impliedBy(e : Expr, path : Seq[Expr]) : Boolean = try {
    solver.solveVALID(Implies(And(path), e)) match {
      case Some(true) => true
      case _ => false
    }
  } catch {
    case _ : Exception => false
  }

  def contradictedBy(e : Expr, path : Seq[Expr]) : Boolean = try {
    solver.solveVALID(Implies(And(path), Not(e))) match {
      case Some(true) => true
      case _ => false
    }
  } catch {
    case _ : Exception => false
  }

  def valid(e : Expr) : Boolean = try {
    solver.solveVALID(e) match {
      case Some(true) => true
      case _ => false 
    }
  } catch {
    case _ : Exception => false
  }

  def sat(e : Expr) : Boolean = try {
    solver.solveSAT(e) match {
      case (Some(false),_) => false
      case _ => true
    }
  } catch {
    case _ : Exception => true
  }

  protected override def rec(e: Expr, path: C) = e match {
    case IfExpr(cond, thenn, elze) =>
      super.rec(e, path) match {
        case IfExpr(BooleanLiteral(true) , t, _) => t
        case IfExpr(BooleanLiteral(false), _, e) => e
        case ite => ite
      }

    case And(es) =>
      var soFar = path
      var continue = true
      var r = And(for(e <- es if continue) yield {
        val se = rec(e, soFar)
        if(se == BooleanLiteral(false)) continue = false
        soFar = register(se, soFar)
        se
      }).copiedFrom(e)

      if (continue) {
        r
      } else {
        BooleanLiteral(false).copiedFrom(e)
      }

    case me@MatchExpr(scrut, cases) =>
      val rs = rec(scrut, path)

      var stillPossible = true
      var pcSoFar = path

      val conds = matchCasePathConditions(me, path)

      val newCases = cases.zip(conds).flatMap { case (cs, cond) =>
       if (stillPossible && sat(And(cond))) {

          if (valid(And(cond))) {
            stillPossible = false
          }

          Some((cs match {
            case SimpleCase(p, rhs) =>
              SimpleCase(p, rec(rhs, cond))
            case GuardedCase(p, g, rhs) =>
              // FIXME: This is quite a dirty hack. We just know matchCasePathConditions 
              // returns the current guard as the last element.
              // We don't include it in the path condition when we recurse into itself.
              val condWithoutGuard = try { cond.init } catch { case _ : UnsupportedOperationException => List() }
              val newGuard = rec(g, condWithoutGuard)
              if (valid(newGuard))
                SimpleCase(p, rec(rhs,cond))
              else 
                GuardedCase(p, newGuard, rec(rhs, cond))
          }).copiedFrom(cs))
        } else {
          None
        }
      }
      newCases match {
        case List() => Error("Unreachable code").copiedFrom(e)
        case List(theCase) => 
          replaceFromIDs(mapForPattern(scrut, theCase.pattern), theCase.rhs)
        case _ => MatchExpr(rs, newCases).copiedFrom(e)
      }

    case Or(es) =>
      var soFar = path
      var continue = true
      var r = Or(for(e <- es if continue) yield {
        val se = rec(e, soFar)
        if(se == BooleanLiteral(true)) continue = false
        soFar = register(Not(se), soFar)
        se
      }).copiedFrom(e)

      if (continue) {
        r
      } else {
        BooleanLiteral(true).copiedFrom(e)
      }

    case b if b.getType == BooleanType && impliedBy(b, path) =>
      BooleanLiteral(true).copiedFrom(b)

    case b if b.getType == BooleanType && contradictedBy(b, path) =>
      BooleanLiteral(false).copiedFrom(b)

    case _ =>
      super.rec(e, path)
  }
}
