import leon._
import higherorder._
import mem._
import lang._
import annotation._
import collection._
import instrumentation._

object RealTimeQueue {

  sealed abstract class Stream[T] {
    def isEmpty: Boolean = {
      this match {
        case SNil() => true
        case _      => false
      }
    }

    def isCons: Boolean = {
      this match {
        case SCons(_, _) => true
        case _           => false
      }
    }

    def size: BigInt = {
      this match {
        case SNil()      => BigInt(0)
        case SCons(x, t) => 1 + ssize(t)
      }
    } ensuring (_ >= 0)
  }
  case class SCons[T](x: T, tail: () => Stream[T]) extends Stream[T]
  case class SNil[T]() extends Stream[T]

  def ssize[T](l: () => Stream[T]): BigInt = (l()*).size

  def evaluated[T](l: () => Stream[T]): Boolean = {
    l fmatch {
      case (f: (() => Stream[T]), r: List[T], a: (() => Stream[T])) if l.is(() => rotate(f,r,a)) =>
        rotate(f,r,a).isCached
      case _ => true
    }
  }

  def isConcrete[T](l: () => Stream[T]): Boolean = {
    evaluated(l) && (l()* match {
      case SCons(_, tail) =>
        isConcrete(tail)
      case _ => true
    })
  }

  @invstate
  @memoized
  def rotate[T](f: () => Stream[T], r: List[T], a: () => Stream[T]): Stream[T] = { // doesn't change state
    require(r.size == ssize(f) + 1 && isConcrete(f))
    (f(), r) match {
      case (SNil(), Cons(y, _)) => //in this case 'y' is the only element in 'r'
        SCons[T](y, a)
      case (SCons(x, tail), Cons(y, r1)) =>
        val rot = () => rotate(tail, r1, () => SCons[T](y, a))
        SCons[T](x, rot)
    }
  } ensuring (res => res.size == ssize(f) + r.size + ssize(a) && res.isCons &&
      time <= 30)

  /**
   * Returns the first element of the stream that is not evaluated.
   */
  def firstUnevaluated[T](l: () => Stream[T]): () => Stream[T] = {
    if (evaluated(l)) {
      l()* match {
        case SCons(_, tail) =>
          firstUnevaluated(tail)
        case _ => l
      }
    } else
      l
  } ensuring (res => (!(res()*).isEmpty || isConcrete(l)) && //if there are no lazy closures then the stream is concrete
    (res() match {
      case SCons(_, tail) =>
        firstUnevaluated(l) == firstUnevaluated(tail) // after evaluating the firstUnevaluated closure in 'l' we can access the next unevaluated closure
      case _ => true
    }))

  case class Queue[T](f: () => Stream[T], r: List[T], s: () => Stream[T]) {
    def isEmpty = (f()*).isEmpty
    def valid = {
      (firstUnevaluated(f) == firstUnevaluated(s)) &&
        ssize(s) == ssize(f) - r.size //invariant: |s| = |f| - |r|
    }
  }

  @inline
  def createQ[T](f: () => Stream[T], r: List[T], s: () => Stream[T]) = {
    s() match {
      case SCons(_, tail) => Queue(f, r, tail)
      case SNil() =>
        val rotres = () => rotate(f, r, () => SNil[T]())
        Queue(rotres, Nil(), rotres)
    }
  }

  def enqueue[T](x: T, q: Queue[T]): Queue[T] = {
    require(q.valid)
    createQ(q.f, Cons(x, q.r), q.s)
  } ensuring (res => res.valid && time <= 60)

  def dequeue[T](q: Queue[T]): Queue[T] = {
    require(!q.isEmpty && q.valid)
    q.f() match {
      case SCons(x, nf) =>
        createQ(nf, q.r, q.s)
    }
  } ensuring (res => res.valid && time <= 120)
}
