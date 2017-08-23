package io.iohk.ethereum

import akka.util.ByteString
import io.iohk.ethereum.domain.UInt256
import io.iohk.ethereum.mpt.{ByteArrayEncoder, ByteArraySerializable, HashByteArraySerializable, MerklePatriciaTrie, NodesKeyValueStorage}
import io.iohk.ethereum.rlp.UInt256RLPImplicits._

package object domain {

  val byteArrayUInt256Serializer = new ByteArrayEncoder[UInt256] {
    override def toBytes(input: UInt256): Array[Byte] = input.bytes.toArray[Byte]
  }

  val rlpUInt256Serializer = new ByteArraySerializable[UInt256] {
    override def fromBytes(bytes: Array[Byte]): UInt256 = ByteString(bytes).toUInt256
    override def toBytes(input: UInt256): Array[Byte] = input.toBytes
  }

  def storageMpt(rootHash: ByteString, nodeStorage: NodesKeyValueStorage): MerklePatriciaTrie[UInt256, UInt256] =
    MerklePatriciaTrie[UInt256, UInt256](rootHash.toArray[Byte], nodeStorage)(HashByteArraySerializable(byteArrayUInt256Serializer), rlpUInt256Serializer)

}
