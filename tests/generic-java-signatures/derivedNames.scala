import java.lang.reflect.ParameterizedType

object Test {
  def main(args: Array[String]): Unit = {
    val objectB = classOf[Foo[Any]].getClasses
    val returnType = objectB(1).getDeclaredMethod("m").getGenericReturnType.asInstanceOf[ParameterizedType]
    val out1 = "Test$Foo.Test$Foo$A<Test.Test$Foo<T1>.B$>" // Windows
    val out2 = "Test$Foo$A<Test$Foo<T1>$B$>"               // Linux, OSX and sometimes Windows
    if (scala.util.Properties.isWin)
      assert(returnType.toString == out1 || returnType.toString == out2)
    else
      assert(returnType.toString == out2)
  }
  class Foo[T1] {
    class A[T2]

    object B  {
      def m: A[B.type] = ???
    }
  }
}

