inline val a = ""
extension (x: Int) def combine (y: Int) = x + y
def combine(x: Int, y: Int, z: Int) = x + y + z
def combine = 0
def foo = "foo"
@main def MyProgram(times: Int): Unit = (1 to times) foreach (_ => println("hello"))
def fooRef = toplevel$package.foo
