package io.iohk.ethereum.domain

import akka.util.ByteString
import io.iohk.ethereum.vm.UInt256
import io.iohk.ethereum.utils.ByteUtils.padLeft
import org.spongycastle.util.encoders.Hex

object Address {

  val Length = 20

  def apply(bytes: ByteString): Address = {
    val truncated = bytes.takeRight(Length)
    val extended = padLeft(truncated, Length)
    new Address(extended)
  }

  def apply(uint: UInt256): Address = Address(uint.bytes)

  def apply(arr: Array[Byte]): Address = Address(ByteString(arr))

  def apply(addr: Long): Address = Address(UInt256(addr))

}

class Address private(val bytes: ByteString) {

  def toArray: Array[Byte] = bytes.toArray

  def toUInt256: UInt256 = UInt256(bytes)

  override def equals(that: Any): Boolean = that match {
    case addr: Address => addr.bytes == bytes
    case other => false
  }

  override def hashCode: Int =
    bytes.hashCode

  override def toString: String =
    s"0x${Hex.toHexString(toArray)}"

}
