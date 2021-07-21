object OpaqueEscape{
  opaque type Wrapped = Int
}
import OpaqueEscape.*
abstract class EscaperBase {
def unwrap(i:Wrapped):Int
  def wrap(i:Int):Wrapped
}
class Escaper extends EscaperBase{  // error: needs to be abstract
  override def unwrap(i:Int):Int = i // was error overriding method unwrap, now OK
  override def wrap(i:Int):Int = i  // error overriding method wrap
}
val e = new Escaper:EscaperBase
val w:Wrapped = e.wrap(1)
val u:Int = e.unwrap(w)