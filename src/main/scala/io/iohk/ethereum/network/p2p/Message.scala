package io.iohk.ethereum.network.p2p

import java.math.BigInteger

import akka.util.ByteString
import io.iohk.ethereum.crypto.ECDSASignature
import io.iohk.ethereum.rlp
import io.iohk.ethereum.rlp._
import io.iohk.ethereum.rlp.RLPImplicits._
import io.iohk.ethereum.utils.ByteUtils._
import org.spongycastle.crypto.digests.{KeccakDigest, SHA3Digest}
import org.spongycastle.util.encoders.Hex

object Message {

  def decode(`type`: Int, payload: Array[Byte]): Message = `type` match {
    case Hello.code => rlp.decode(payload)(Hello.rlpEndDec)
    case Disconnect.code => rlp.decode(payload)(Disconnect.rlpEndDec)
    case Ping.code => rlp.decode(payload)(Ping.rlpEndDec)
    case Pong.code => rlp.decode(payload)(Pong.rlpEndDec)
    case Status.code => rlp.decode(payload)(Status.rlpEndDec)
    case Transactions.code => rlp.decode(payload)(Transactions.rlpEndDec)
    case NewBlockHashes.code => rlp.decode(payload)(NewBlockHashes.rlpEndDec)
    case GetBlockHeaders.code => rlp.decode(payload)(GetBlockHeaders.rlpEndDec)
    case BlockHeaders.code => rlp.decode(payload)(BlockHeaders.rlpEndDec)
    case GetBlockBodies.code => rlp.decode(payload)(GetBlockBodies.rlpEndDec)
    case BlockBodies.code => rlp.decode(payload)(BlockBodies.rlpEndDec)
    case _ => throw new RuntimeException(s"Unknown message type: ${`type`}")
  }
}

sealed trait Message {
  def code: Int
}

object Capability {
  implicit val rlpEncDec = new RLPEncoder[Capability] with RLPDecoder[Capability] {
    override def encode(obj: Capability): RLPEncodeable = {
      RLPList(obj.name, obj.version)
    }

    override def decode(rlp: RLPEncodeable): Capability = rlp match {
      case RLPList(name, version) => Capability(name, version)
      case _ => throw new RuntimeException("Cannot decode Capability")
    }
  }
}

case class Capability(name: String, version: Byte)

object Hello {

  implicit val rlpEndDec = new RLPEncoder[Hello] with RLPDecoder[Hello] {
    override def encode(obj: Hello): RLPEncodeable = {
      import obj._
      RLPList(p2pVersion, clientId, capabilities, listenPort, nodeId.toArray[Byte])
    }

    override def decode(rlp: RLPEncodeable): Hello = rlp match {
      case RLPList(p2pVersion, clientId, (capabilities: RLPList), listenPort, nodeId) =>
        Hello(p2pVersion, clientId, capabilities.items.map(Capability.rlpEncDec.decode),
          listenPort, ByteString(nodeId: Array[Byte]))
      case _ => throw new RuntimeException("Cannot decode Hello")
    }
  }

  val code = 0x00

}

case class Hello(
    p2pVersion: Long,
    clientId: String,
    capabilities: Seq[Capability],
    listenPort: Long,
    nodeId: ByteString)
  extends Message {

  override val code: Int = Hello.code

  override def toString: String = {
    s"""Hello {
       |p2pVersion: $p2pVersion
       |clientId: $clientId
       |capabilities: $capabilities
       |listenPort: $listenPort
       |nodeId: ${Hex.toHexString(nodeId.toArray[Byte])}
       |}""".stripMargin
  }
}

object Ping {

  implicit val rlpEndDec = new RLPEncoder[Ping] with RLPDecoder[Ping] {
    override def encode(obj: Ping): RLPEncodeable = RLPList()

    override def decode(rlp: RLPEncodeable): Ping = Ping()
  }

  val code = 0x02
}

case class Ping() extends Message {
  override val code: Int = Ping.code
}

object Pong {

  implicit val rlpEndDec = new RLPEncoder[Pong] with RLPDecoder[Pong] {
    override def encode(obj: Pong): RLPEncodeable = RLPList()

    override def decode(rlp: RLPEncodeable): Pong = Pong()
  }

  val code = 0x03
}

case class Pong() extends Message {
  override val code: Int = Pong.code
}

object BlockBodies {
  implicit val rlpEndDec = new RLPEncoder[BlockBodies] with RLPDecoder[BlockBodies] {
    override def encode(obj: BlockBodies): RLPEncodeable = {
      import obj._
      RLPList(bodies.map(BlockBody.rlpEndDec.encode): _*)
    }

    override def decode(rlp: RLPEncodeable): BlockBodies = rlp match {
      case rlpList: RLPList => BlockBodies(rlpList.items.map(BlockBody.rlpEndDec.decode))
      case _ => throw new RuntimeException("Cannot decode BlockBodies")
    }
  }

  val code: Int = 0x10 + 0x06
}

case class BlockBodies(bodies: Seq[BlockBody]) extends Message {
  val code: Int = BlockBodies.code
}

object BlockBody {
  implicit val rlpEndDec = new RLPEncoder[BlockBody] with RLPDecoder[BlockBody] {
    override def encode(obj: BlockBody): RLPEncodeable = {
      import obj._
      RLPList(
        RLPList(transactionList.map(Transaction.rlpEndDec.encode): _*),
        RLPList(uncleNodesList.map(BlockHeader.rlpEndDec.encode): _*))
    }

    override def decode(rlp: RLPEncodeable): BlockBody = rlp match {
      case RLPList((transactions: RLPList), (uncles: RLPList)) =>
        BlockBody(
          transactions.items.map(Transaction.rlpEndDec.decode),
          uncles.items.map(BlockHeader.rlpEndDec.decode))
      case _ => throw new RuntimeException("Cannot decode BlockBody")
    }
  }
}

case class BlockBody(transactionList: Seq[Transaction], uncleNodesList: Seq[BlockHeader]) {
  override def toString: String =
    s"""BlockBody{
       |transactionList: $transactionList
       |uncleNodesList: $uncleNodesList
       |}
    """.stripMargin
}

object Status {
  implicit val rlpEndDec = new RLPEncoder[Status] with RLPDecoder[Status] {
    override def encode(obj: Status): RLPEncodeable = {
      import obj._
      RLPList(protocolVersion, networkId, totalDifficulty, bestHash.toArray[Byte], genesisHash.toArray[Byte])
    }

    override def decode(rlp: RLPEncodeable): Status = rlp match {
      case RLPList(protocolVersion, networkId, totalDifficulty, bestHash, genesisHash) =>
        Status(protocolVersion, networkId, totalDifficulty, ByteString(bestHash: Array[Byte]), ByteString(genesisHash: Array[Byte]))
      case _ => throw new RuntimeException("Cannot decode Status")
    }
  }

  val code: Int = 0x10 + 0x00
}

case class Status(protocolVersion: Int, networkId: Int, totalDifficulty: BigInt, bestHash: ByteString, genesisHash: ByteString) extends Message {
  override def code: Int = Status.code

  override def toString: String = {
    s"""Status {
       |protocolVersion: $protocolVersion
       |networkId: $networkId
       |totalDifficulty: $totalDifficulty
       |bestHash: ${Hex.toHexString(bestHash.toArray[Byte])}
       |genesisHash: ${Hex.toHexString(genesisHash.toArray[Byte])}
       |}""".stripMargin
  }
}

object NewBlockHashes {
  implicit val rlpEndDec = new RLPEncoder[NewBlockHashes] with RLPDecoder[NewBlockHashes] {
    override def encode(obj: NewBlockHashes): RLPEncodeable = {
      import obj._
      RLPList(hashes.map(BlockHash.rlpEndDec.encode): _*)
    }

    override def decode(rlp: RLPEncodeable): NewBlockHashes = rlp match {
      case rlpList: RLPList => NewBlockHashes(rlpList.items.map(BlockHash.rlpEndDec.decode))
      case _ => throw new RuntimeException("Cannot decode NewBlockHashes")
    }
  }

  val code: Int = 0x10 + 0x01
}

case class NewBlockHashes(hashes: Seq[BlockHash]) extends Message {
  override def code: Int = NewBlockHashes.code
}


object BlockHash {
  implicit val rlpEndDec = new RLPEncoder[BlockHash] with RLPDecoder[BlockHash] {
    override def encode(obj: BlockHash): RLPEncodeable = {
      import obj._
      RLPList(hash.toArray[Byte], number)
    }

    override def decode(rlp: RLPEncodeable): BlockHash = rlp match {
      case RLPList(hash, number) => BlockHash(ByteString(hash: Array[Byte]), number)
      case _ => throw new RuntimeException("Cannot decode BlockHash")
    }
  }
}

case class BlockHash(hash: ByteString, number: BigInt) {
  override def toString: String = {
    s"""BlockHash {
       |hash: ${Hex.toHexString(hash.toArray[Byte])}
       |number: $number
       |}""".stripMargin
  }
}

object GetBlockHeaders {
  implicit val rlpEndDec = new RLPEncoder[GetBlockHeaders] with RLPDecoder[GetBlockHeaders] {
    override def encode(obj: GetBlockHeaders): RLPEncodeable = {
      import obj._
      block match {
        case Left(blockNumber) => RLPList(blockNumber, maxHeaders, skip, reverse)
        case Right(blockHash) => RLPList(blockHash.toArray[Byte], maxHeaders, skip, reverse)
      }
    }

    override def decode(rlp: RLPEncodeable): GetBlockHeaders = rlp match {
      case RLPList((block: RLPValue), maxHeaders, skip, reverse) if block.bytes.length < 32 =>
        GetBlockHeaders(Left(block), maxHeaders, skip, reverse)

      case RLPList((block: RLPValue), maxHeaders, skip, reverse) =>
        GetBlockHeaders(Right(ByteString(block: Array[Byte])), maxHeaders, skip, reverse)

      case _ => throw new RuntimeException("Cannot decode GetBlockHeaders")
    }
  }

  val code: Int = 0x10 + 0x03
}

case class GetBlockHeaders(block: Either[BigInt, ByteString], maxHeaders: BigInt, skip: BigInt, reverse: Int) extends Message {
  override def code: Int = GetBlockHeaders.code

  override def toString: String = {
    s"""GetBlockHeaders{
       |block: ${block.fold(a => a, b => Hex.toHexString(b.toArray[Byte]))}
       |maxHeaders: $maxHeaders
       |skip: $skip
       |reverse: ${reverse == 1}
       |}
     """.stripMargin
  }
}

object BlockHeaders {
  implicit val rlpEndDec = new RLPEncoder[BlockHeaders] with RLPDecoder[BlockHeaders] {
    override def encode(obj: BlockHeaders): RLPEncodeable = {
      import obj._
      RLPList(headers.map(BlockHeader.rlpEndDec.encode): _*)
    }

    override def decode(rlp: RLPEncodeable): BlockHeaders = rlp match {
      case rlpList: RLPList => BlockHeaders(rlpList.items.map(BlockHeader.rlpEndDec.decode))

      case _ => throw new RuntimeException("Cannot decode BlockHeaders")
    }
  }

  val code: Int = 0x10 + 0x04
}

case class BlockHeaders(headers: Seq[BlockHeader]) extends Message {
  override def code: Int = BlockHeaders.code
}

object BlockHeader {
  implicit val rlpEndDec = new RLPEncoder[BlockHeader] with RLPDecoder[BlockHeader] {
    override def encode(obj: BlockHeader): RLPEncodeable = {
      import obj._
      RLPList(
        parentHash.toArray[Byte],
        ommersHash.toArray[Byte],
        beneficiary.toArray[Byte],
        stateRoot.toArray[Byte],
        transactionsRoot.toArray[Byte],
        receiptsRoot.toArray[Byte],
        logsBloom.toArray[Byte],
        difficulty,
        number,
        gasLimit,
        gasUsed,
        unixTimestamp,
        extraData.toArray[Byte],
        mixHash.toArray[Byte],
        nonce.toArray[Byte])
    }

    override def decode(rlp: RLPEncodeable): BlockHeader = rlp match {
      case RLPList(parentHash, ommersHash, beneficiary, stateRoot, transactionsRoot, receiptsRoot, logsBloom,
      difficulty, number, gasLimit, gasUsed, unixTimestamp, extraData, mixHash, nonce) =>
        BlockHeader(ByteString(parentHash: Array[Byte]),
          ByteString(ommersHash: Array[Byte]),
          ByteString(beneficiary: Array[Byte]),
          ByteString(stateRoot: Array[Byte]),
          ByteString(transactionsRoot: Array[Byte]),
          ByteString(receiptsRoot: Array[Byte]),
          ByteString(logsBloom: Array[Byte]),
          difficulty,
          number,
          gasLimit,
          gasUsed,
          unixTimestamp,
          ByteString(extraData: Array[Byte]),
          ByteString(mixHash: Array[Byte]),
          ByteString(nonce: Array[Byte]))

      case _ => throw new RuntimeException("Cannot decode BlockHeaders")
    }
  }
}

case class BlockHeader(
    parentHash: ByteString,
    ommersHash: ByteString,
    beneficiary: ByteString,
    stateRoot: ByteString,
    transactionsRoot: ByteString,
    receiptsRoot: ByteString,
    logsBloom: ByteString,
    difficulty: BigInt,
    number: BigInt,
    gasLimit: BigInt,
    gasUsed: BigInt,
    unixTimestamp: Long,
    extraData: ByteString,
    mixHash: ByteString,
    nonce: ByteString) {

  override def toString: String = {
    s"""BlockHeader {
       |parentHash: ${Hex.toHexString(parentHash.toArray[Byte])}
       |ommersHash: ${Hex.toHexString(ommersHash.toArray[Byte])}
       |beneficiary: ${Hex.toHexString(beneficiary.toArray[Byte])}
       |stateRoot: ${Hex.toHexString(stateRoot.toArray[Byte])}
       |transactionsRoot: ${Hex.toHexString(transactionsRoot.toArray[Byte])}
       |receiptsRoot: ${Hex.toHexString(receiptsRoot.toArray[Byte])}
       |logsBloom: ${Hex.toHexString(logsBloom.toArray[Byte])}
       |difficulty: $difficulty,
       |number: $number,
       |gasLimit: $gasLimit,
       |gasUsed: $gasUsed,
       |unixTimestamp: $unixTimestamp,
       |extraData: ${Hex.toHexString(extraData.toArray[Byte])}
       |mixHash: ${Hex.toHexString(mixHash.toArray[Byte])}
       |nonce: ${Hex.toHexString(nonce.toArray[Byte])}
       |}""".stripMargin
  }
}

object GetBlockBodies {
  implicit val rlpEndDec = new RLPEncoder[GetBlockBodies] with RLPDecoder[GetBlockBodies] {
    override def encode(obj: GetBlockBodies): RLPEncodeable = {
      import obj._
      RLPList(hashes.map(e => RLPValue(e.toArray[Byte])): _*)
    }

    override def decode(rlp: RLPEncodeable): GetBlockBodies = rlp match {
      case rlpList: RLPList => GetBlockBodies(rlpList.items.map(e => ByteString(e: Array[Byte])))

      case _ => throw new RuntimeException("Cannot decode BlockHeaders")
    }
  }

  val code: Int = 0x10 + 0x05
}

case class GetBlockBodies(hashes: Seq[ByteString]) extends Message {
  override def code: Int = GetBlockBodies.code

  override def toString: String = {
    s"""GetBlockBodies {
       |hashes: ${hashes.map(h => Hex.toHexString(h.toArray[Byte]))}
       |}
     """.stripMargin
  }
}

object Transactions {
  implicit val rlpEndDec = new RLPEncoder[Transactions] with RLPDecoder[Transactions] {
    override def encode(obj: Transactions): RLPEncodeable = {
      import obj._
      RLPList(txs.map(Transaction.rlpEndDec.encode): _*)
    }

    override def decode(rlp: RLPEncodeable): Transactions = rlp match {
      case rlpList: RLPList => Transactions(rlpList.items.map(Transaction.rlpEndDec.decode))
      case _ => throw new RuntimeException("Cannot decode Transactions")
    }
  }

  val code: Int = 0x10 + 0x02
}

case class Transactions(txs: Seq[Transaction]) extends Message {
  override def code: Int = Transactions.code
}

object Transaction {
  implicit val rlpEndDec = new RLPEncoder[Transaction] with RLPDecoder[Transaction] {
    override def encode(obj: Transaction): RLPEncodeable = {
      import obj._
      RLPList(nonce, gasPrice, gasLimit, receivingAddress.toArray[Byte], value,
        payload.fold(_.byteString.toArray[Byte], _.byteString.toArray[Byte]),
        pointSign, signatureRandom.toArray[Byte], signature.toArray[Byte])
    }

    override def decode(rlp: RLPEncodeable): Transaction = rlp match {
      case RLPList(nonce, gasPrice, gasLimit, (receivingAddress: RLPValue), value, payload, pointSign, signatureRandom, signature)
        if receivingAddress.bytes.nonEmpty =>
        Transaction(nonce, gasPrice, gasLimit, ByteString(receivingAddress: Array[Byte]), value, Right(TransactionData(ByteString(payload: Array[Byte]))),
          pointSign, ByteString(signatureRandom: Array[Byte]), ByteString(signature: Array[Byte]))

      case RLPList(nonce, gasPrice, gasLimit, (receivingAddress: RLPValue), value, payload, pointSign, signatureRandom, signature)
        if receivingAddress.bytes.isEmpty =>
        Transaction(nonce, gasPrice, gasLimit, ByteString(), value, Left(ContractInit(ByteString(payload: Array[Byte]))),
          pointSign, ByteString(signatureRandom: Array[Byte]), ByteString(signature: Array[Byte]))

      case _ => throw new RuntimeException("Cannot decode Transaction")
    }
  }
}

//ETH yellow paper section 4.3
case class Transaction(
    nonce: BigInt,
    gasPrice: BigInt,
    gasLimit: BigInt,
    receivingAddress: ByteString,
    value: BigInt,
    payload: Either[ContractInit, TransactionData],
    //yellow paper appendix F
    pointSign: Byte, //v - 27 or 28 according to yellow paper, but it is 37 and 38 in ETH
    signatureRandom: ByteString, //r
    signature: ByteString /*s*/) {

  val digest: SHA3Digest = new SHA3Digest()

  val bytesToSign: Array[Byte] =

//  {
//    bigIntegerToBytes(nonce.bigInteger, 32) ++
//      bigIntegerToBytes(gasPrice.bigInteger, 32) ++
//      bigIntegerToBytes(gasLimit.bigInteger, 32) ++
//      receivingAddress.toArray[Byte] ++
//      bigIntegerToBytes(value.bigInteger, 32) ++
//      payload.fold(_.byteString.toArray[Byte], _.byteString.toArray[Byte])
//  }

  val senderPublicKey: Option[Array[Byte]] = ECDSASignature.recoverPubBytes(
    new BigInteger(1, signatureRandom.toArray[Byte]),
    new BigInteger(1, signature.toArray[Byte]),
    ECDSASignature.recIdFromSignatureV(pointSign),
    bytesToSign
  )

  val senderAddress: Option[Array[Byte]] = senderPublicKey.map { arr =>
    digest.update(arr, 0, arr.length)
    val result: Array[Byte] = new Array[Byte](digest.getDigestSize)
    digest.doFinal(result, 0)
    result.slice(12, digest.getDigestSize)
  }

  override def toString: String = {
    s"""Transaction {
       |nonce: $nonce
       |gasPrice: $gasPrice
       |gasLimit: $gasLimit
       |receivingAddress: ${Hex.toHexString(receivingAddress.toArray[Byte])}
       |value: $value wei
       |payload: ${payload.fold(init => s"ContractInit [${Hex.toHexString(init.byteString.toArray[Byte])}]", data => s"TransactionData [${Hex.toHexString(data.byteString.toArray[Byte])}]")}
       |pointSign: $pointSign
       |signatureRandom: $signatureRandom
       |signature: $signature
       |senderAddress: ${senderAddress.map(Hex.toHexString)}
       |}""".stripMargin
  }
}

case class ContractInit(byteString: ByteString)

case class TransactionData(byteString: ByteString)

object Disconnect {
  implicit val rlpEndDec = new RLPEncoder[Disconnect] with RLPDecoder[Disconnect] {
    override def encode(obj: Disconnect): RLPEncodeable = {
      RLPList(obj.reason)
    }

    override def decode(rlp: RLPEncodeable): Disconnect = rlp match {
      case rlpList: RLPList =>
        Disconnect(reason = rlpList.items.head)
      case _ => throw new RuntimeException("Cannot decode Disconnect")
    }
  }

  val code = 0x01
}

case class Disconnect(reason: Long) extends Message {
  override val code: Int = Disconnect.code

  override def toString: String = {

    val message = reason match {
      case 0x00 => "Disconnect requested"
      case 0x01 => "TCP sub-system error"
      case 0x03 => "Useless peer"
      case 0x04 => "Too many peers"
      case 0x05 => "Already connected"
      case 0x06 => "Incompatible P2P protocol version"
      case 0x07 => "Null node identity received - this is automatically invalid"
      case 0x08 => "Client quitting"
      case 0x09 => "Unexpected identity"
      case 0x0a => "Identity is the same as this node"
      case 0x0b => "Timeout on receiving a message"
      case 0x10 => "Some other reason specific to a subprotocol"
      case _ => s"unknown code $code"
    }

    s"Disconnect($message)"
  }
}
