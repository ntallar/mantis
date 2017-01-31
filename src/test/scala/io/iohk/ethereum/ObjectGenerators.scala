package io.iohk.ethereum

import java.math.BigInteger

import akka.util.ByteString
import io.iohk.ethereum.network.p2p.messages.PV63._
import org.scalacheck.{Arbitrary, Gen}
import io.iohk.ethereum.mpt.HexPrefix.bytesToNibbles

trait ObjectGenerators {

  lazy val intGen: Gen[Int] = Gen.choose(Int.MinValue, Int.MaxValue)

  lazy val bigIntGen: Gen[BigInt] = byteArrayOfNItemsGen(32).map(b=>new BigInteger(1, b))

  lazy val anyArrayGen: Gen[Array[Byte]] = Gen.nonEmptyListOf(Arbitrary.arbitrary[Byte]).map(_.toArray)

  def byteArrayOfNItemsGen(n: Int): Gen[Array[Byte]] = Gen.listOfN(n, Arbitrary.arbitrary[Byte]).map(_.toArray)

  def byteStringOfLengthNGen(n: Int): Gen[ByteString] = byteArrayOfNItemsGen(n).map(ByteString(_))

  def seqByteArrayOfNItemsGen(n: Int): Gen[Seq[Array[Byte]]] = Gen.listOf(byteArrayOfNItemsGen(n))

  def hexPrefixDecodeParametersGen(): Gen[(Array[Byte], Boolean)] = for {
    aByteList <- Gen.nonEmptyListOf(Arbitrary.arbitrary[Byte])
    t <- Arbitrary.arbitrary[Boolean]
  } yield (aByteList.toArray, t)

  def keyValueListGen(): Gen[List[(Int, Int)]] = for {
    aKeyList <- Gen.nonEmptyListOf(Arbitrary.arbitrary[Int]).map(_.distinct)
  } yield aKeyList.zip(aKeyList)

  def receiptGen(): Gen[Receipt] = for {
    postTransactionStateHash <- anyArrayGen
    cumulativeGasUsed <- bigIntGen
    logsBloomFilter <- anyArrayGen
  } yield Receipt(
    postTransactionStateHash = ByteString(postTransactionStateHash),
    cumulativeGasUsed = cumulativeGasUsed,
    logsBloomFilter = ByteString(logsBloomFilter),
    logs = Seq()
  )

  def receiptsGen(n: Int): Gen[Seq[Seq[Receipt]]] = Gen.listOfN(n, Gen.listOf(receiptGen()))

  def branchNodeGen: Gen[MptBranch] = for {
    children <- Gen.listOfN(16, byteStringOfLengthNGen(32)).map(childrenList => childrenList.map(child => Left(MptHash(child))))
    terminator <- byteStringOfLengthNGen(32)
  } yield MptBranch(children, terminator)

  def extensionNodeGen: Gen[MptExtension] = for {
    keyNibbles <- byteArrayOfNItemsGen(32)
    value <- byteStringOfLengthNGen(32)
  } yield MptExtension(ByteString(bytesToNibbles(keyNibbles)), Left(MptHash(value)))

  def leafNodeGen: Gen[MptLeaf] = for {
    keyNibbles <- byteArrayOfNItemsGen(32)
    value <- byteStringOfLengthNGen(32)
  } yield MptLeaf(ByteString(bytesToNibbles(keyNibbles)), value)

  def nodeGen: Gen[MptNode] = Gen.choose(0, 2).flatMap{ i =>
    i match {
      case 0 => branchNodeGen
      case 1 => extensionNodeGen
      case 2 => leafNodeGen
    }
  }

}
