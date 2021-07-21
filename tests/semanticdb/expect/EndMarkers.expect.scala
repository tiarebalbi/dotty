package endmarkers:

  class MultiCtor/*<-endmarkers::MultiCtor#*/(val i/*<-endmarkers::MultiCtor#i.*/: Int/*->scala::Int#*/):
    def this/*<-endmarkers::MultiCtor#`<init>`(+1).*/() =
      this(23)
    end this/*->endmarkers::MultiCtor#`<init>`(+1).*/
  end MultiCtor/*->endmarkers::MultiCtor#*/

  def topLevelMethod/*<-endmarkers::EndMarkers$package.topLevelMethod().*/: String/*->scala::Predef.String#*/ =
    "hello"
  end topLevelMethod/*->endmarkers::EndMarkers$package.topLevelMethod().*/

  val topLevelVal/*<-endmarkers::EndMarkers$package.topLevelVal.*/: Int/*->scala::Int#*/ =
    23
  end topLevelVal/*->endmarkers::EndMarkers$package.topLevelVal.*/

  var topLevelVar/*<-endmarkers::EndMarkers$package.topLevelVar().*/: String/*->scala::Predef.String#*/ =
    ""
  end topLevelVar/*->endmarkers::EndMarkers$package.topLevelVar().*/

  class Container/*<-endmarkers::Container#*/:

    def foo/*<-endmarkers::Container#foo().*/ =
      (/*->scala::Tuple3.apply().*/1,2,3)
    end foo/*->endmarkers::Container#foo().*/

    val bar/*<-endmarkers::Container#bar.*/ =
      (/*->scala::Tuple3.apply().*/4,5,6)
    end bar/*->endmarkers::Container#bar.*/

    var baz/*<-endmarkers::Container#baz().*/ =
      15
    end baz/*->endmarkers::Container#baz().*/

  end Container/*->endmarkers::Container#*/

  def topLevelWithLocals/*<-endmarkers::EndMarkers$package.topLevelWithLocals().*/: Unit/*->scala::Unit#*/ =

    val localVal/*<-local0*/ =
      37
    end localVal/*->local0*/

    var localVar/*<-local1*/ =
      43
    end localVar/*->local1*/

    def localDef/*<-local2*/ =
      97
    end localDef/*->local2*/

  end topLevelWithLocals/*->endmarkers::EndMarkers$package.topLevelWithLocals().*/

  object TestObj/*<-endmarkers::TestObj.*/:

    def foo/*<-endmarkers::TestObj.foo().*/ = 23

  end TestObj/*->endmarkers::TestObj.*/

end endmarkers
