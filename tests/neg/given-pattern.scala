

class Test {
  import scala.collection.immutable.{TreeSet, HashSet}

  def f2[T](x: Ordering[T]) = {
    val (given Ordering[T]) = x // error: given Ordering[T] not allowed here
    new TreeSet[T] // error: no implicit ordering defined for T
  }
  def f3[T](x: Ordering[T]) = {
    val given Ordering[T] = x // error: given Ordering[T] not allowed here
    new TreeSet[T] // error: no implicit ordering defined for T
  }
}
