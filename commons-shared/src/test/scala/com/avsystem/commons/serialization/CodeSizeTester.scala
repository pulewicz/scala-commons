package com.avsystem.commons
package serialization

case class CodeSizeTester00(
  int: Int,
  string: String,
  double: Double,
  map: Map[String, List[Boolean]],
  people: Set[Person]
)
object CodeSizeTester00 {
  implicit val codec: GenCodec[CodeSizeTester00] = GenCodec.materialize[CodeSizeTester00]
}

case class CodeSizeTester01(
  int: Int,
  string: String,
  double: Double,
  map: Map[String, List[Boolean]],
  people: Set[Person]
)
object CodeSizeTester01 {
  implicit val codec: GenCodec[CodeSizeTester01] = GenCodec.materialize
}

case class CodeSizeTester02(
  int: Int,
  string: String,
  double: Double,
  map: Map[String, List[Boolean]],
  people: Set[Person]
)
object CodeSizeTester02 {
  implicit val codec: GenCodec[CodeSizeTester02] = GenCodec.materialize
}

case class CodeSizeTester03(
  int: Int,
  string: String,
  double: Double,
  map: Map[String, List[Boolean]],
  people: Set[Person]
)
object CodeSizeTester03 {
  implicit val codec: GenCodec[CodeSizeTester03] = GenCodec.materialize
}

case class CodeSizeTester04(
  int: Int,
  string: String,
  double: Double,
  map: Map[String, List[Boolean]],
  people: Set[Person]
)
object CodeSizeTester04 {
  implicit val codec: GenCodec[CodeSizeTester04] = GenCodec.materialize
}

case class CodeSizeTester05(
  int: Int,
  string: String,
  double: Double,
  map: Map[String, List[Boolean]],
  people: Set[Person]
)
object CodeSizeTester05 {
  implicit val codec: GenCodec[CodeSizeTester05] = GenCodec.materialize
}

case class CodeSizeTester06(
  int: Int,
  string: String,
  double: Double,
  map: Map[String, List[Boolean]],
  people: Set[Person]
)
object CodeSizeTester06 {
  implicit val codec: GenCodec[CodeSizeTester06] = GenCodec.materialize
}

case class CodeSizeTester07(
  int: Int,
  string: String,
  double: Double,
  map: Map[String, List[Boolean]],
  people: Set[Person]
)
object CodeSizeTester07 {
  implicit val codec: GenCodec[CodeSizeTester07] = GenCodec.materialize
}

case class CodeSizeTester08(
  int: Int,
  string: String,
  double: Double,
  map: Map[String, List[Boolean]],
  people: Set[Person]
)
object CodeSizeTester08 {
  implicit val codec: GenCodec[CodeSizeTester08] = GenCodec.materialize
}

case class CodeSizeTester09(
  int: Int,
  string: String,
  double: Double,
  map: Map[String, List[Boolean]],
  people: Set[Person]
)
object CodeSizeTester09 {
  implicit val codec: GenCodec[CodeSizeTester09] = GenCodec.materialize
}

case class Person(name: String, birthYear: Int, planet: String = "Earth")
object Person {
  implicit val codec: GenCodec[Person] = GenCodec.materialize
}

object Main {
  def main(args: Array[String]): Unit = {
    println(CodeSizeTester00.codec.write(null, null))
//    println(CodeSizeTester01.codec.write(null, null))
//    println(CodeSizeTester02.codec.write(null, null))
//    println(CodeSizeTester03.codec.write(null, null))
//    println(CodeSizeTester04.codec.write(null, null))
//    println(CodeSizeTester05.codec.write(null, null))
//    println(CodeSizeTester06.codec.write(null, null))
//    println(CodeSizeTester07.codec.write(null, null))
//    println(CodeSizeTester08.codec.write(null, null))
//    println(CodeSizeTester09.codec.write(null, null))

    println(CodeSizeTester00.codec.read(null))
//    println(CodeSizeTester01.codec.read(null))
//    println(CodeSizeTester02.codec.read(null))
//    println(CodeSizeTester03.codec.read(null))
//    println(CodeSizeTester04.codec.read(null))
//    println(CodeSizeTester05.codec.read(null))
//    println(CodeSizeTester06.codec.read(null))
//    println(CodeSizeTester07.codec.read(null))
//    println(CodeSizeTester08.codec.read(null))
//    println(CodeSizeTester09.codec.read(null))
  }
}
