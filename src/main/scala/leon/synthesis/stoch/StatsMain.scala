package leon
package synthesis
package stoch

import java.time.LocalDateTime

import StatsUtils._
import Stats._
import purescala.Definitions.UnitDef
import purescala.Expressions.{Expr, Variable}
import leon.utils.PreprocessingPhase

object StatsMain {

  val SELECT_FUNCTION_TYPES: Boolean = false
  val SELECT_TUPLE_TYPES: Boolean = false

/*
  def main(args: Array[String]): Unit = {
    println(LocalDateTime.now())
    println(s"SELECT_FUNCTION_TYPES: ${StatsMain.SELECT_FUNCTION_TYPES}")
    println(s"SELECT_TUPLE_TYPES: ${StatsMain.SELECT_TUPLE_TYPES}")

    val canaryFileName = args(1)
    val canaryExprs = procFiles(canaryFileName)
    val canaryTypes = canaryExprs.collect {
      case v: Variable if v.id.name.contains("f82c414") =>
        v.id.name -> v.getType
    }.toMap

    println("Printing canary types:")
    canaryTypes.foreach(println)

    val fase = args.drop(2).toSeq.par
                   .map(fname => canaryTypeFilter(procFiles(fname, canaryFileName)))
                   .filter(_.nonEmpty)
                   .seq.flatten

    /* for ((fname, exprs) <- fase) {
      println(s"Printing interesting expressions from ${fname}")
      for (expr <- exprs) {
        println(s"$fname, ${expr.getType}, ${expr.getType.getClass}, ${expr.getClass}")
      }
    } */

    val ecs: ExprConstrStats = {
      groupAndFilterExprs(canaryTypes, fase)
    }

    println("Printing coarse expression constructor stats:")
    println(Stats.ecsToStringCoarse(ecs))

    println("Printing expression constructor stats:")
    println(Stats.ecsToString(ecs))

    val fcs: FunctionCallStats = getFunctionCallStats(ecs)
    println("Printing function call stats:")
    println(Stats.fcsToString(fcs))

    val ls: LitStats = getLitStats(ecs)
    println("Printing literal occurrence statistics:")
    println(Stats.lsToString(ls))

    val prodRules: UnitDef = PCFGEmitter.emit(canaryExprs, canaryTypes, ecs, fcs, ls)
    val prodRulesStr = replaceKnownNames(prodRules.toString)
    println("Printing production rules:")
    println(prodRulesStr)
  }

  def procFiles(fileNames: String*): Seq[Expr] = {
    val ctx = Main.processOptions(fileNames.toSeq)
    try {
      pipeline.run(ctx, fileNames.toList)._2
    } catch {
      case ex: Exception =>
        println(s"procFiles($fileNames): Encountered exception $ex")
        Seq()
    }
  }

  def pipeline: Pipeline[List[String], Seq[Expr]] = {
    import leon.frontends.scalac.{ClassgenPhase, ExtractionPhase}
    ClassgenPhase andThen
      ExtractionPhase andThen
      new PreprocessingPhase(false) andThen
      SimpleFunctionApplicatorPhase(allSubExprs) andThen
      SimpleFunctionApplicatorPhase(normalizeExprs)
  }

    def dist(statsTrain: ExprConstrStats, statsTest: ExprConstrStats): (Double, Double) = {
      val statsTrainC = statsTrain.mapValues(_.mapValues(_.size))
      val statsTestC = statsTest.mapValues(_.mapValues(_.size))

      val numTestExprs = statsTestC.mapValues(_.values.sum).values.sum
      println(s"numTestExprs: $numTestExprs")

      var numCorrectTestExprs = 0.0
      var numRandomCorrectTestExprs = 0.0
      for (typeTree <- statsTestC.toSeq.sortBy(-_._2.values.sum).map(_._1)) {
        val typeFreqTest = statsTestC(typeTree).values.sum
        val numConstrs = statsTestC(typeTree).values.size
        println(s"Considering type $typeTree, which occurs $typeFreqTest times in test, and with $numConstrs constructors")

        val predConstrOpt = statsTrainC.getOrElse(typeTree, Map()).toList.sortBy(-_._2).headOption
        predConstrOpt match {
          case Some((constr, _)) =>
            val thisTypeCorrect = statsTestC(typeTree).getOrElse(constr, 0)
            println(s"  Train suggests constructor $constr which was used $thisTypeCorrect times in test")
            numCorrectTestExprs = numCorrectTestExprs + thisTypeCorrect
          case None =>
            numCorrectTestExprs = numCorrectTestExprs + (typeFreqTest.asInstanceOf[Double] / numConstrs)
        }

        numRandomCorrectTestExprs = numRandomCorrectTestExprs + (typeFreqTest.asInstanceOf[Double] / numConstrs)
      }

      val ourScore = numCorrectTestExprs / numTestExprs
      val randomScore = numRandomCorrectTestExprs / numTestExprs
      (ourScore, randomScore)
    }
  */
}