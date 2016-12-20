package leon
package grammars
package enumerators

import leon.grammars.enumerators.CandidateScorer.Score
import leon.purescala.Common.FreshIdentifier
import leon.synthesis.{Example, SynthesisPhase}
import leon.utils.{DebugSection, NoPosition}
import purescala.Expressions.Expr
import scala.annotation.tailrec
import scala.collection.mutable


object ProbwiseTopdownEnumerator {
  val ntWrap = (e: Expansion[Label, Expr]) => {
    val tp = e.nt.getType
    FreshIdentifier(Console.BOLD + tp.toString + Console.RESET, tp).toVariable
  }
}


class ProbwiseTopdownEnumerator(
    protected val grammar: ExpressionGrammar,
    init: Label,
    scorer: CandidateScorer[Label, Expr],
    examples: Seq[Example],
    eval: (Expr, Example) => Option[Expr],
    disambiguate: Boolean
  )(implicit ctx: LeonContext)
  extends AbstractProbwiseTopdownEnumerator[Label, Expr](scorer, disambiguate, ProbwiseTopdownEnumerator.ntWrap)
  with GrammarEnumerator
{
  import ctx.reporter._
  override protected implicit val debugSection = leon.utils.DebugSectionSynthesis

  debug(s"Creating ProbwiseTopdownEnumerator with disambiguate = $disambiguate")

  val hors = GrammarEnumerator.horizonMap(init, productions).mapValues(_._2)
  protected def productions(nt: Label) = grammar.getProductions(nt)
  protected def nthor(nt: Label): Double = hors(nt)

  def sig(r: Expr): Option[Seq[Expr]] = {
    examples mapM (eval(r, _))
  }

}

object EnumeratorStats {
  var partialEvalAcceptCount: Int = 0
  var partialEvalRejectionCount: Int = 0
  var expandNextCallCount: Int = 0
  var iteratorNextCallCount: Int = 0
  var cegisIterCount: Int = 0
}

abstract class AbstractProbwiseTopdownEnumerator[NT, R](scorer: CandidateScorer[NT, R], disambiguate: Boolean, ntWrap: Expansion[NT, R] => R)(implicit ctx: LeonContext) {

  import ctx.reporter._
  implicit protected val debugSection = leon.utils.DebugSectionSynthesis
  def verboseDebug(msg: => String) = {
    debug(msg)(leon.utils.DebugSectionSynthesisVerbose)
  }
  def ifVerboseDebug(body: (Any => Unit) => Any) = {
    ifDebug(NoPosition, body)(leon.utils.DebugSectionSynthesisVerbose)
  }

  protected def productions(nt: NT): Seq[ProductionRule[NT, R]]

  protected def nthor(nt: NT): Double

  protected val coeff = ctx.findOptionOrDefault(SynthesisPhase.optProbwiseTopdownCoeff)

  type Sig = Seq[R]

  // Filter out seen expressions
  protected val redundant = mutable.Set[Expansion[NT, R]]()
  protected val representatives = mutable.Set[Expansion[NT, R]]()
  def expRedundant(e: Expansion[NT, R]) = redundant(e)

  // Disambiguate expressions by signature
  protected val seenSigs = mutable.Map[(NT, Sig), Expansion[NT, R]]()
  def sig(r: R): Option[Sig]
  protected def sigFresh(e: Expansion[NT, R], verbose: Boolean): Boolean = {
    var reason = ""
    val res = e match {
      case NonTerminalInstance(_) =>
        if (verbose) {
          reason = "Non-terminal"
        }
        true
      case ProdRuleInstance(nt, rule, children) =>
        if (children.exists(child => !sigFresh(child, verbose))) {
          if (verbose) {
            reason = "Non-canonical child exists"
          }
          false
        } else if (!e.complete) {
          if (verbose) {
            reason = "Incomplete"
          }
          true
        } else {
          sig(e.produce).exists { theSig =>
            if (!seenSigs.contains((e.nt, theSig))) {
              seenSigs += ((e.nt, theSig) -> e)
              representatives += e
              if (verbose) {
                reason = "Signature never seen before"
              }
              true
            } else if (representatives(e)) {
              if (verbose) {
                reason = "Representative expansion"
              }
              true
            } else {
              redundant += e
              if (verbose) {
                reason = s"Redundant. seenSigs((e.nt, theSig)) = ${seenSigs((e.nt, theSig)).falseProduce(ntWrap)}"
              }
              false
            }
          }
        }
    }
    if (verbose) {
      verboseDebug(s"sigFresh(${e.falseProduce(ntWrap)}) = $res. $reason.")
    }
    res
  }

  /**
    * Represents an element of the worklist
    *
    * @param expansion The partial expansion under consideration
    * @param logProb The log probability already accumulated by the expansion
    * @param horizon The minimum cost that this expansion will accumulate before becoming concrete
    */
  case class WorklistElement(
    expansion: Expansion[NT, R],
    logProb: Double,
    horizon: Double,
    score: Score,
    parent: Option[WorklistElement]
  ) {
    require(logProb <= 0 && horizon <= 0)
    def get = expansion.produce
    val yesScore = score.nYes

    def priorityExplanation = {
      val t1 = coeff * Math.log((score.nYes + 1.0) / (score.nExs + 1.0))
      val t2 = logProb + horizon
      Map("Priority" -> (t1 + t2),
          "nYes" -> score.nYes,
          "nExs" -> score.nExs,
          "t1" -> t1,
          "logProb" -> logProb,
          "horizon" -> horizon,
          "t2" -> t2)
    }

    val priority = {
      val t1 = coeff * Math.log((score.nYes + 1.0) / (score.nExs + 1.0))
      val t2 = logProb + horizon
      t1 + t2
    }

    def lineage: Seq[WorklistElement] = this +: parent.map(_.lineage).getOrElse(Nil)
  }

  def iterator(nt: NT): Iterator[R] = new Iterator[R] {
    val ordering = Ordering.by[WorklistElement, Double](_.priority)
    val worklist = new mutable.PriorityQueue[WorklistElement]()(ordering)

    val seedExpansion = NonTerminalInstance[NT, R](nt)
    val seedScore = scorer.score(seedExpansion, Set(), eagerReturnOnFalse = false)
    val worklistSeed = WorklistElement(seedExpansion, 0.0, nthor(nt), seedScore, None)

    worklist.enqueue(worklistSeed)

    var lastPrint: Int = 1

    def hasNext: Boolean = {
      prepareNext()
      worklist.nonEmpty
    }

    def next: R = {
      EnumeratorStats.iteratorNextCallCount += 1
      prepareNext()
      assert(worklist.nonEmpty)
      val res = worklist.dequeue
      ifDebug { printer =>
        printer("Printing lineage for returned element:")
        res.lineage.foreach { elem => printer(s"    ${elem.priority}: ${elem.expansion.falseProduce(ntWrap)}") }
      }
      res.get
    }

    @tailrec def prepareNext(): Unit = {
      if (worklist.nonEmpty) {
        val elem = worklist.head
        lazy val score = scorer.score(elem.expansion, elem.score.yesExs, eagerReturnOnFalse = false)
        lazy val compliesTests = {
          val res = score.noExs.isEmpty
          if (res) {
            EnumeratorStats.partialEvalAcceptCount += 1
          } else {
            EnumeratorStats.partialEvalRejectionCount += 1
          }
          res
        }
        lazy val hasFreshSig = !disambiguate || sigFresh(elem.expansion, score.noExs.isEmpty && score.maybeExs.isEmpty)
        // Does elem have to be removed from the queue?
        if (!elem.expansion.complete || !compliesTests || !hasFreshSig) {
          // If so, remove it
          worklist.dequeue()
          verboseDebug(s"Dequeued (${elem.priority}): ${elem.expansion.falseProduce(ntWrap)}")
          // Is it possible that the expansions of elem lead somewhere?
          if (compliesTests && hasFreshSig) {
            // If so, compute them and put them back in the worklist
            val newElems = expandNext(elem, score)
            worklist ++= newElems
            // And debug some
            ifVerboseDebug { printer =>
              newElems foreach { e =>
                printer(s"Enqueued (${e.priority}): ${e.expansion.falseProduce(ntWrap)}")
              }
            }
            ifDebug { printer =>
              if (worklist.size >= 2 * lastPrint) {
                printer(s"Worklist size: ${worklist.size}")
                printer(s"Accept / reject ratio: ${EnumeratorStats.partialEvalAcceptCount} /" +
                  s"${EnumeratorStats.partialEvalRejectionCount}")
                lastPrint = worklist.size
              }
            }
          } else {
            ifVerboseDebug { printer =>
              val scoreTriple = (score.yesExs.size, score.noExs.size, score.maybeExs.size)
              if (!compliesTests) {
                printer(s"Element rejected. compliesTests = $compliesTests, scoreTriple = $scoreTriple.")
              } else {
                printer(s"Element rejected. compliesTests = $compliesTests, hasFreshSig = $hasFreshSig, scoreTriple = $scoreTriple.")
              }
            }
          }
          // We dequeued an elemen, so we don't necessarily have an acceptable element
          // on the head of the queue. Call rec again.
          prepareNext()
        }
      }
    }

  }

  def expandNext(elem: WorklistElement, elemScore: Score): Seq[WorklistElement] = {
    EnumeratorStats.expandNextCallCount += 1
    val expansion = elem.expansion

    require(!expansion.complete)

    expansion match {
      case NonTerminalInstance(nt) =>
        val prodRules = productions(nt)
        for {
          rule <- prodRules
          expansion = ProdRuleInstance(
            nt,
            rule,
            rule.subTrees.map(ntChild => NonTerminalInstance[NT, R](ntChild)).toList
          )
          // if !expRedundant(expansion)
        } yield {
          val logProbPrime = elem.logProb + rule.weight
          val horizonPrime = rule.subTrees.map(nthor).sum
          WorklistElement(expansion, logProbPrime, horizonPrime, elemScore, Some(elem))
        }

      case ProdRuleInstance(nt, rule, children) =>
        require(children.exists(!_.complete))

        def expandChildren(cs: List[Expansion[NT, R]]): Seq[(List[Expansion[NT, R]], Double)] = cs match {
          case Nil =>
            throw new IllegalArgumentException()
          case csHd :: csTl if csHd.complete =>
            for ((expansions, logProb) <- expandChildren(csTl)) yield (csHd :: expansions, logProb)
          case csHd :: csTl =>
            for {
              csHdExp <- expandNext(WorklistElement(csHd, 0.0, 0.0, elemScore, None), elemScore)
              // if !expRedundant(csHdExp.expansion)
            } yield {
              (csHdExp.expansion :: csTl, csHdExp.logProb)
            }
        }

        for {
          (expansions, logProb) <- expandChildren(children)
          expPrime = ProdRuleInstance(nt, rule, expansions)
          // if !expRedundant(expPrime)
        } yield {
          val logProbPrime = elem.logProb + logProb
          val horizonPrime = expPrime.horizon(nthor)
          WorklistElement(expPrime, logProbPrime, horizonPrime, elemScore, Some(elem))
        }
    }
  }
}