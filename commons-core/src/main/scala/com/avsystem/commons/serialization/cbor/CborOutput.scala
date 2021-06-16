package com.avsystem.commons
package serialization.cbor

import com.avsystem.commons.serialization.GenCodec.WriteFailure
import com.avsystem.commons.serialization._
import com.avsystem.commons.serialization.cbor.InitialByte.IndefiniteLength

import java.io.{ByteArrayOutputStream, DataOutput, DataOutputStream}
import java.nio.charset.StandardCharsets

/**
  * Defines translation between textual object field names and corresponding numeric labels. May be used to reduce
  * size of CBOR representation of objects.
  */
trait FieldLabels {
  def label(field: String): Opt[Int]
  def field(label: Int): Opt[String]
}
object FieldLabels {
  final val NoLabels: FieldLabels = new FieldLabels {
    def label(field: String): Opt[Int] = Opt.Empty
    def field(label: Int): Opt[String] = Opt.Empty
  }
}

abstract class BaseCborOutput(out: DataOutput) {
  protected final def write(byte: InitialByte): Unit =
    out.write(byte.value)

  private def unsignedInfo(unsignedBytes: Long): Int =
    if (unsignedBytes >= 0 && unsignedBytes < InitialByte.SingleByteValueInfo) unsignedBytes.toInt
    else if ((unsignedBytes & 0xFFL) == unsignedBytes) InitialByte.SingleByteValueInfo
    else if ((unsignedBytes & 0xFFFFL) == unsignedBytes) InitialByte.TwoBytesValueInfo
    else if ((unsignedBytes & 0xFFFFFFFFL) == unsignedBytes) InitialByte.FourBytesValueInfo
    else InitialByte.EightBytesValueInfo

  // unsignedBytes represents 8-byte unsigned integer
  protected final def writeValue(major: MajorType, unsignedBytes: Long): Unit = {
    val info = unsignedInfo(unsignedBytes)
    write(InitialByte(major, info))
    info match {
      case InitialByte.SingleByteValueInfo => out.writeByte(unsignedBytes.toInt)
      case InitialByte.TwoBytesValueInfo => out.writeShort(unsignedBytes.toInt)
      case InitialByte.FourBytesValueInfo => out.writeInt(unsignedBytes.toInt)
      case InitialByte.EightBytesValueInfo => out.writeLong(unsignedBytes)
      case _ =>
    }
  }

  protected def writeSigned(value: Long): Unit =
    if (value >= 0) writeValue(MajorType.Unsigned, value)
    else writeValue(MajorType.Negative, -(value + 1))

  protected final def writeTag(tag: Tag): Unit =
    writeValue(MajorType.Tag, tag.value)

  protected final def writeText(str: String): Unit = {
    val bytes = str.getBytes(StandardCharsets.UTF_8)
    writeValue(MajorType.TextString, bytes.length)
    out.write(bytes)
  }

  protected final def writeBytes(bytes: Array[Byte]): Unit = {
    writeValue(MajorType.ByteString, bytes.length)
    out.write(bytes)
  }
}

object CborOutput {
  def write[T: GenCodec](
    value: T,
    fieldLabels: FieldLabels = FieldLabels.NoLabels,
    sizePolicy: SizePolicy = SizePolicy.Optional
  ): Array[Byte] = {
    val baos = new ByteArrayOutputStream
    GenCodec.write[T](new CborOutput(new DataOutputStream(baos), fieldLabels, sizePolicy), value)
    baos.toByteArray
  }
}

/**
  * An [[com.avsystem.commons.serialization.Output Output]] implementation that serializes into
  * [[https://tools.ietf.org/html/rfc7049 CBOR]].
  */
class CborOutput(out: DataOutput, fieldLabels: FieldLabels, sizePolicy: SizePolicy)
  extends BaseCborOutput(out) with OutputAndSimpleOutput {

  def writeNull(): Unit =
    write(InitialByte.Null)

  def writeBoolean(boolean: Boolean): Unit =
    write(if (boolean) InitialByte.True else InitialByte.False)

  def writeString(str: String): Unit =
    writeText(str)

  def writeChunkedString(): CborChunkedStringOutput =
    new CborChunkedStringOutput(out)

  override def writeSigned(value: Long): Unit =
    super.writeSigned(value)

  def writeInt(int: Int): Unit =
    writeLong(int)

  def writeLong(long: Long): Unit =
    writeSigned(long)

  def writeDouble(double: Double): Unit =
    if (double.toLong.toDouble == double) {
      writeLong(double.toLong)
    } else if (double.isNaN) {
      write(InitialByte.HalfPrecisionFloat)
      out.writeShort(HFloat.NaN.raw)
    } else {
      val float = double.toFloat
      if (float.toDouble == double) {
        val hfloat = HFloat.fromFloat(float)
        if (hfloat.toFloat == float) {
          write(InitialByte.HalfPrecisionFloat)
          out.writeShort(hfloat.raw)
        } else {
          write(InitialByte.SinglePrecisionFloat)
          out.writeFloat(float)
        }
      }
      else {
        write(InitialByte.DoublePrecisionFloat)
        out.writeDouble(double)
      }
    }

  def writeBigInt(bigInt: BigInt): Unit = {
    val neg = bigInt < 0
    val unsigned = if (neg) -(bigInt + 1) else bigInt
    if (unsigned.bitLength <= 64) {
      writeValue(if (neg) MajorType.Negative else MajorType.Unsigned, unsigned.longValue)
    } else {
      writeTag(if (neg) Tag.NegativeBignum else Tag.PositiveBignum)
      writeBinary(unsigned.toByteArray)
    }
  }

  def writeBigDecimal(bigDecimal: BigDecimal): Unit = {
    writeTag(Tag.DecimalFraction)
    writeValue(MajorType.Array, 2)
    writeSigned(-bigDecimal.scale)
    writeBigInt(bigDecimal.bigDecimal.unscaledValue)
  }

  def writeBinary(binary: Array[Byte]): Unit =
    writeBytes(binary)

  def writeChunkedBinary(): CborChunkedBinaryOutput =
    new CborChunkedBinaryOutput(out)

  override def writeTimestamp(millis: Long): Unit = {
    writeTag(Tag.EpochDateTime)
    if (millis % 1000 == 0)
      writeLong(millis / 1000)
    else
      writeDouble(millis.toDouble / 1000)
  }

  def writeList(): CborListOutput =
    new CborListOutput(out, fieldLabels, sizePolicy)

  def writeObject(): CborObjectOutput =
    new CborObjectOutput(out, fieldLabels, sizePolicy)

  def writeRawCbor(raw: RawCbor): Unit =
    out.write(raw.bytes, raw.offset, raw.length)

  override def writeCustom[T](typeMarker: TypeMarker[T], value: T): Boolean =
    typeMarker match {
      case RawCbor =>
        writeRawCbor(value)
        true
      case _ =>
        super.writeCustom(typeMarker, value)
    }

  override def keepsMetadata(metadata: InputMetadata[_]): Boolean = metadata match {
    case InitialByte | Tags => true
    case _ => super.keepsMetadata(metadata)
  }
}

abstract class CborSequentialOutput(
  out: DataOutput,
  override val sizePolicy: SizePolicy
) extends BaseCborOutput(out) with SequentialOutput {

  protected[this] var size: Int = -1
  protected[this] var fresh: Boolean = true

  protected final def ensureInitialWritten(major: MajorType): Unit =
    if (fresh) {
      fresh = false
      if (size >= 0) {
        writeValue(major, size)
      } else if (sizePolicy != SizePolicy.Required) {
        write(InitialByte(major, InitialByte.IndefiniteLengthInfo))
      } else {
        throw new WriteFailure("explicit size for an array or object was required but it was not declared")
      }
    }

  override final def declareSize(size: Int): Unit =
    if (fresh) {
      this.size = size
    } else {
      throw new IllegalStateException("Cannot declare size after elements or fields have already been written")
    }
}

class CborListOutput(
  out: DataOutput,
  fieldLabels: FieldLabels,
  sizePolicy: SizePolicy
) extends CborSequentialOutput(out, sizePolicy) with ListOutput {

  def writeElement(): CborOutput = {
    ensureInitialWritten(MajorType.Array)
    if (size > 0) {
      size -= 1
    } else if (size == 0) {
      throw new WriteFailure("explicit size was given and all the elements have already been written")
    }
    new CborOutput(out, fieldLabels, sizePolicy)
  }

  def finish(): Unit = {
    ensureInitialWritten(MajorType.Array)
    if (size < 0) {
      write(InitialByte.Break)
    } else if (size > 0) {
      throw new WriteFailure("explicit size was given but not enough elements were written")
    }
  }
}

class CborObjectOutput(
  out: DataOutput,
  fieldLabels: FieldLabels,
  sizePolicy: SizePolicy
) extends CborSequentialOutput(out, sizePolicy) with ObjectOutput {

  /**
    * Returns a [[CborOutput]] for writing an arbitrary CBOR map key.
    * This method is an extension of standard [[Output]] which only allows string-typed keys.
    * If a key is written using this method then its corresponding value MUST be written using [[writeValue]]
    * and [[writeField]] MUST NOT be used.
    */
  def writeKey(): CborOutput = {
    ensureInitialWritten(MajorType.Map)
    if (size > 0) {
      size -= 1
    } else if (size == 0) {
      throw new WriteFailure("explicit size was given and all the fields have already been written")
    }
    new CborOutput(out, fieldLabels, sizePolicy)
  }

  /**
    * Returns a [[CborOutput]] for writing a value of a CBOR map field whose key was previously written
    * using [[writeKey]]. This method MUST ONLY be used after the key has been fully written with [[writeKey]].
    * If [[writeKey]] and [[writeValue]] is used then [[writeField]] MUST NOT be used.
    */
  def writeValue(): CborOutput =
    new CborOutput(out, fieldLabels, sizePolicy)

  def writeField(key: String): CborOutput = {
    val kvOutput = writeKey()
    fieldLabels.label(key) match {
      case Opt(label) => kvOutput.writeSigned(label)
      case Opt.Empty => kvOutput.writeString(key)
    }
    kvOutput
  }

  def finish(): Unit = {
    ensureInitialWritten(MajorType.Map)
    if (size < 0) {
      write(InitialByte.Break)
    } else if (size > 0) {
      throw new WriteFailure("explicit size was given but not enough fields were written")
    }
  }
}

abstract class CborChunkedOutput(out: DataOutput) extends BaseCborOutput(out) {
  protected type Chunk

  protected def major: MajorType
  protected def doWriteChunk(chunk: Chunk): Unit

  protected[this] var fresh = true

  private def ensureInitialWritten(): Unit =
    if (fresh) {
      fresh = false
      write(IndefiniteLength(major))
    }

  def writeChunk(chunk: Chunk): Unit = {
    ensureInitialWritten()
    doWriteChunk(chunk)
  }

  def finish(): Unit = {
    ensureInitialWritten()
    write(InitialByte.Break)
  }
}

class CborChunkedStringOutput(out: DataOutput) extends CborChunkedOutput(out) {
  type Chunk = String

  protected def major: MajorType = MajorType.TextString
  protected def doWriteChunk(chunk: String): Unit = writeText(chunk)
}

class CborChunkedBinaryOutput(out: DataOutput) extends CborChunkedOutput(out) {
  type Chunk = Array[Byte]

  protected def major: MajorType = MajorType.ByteString
  protected def doWriteChunk(chunk: Array[Byte]): Unit = writeBytes(chunk)
}
