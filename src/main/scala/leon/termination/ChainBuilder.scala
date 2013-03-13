package leon
package termination

import leon.purescala.Definitions._
import leon.purescala.Trees._
import leon.purescala.TreeOps._
import leon.purescala.Common._

final case class Chain(chain: List[Relation]) {
  def funDef  : FunDef                    = chain.head.funDef
  def funDefs : Set[FunDef]               = chain.map(_.funDef) toSet

  def loop(initialSubst: Map[Identifier, Expr] = Map(), finalSubst: Map[Identifier, Expr] = Map()) : Seq[Expr] = {
    assert(initialSubst.nonEmpty || finalSubst.nonEmpty)

    def rec(relations: List[Relation], subst: Map[Identifier, Expr]): Seq[Expr] = relations match {
      case Relation(_, path, FunctionInvocation(fd, args)) :: Nil =>
        assert(fd == funDef)
        val newPath = path.map(replaceFromIDs(subst, _))
        val equalityConstraints = if (finalSubst.isEmpty) Seq() else {
          val newArgs = args.map(replaceFromIDs(subst, _))
          (fd.args.map(arg => finalSubst(arg.id)) zip newArgs).map(p => Equals(p._1, p._2))
        }
        newPath ++ equalityConstraints
      case Relation(_, path, FunctionInvocation(fd, args)) :: xs =>
        val formalArgs = fd.args.map(_.id)
        val freshFormalArgVars = formalArgs.map(_.freshen.toVariable)
        val formalArgsMap: Map[Identifier, Expr] = formalArgs zip freshFormalArgVars toMap
        val (newPath, newArgs) = (path.map(replaceFromIDs(subst, _)), args.map(replaceFromIDs(subst, _)))
        val constraints = newPath ++ (freshFormalArgVars zip newArgs).map(p => Equals(p._1, p._2))
        constraints ++ rec(xs, formalArgsMap)
      case Nil => sys.error("Empty chain shouldn't exist by construction")
    }

    val subst : Map[Identifier, Expr] = if (initialSubst.nonEmpty) initialSubst else {
      funDef.args.map(arg => arg.id -> arg.toVariable).toMap
    }
    val Chain(relations) = this
    rec(relations, subst)
  }

  def reentrant(other: Chain) : Seq[Expr] = {
    assert(funDef == other.funDef)
    val bindingSubst : Map[Identifier, Expr] = funDef.args.map({
      arg => arg.id -> arg.id.freshen.toVariable
    }).toMap
    val firstLoop = loop(finalSubst = bindingSubst)
    val secondLoop = other.loop(initialSubst = bindingSubst)
    firstLoop ++ secondLoop
  }

  def times(k: Int, initialSubst: Map[Identifier, Expr] = Map(), finalSubst: Map[Identifier, Expr] = Map()) : Seq[Expr] = {
    def rec(bindingSubst: Map[Identifier, Expr], count: Int) : Seq[Expr] = if (count == k) loop(initialSubst = bindingSubst, finalSubst = finalSubst) else {
      val nextSubst : Map[Identifier, Expr] = funDef.args.map(arg => arg.id -> arg.id.freshen.toVariable).toMap
      val currentLoop = loop(initialSubst = bindingSubst, finalSubst = nextSubst)
      val rest = rec(nextSubst, count + 1)
      currentLoop ++ rest
    }
    rec(initialSubst, 1)
  }

  def inlined: TraversableOnce[Expr] = {
    def rec(list: List[Relation], mapping: Map[Identifier, Expr]): List[Expr] = list match {
      case Relation(_, _, FunctionInvocation(fd, args)) :: xs =>
        val mappedArgs = args.map(replaceFromIDs(mapping, _))
        val newMapping = fd.args.map(_.id).zip(mappedArgs).toMap
        // We assume we have a body at this point. It would be weird to have gotten here without one...
        val expr = hoistIte(expandLets(matchToIfThenElse(fd.getBody)))
        val inlinedExpr = replaceFromIDs(newMapping, expr)
        inlinedExpr:: rec(xs, newMapping)
      case Nil => Nil
    }

    val body = hoistIte(expandLets(matchToIfThenElse(funDef.getBody)))
    body :: rec(chain, funDef.args.map(arg => arg.id -> arg.toVariable) toMap)
  }
}

object ChainBuilder {
  import scala.collection.mutable.{Map => MutableMap}

  private val chainCache : MutableMap[FunDef, Set[Chain]] = MutableMap()
  def run(funDef: FunDef): Set[Chain] = chainCache.get(funDef) match {
    case Some(chains) => chains
    case None => {
      // Note that this method will generate duplicate cycles (in fact, it will generate all list representations of a cycle)
      def chains(partials: List[(Relation, List[Relation])]): List[List[Relation]] = if (partials.isEmpty) Nil else {
        // Note that chains in partials are reversed to profit from O(1) insertion
        val (results, newPartials) = partials.foldLeft(List[List[Relation]](),List[(Relation, List[Relation])]())({
          case ((results, partials), (first, chain @ Relation(_, _, FunctionInvocation(fd, _)) :: xs)) =>
            val cycle = RelationBuilder.run(fd).contains(first)
            // reverse the chain when "returning" it since we're working on reversed chains
            val newResults = if (cycle) chain.reverse :: results else results

            // Partial chains can fall back onto a transition that was already taken (thus creating a cycle
            // inside the chain). Since this cycle will be discovered elsewhere, such partial chains should be
            // dropped from the partial chain list
            val transitions = RelationBuilder.run(fd) -- chain.toSet
            val newPartials = transitions.map(transition => (first, transition :: chain)).toList

            (newResults, partials ++ newPartials)
          case (_, (_, Nil)) => scala.sys.error("Empty partial chain shouldn't exist by construction")
        })

        results ++ chains(newPartials)
      }

      val initialPartials = RelationBuilder.run(funDef).map(r => (r, r :: Nil)).toList
      val result = chains(initialPartials).map(Chain(_)).toSet
      chainCache(funDef) = result
      result
    }
  }
}