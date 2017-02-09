package io.iohk.ethereum.vm

import akka.util.ByteString
import language.implicitConversions


object DataWord {

  /**
    * DataWord size in bytes
    */
  val Size = 32

  private val Modulus: BigInt = BigInt(2).pow(Size * 8)

  val MaxValue = DataWord(Modulus - 1)

  val Zero = DataWord(0)

  private val Zeros: ByteString = ByteString(Array.fill[Byte](Size)(0))

  def apply(value: ByteString): DataWord = {
    require(value.length <= Size, s"Input byte array cannot be longer than $Size: ${value.length}")
    DataWord(value.foldLeft(BigInt(0)){(n, b) => (n << 8) + (b & 0xff)})
  }

  def apply(array: Array[Byte]): DataWord =
    DataWord(ByteString(array))

  def apply(n: BigInt): DataWord =
    new DataWord(fixBigInt(n))

  def apply(b: Boolean): DataWord =
    apply(if (b) 1 else 0)

  def apply[N: Integral](n: N): DataWord = {
    val num = implicitly[Integral[N]]
    apply(BigInt(num.toLong(n)))
  }

  private def fixBigInt(n: BigInt): BigInt = (n % Modulus + Modulus) % Modulus

  implicit def dataWord2BigInt(dw: DataWord): BigInt = dw.toBigInt
}

/** Stores 256 bit words and adds a few convenience methods on them.
 *  Internally a word is stored as a BigInt. */
class DataWord private (private val n: BigInt) extends Ordered[DataWord] {

  import DataWord._

  /** Converts a BigInt to a ByteString.
   *  Output ByteString is padded with 0's from the left side up to MaxLength bytes.
   */
  lazy val bytes: ByteString = {
    val bs: ByteString = ByteString(n.toByteArray).takeRight(Size)
    val padLength: Int = Size - bs.length
    if (padLength > 0)
      Zeros.take(padLength) ++ bs
    else
      bs
  }

  require(n >= 0 && n < Modulus, s"Invalid word value: $n")

  def &(that: DataWord): DataWord = DataWord(this.n & that.n)

  def |(that: DataWord): DataWord = DataWord(this.n | that.n)

  def ^(that: DataWord): DataWord = DataWord(this.n ^ that.n)

  def unary_~ : DataWord = DataWord(~n)

  def unary_- : DataWord = DataWord(-n)

  def +(that: DataWord): DataWord = DataWord(this.n + that.n)

  def -(that: DataWord): DataWord = DataWord(this.n - that.n)

  def *(that: DataWord): DataWord = DataWord(this.n * that.n)

  def /(that: DataWord): DataWord = DataWord(this.n / that.n)

  def **(that: DataWord): DataWord = DataWord(this.n.modPow(that.n, Modulus))

  def compare(that: DataWord): Int = this.n.compare(that.n)

  /**
    * @return an Int with MSB=0, thus a value in range [0, Int.MaxValue]
    */
  def intValue: Int = n.intValue & Int.MaxValue

  /**
    * @return a Long with MSB=0, thus a value in range [0, Long.MaxValue]
    */
  def longValue: Long = n.longValue & Long.MaxValue

  def isZero: Boolean = n == 0

  override def equals(that: Any): Boolean = {
    that match {
      case that: DataWord => this.n.equals(that.n)
      case other => other == n
    }
  }

  override def hashCode: Int = n.hashCode()

  override def toString: String =
    f"DataWord(0x$n%02x)" //would be even better to add a leading zero if odd number of digits

  def toBigInt: BigInt = n

  /**
    * @return Size in bytes excluding the leading 0 bytes
    */
  def byteSize: Int = (n.bitLength - 1) / 8 + 1
}
