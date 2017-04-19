package io.iohk.ethereum.txExecTest.util

import java.io.FileWriter

import akka.actor.{Actor, ActorRef, _}
import akka.util.ByteString
import io.iohk.ethereum.crypto.kec256
import io.iohk.ethereum.domain.{BlockHeader, Receipt}
import io.iohk.ethereum.network.PeerActor.{MessageReceived, SendMessage, Subscribe}
import io.iohk.ethereum.network.PeerManagerActor.{GetPeers, Peer, Peers}
import io.iohk.ethereum.network.p2p.messages.PV62._
import io.iohk.ethereum.network.p2p.messages.PV63.ReceiptImplicits.receiptRlpEncDec
import io.iohk.ethereum.network.p2p.messages.PV63._
import io.iohk.ethereum.rlp.RLPImplicitConversions.toRlpList
import io.iohk.ethereum.rlp.{RLPEncoder, RLPImplicitConversions, encode}
import org.spongycastle.util.encoders.Hex

import scala.collection.immutable.HashMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

class DumpChainActor(peerManager: ActorRef, startBlock: BigInt, maxBlocks: BigInt) extends Actor {
  var stateNodesHashes: Set[ByteString] = Set.empty
  var contractNodesHashes: Set[ByteString] = Set.empty
  var evmCodeHashes: Set[ByteString] = Set.empty

  var blockHeadersStorage: Map[ByteString, BlockHeader] = HashMap.empty
  var blockHeadersHashes: Seq[(BigInt,ByteString)] = Nil
  var blockBodyStorage: Map[ByteString, BlockBody] = HashMap.empty
  var blockReceiptsStorage: Map[ByteString, Seq[Receipt]] = HashMap.empty
  var stateStorage: Map[ByteString, MptNode] = HashMap.empty
  var contractStorage: Map[ByteString, MptNode] = HashMap.empty
  var evmCodeStorage: Map[ByteString, ByteString] = HashMap.empty

  var peers: Seq[Peer] = Nil

  override def preStart(): Unit = {
    context.system.scheduler.scheduleOnce(4 seconds, () => peerManager ! GetPeers)
  }

  // scalastyle:off
  override def receive: Receive = {
    case Peers(p) =>
      //TODO ask for block headers
      peers = p.keys.toSeq
      peers.headOption.foreach { case Peer(_, actor) =>
        actor ! Subscribe(Set(BlockHeaders.code, BlockBodies.code, Receipts.code, NodeData.code))
        actor ! SendMessage(GetBlockHeaders(block = Left(startBlock), maxHeaders = maxBlocks, skip = 0, reverse = false))
      }

    case MessageReceived(m: BlockHeaders) =>
      val mptRoots: Seq[ByteString] = m.headers.map(_.stateRoot)

      blockHeadersHashes = m.headers.map(e => (e.number, e.hash))
      m.headers.foreach { h =>
        blockHeadersStorage = blockHeadersStorage + (h.hash -> h)
      }

      peers.headOption.foreach { case Peer(_, actor) =>
        actor ! SendMessage(GetBlockBodies(m.headers.map(_.hash)))
        actor ! SendMessage(GetReceipts(blockHeadersHashes.filter { case (n, _) => n > 0 }.map { case (_, h) => h }))
        actor ! SendMessage(GetNodeData(mptRoots))
        stateNodesHashes = stateNodesHashes ++ mptRoots.toSet
      }

    case MessageReceived(m: BlockBodies) =>
      m.bodies.zip(blockHeadersHashes).foreach { case (b, (_, h)) =>
        blockBodyStorage = blockBodyStorage + (h -> b)
      }
      val bodiesFile = new FileWriter("bodies.txt", true)
      blockBodyStorage.foreach{case (h,v) =>  bodiesFile.write(s"${Hex.toHexString(h.toArray[Byte])} ${Hex.toHexString(encode(v))}\n")}
      bodiesFile.close()

    case MessageReceived(m: Receipts) =>
      m.receiptsForBlocks.zip(blockHeadersHashes.filter { case (n, _) => n > 0 }).foreach { case (r, (_, h)) =>
        blockReceiptsStorage = blockReceiptsStorage + (h -> r)
      }
      val receiptsFile = new FileWriter("receipts.txt", true)
      blockReceiptsStorage.foreach { case (h, v: Seq[Receipt]) => receiptsFile.write(s"${Hex.toHexString(h.toArray[Byte])} ${Hex.toHexString(encode(toRlpList(v)))}\n") }
      receiptsFile.close()

    case MessageReceived(m: NodeData) =>

      val stateNodes = m.values.filter(node => stateNodesHashes.contains(kec256(node)))
      val contractNodes = m.values.filter(node => contractNodesHashes.contains(kec256(node)))
      val evmCode = m.values.filter(node => evmCodeHashes.contains(kec256(node)))

      val nodes = NodeData(stateNodes).values.indices.map(i => NodeData(stateNodes).getMptNode(i))

      val children = nodes.flatMap {
        case n: MptBranch => n.children.collect { case Left(h: MptHash) if h.hash.nonEmpty => h.hash }
        case MptExtension(_, Left(h)) => Seq(h.hash)
        case n: MptLeaf => Seq.empty
        case _ => Seq.empty
      }

      var contractChildren: Seq[ByteString] = Nil
      var evmTorequest: Seq[ByteString] = Nil

      nodes.foreach {
        case n: MptLeaf =>
          if (n.getAccount.codeHash != DumpChainActor.emptyEvm) {
            peers.headOption.foreach { case Peer(_, actor) =>
              evmTorequest = evmTorequest :+ n.getAccount.codeHash
              evmCodeHashes = evmCodeHashes + n.getAccount.codeHash
            }
          }
          if (n.getAccount.storageRoot != DumpChainActor.emptyStorage) {
            peers.headOption.foreach { case Peer(_, actor) =>
              contractChildren = contractChildren :+ n.getAccount.storageRoot
              contractNodesHashes = contractNodesHashes + n.getAccount.storageRoot
            }
          }
        case _ =>
      }

      val cNodes = NodeData(contractNodes).values.indices.map(i => NodeData(contractNodes).getMptNode(i))
      contractChildren = contractChildren ++ cNodes.flatMap {
        case n: MptBranch => n.children.collect { case Left(h: MptHash) if h.hash.nonEmpty => h.hash }
        case MptExtension(_, Left(h)) => Seq(h.hash)
        case _ => Seq.empty
      }

      if (children.isEmpty && contractChildren.isEmpty && evmTorequest.isEmpty) {
        val headersFile = new FileWriter("headers.txt", true)
        val stateTreeFile = new FileWriter("stateTree.txt", true)
        val contractTreesFile = new FileWriter("contractTrees.txt", true)
        val evmCodeFile = new FileWriter("evmCode.txt", true)

        def dumpToFile[T](fw: FileWriter, element: (ByteString, T))(implicit enc: RLPEncoder[T]): Unit = element match {
          case (h, v) => fw.write(s"${Hex.toHexString(h.toArray[Byte])} ${Hex.toHexString(encode(v))}\n")
        }

        blockHeadersStorage.foreach(dumpToFile(headersFile, _)(BlockHeaderImplicits.headerRlpEncDec))
        stateStorage.foreach(dumpToFile(stateTreeFile, _))
        contractStorage.foreach(dumpToFile(contractTreesFile, _))
        evmCodeStorage.foreach { case (h, v) => evmCodeFile.write(s"${Hex.toHexString(h.toArray[Byte])} ${Hex.toHexString(v.toArray[Byte])}\n") }

        headersFile.close()
        stateTreeFile.close()
        contractTreesFile.close()
        evmCodeFile.close()
        println("chain dumped to file")
      } else {
        peers.headOption.foreach { case Peer(_, actor) =>
          actor ! SendMessage(GetNodeData(children ++ contractChildren ++ evmTorequest))
          stateNodesHashes = stateNodesHashes ++ children.toSet
          contractNodesHashes = contractNodesHashes ++ contractChildren.toSet
        }

        evmCode.foreach { e =>
          evmCodeStorage = evmCodeStorage + (kec256(e) -> e)
        }

        nodes.foreach { n =>
          stateStorage = stateStorage + (n.hash -> n)
        }

        cNodes.foreach { n =>
          contractStorage = contractStorage + (n.hash -> n)
        }
      }
  }
}

object DumpChainActor {
  def props(peerManager: ActorRef, startBlock: BigInt, maxBlocks: BigInt): Props =
    Props(new DumpChainActor(peerManager, startBlock: BigInt, maxBlocks: BigInt))
  val emptyStorage = ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))
  val emptyEvm = ByteString(Hex.decode("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470"))
}