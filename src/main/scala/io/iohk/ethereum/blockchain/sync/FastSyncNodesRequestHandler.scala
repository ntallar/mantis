package io.iohk.ethereum.blockchain.sync

import akka.actor.{ActorRef, Props, Scheduler}
import akka.util.ByteString
import io.iohk.ethereum.blockchain.sync.FastSync._
import io.iohk.ethereum.crypto._
import io.iohk.ethereum.domain.{Account, Blockchain}
import io.iohk.ethereum.mpt.{BranchNode, ExtensionNode, LeafNode, MptNode, NodesKeyValueStorage}
import io.iohk.ethereum.network.Peer
import io.iohk.ethereum.network.p2p.messages.PV63._
import io.iohk.ethereum.network.p2p.messages.PV63.MptNodeEncoders._
import org.spongycastle.util.encoders.Hex

import scala.concurrent.duration.FiniteDuration

class FastSyncNodesRequestHandler(
    peer: Peer,
    peerResponseTimeout: FiniteDuration,
    etcPeerManager: ActorRef,
    peerMessageBus: ActorRef,
    requestedHashes: Seq[HashType],
    blockchain: Blockchain,
    nodesKeyValueStorage: NodesKeyValueStorage)(implicit scheduler: Scheduler)
  extends SyncRequestHandler[GetNodeData, NodeData](peer, peerResponseTimeout, etcPeerManager, peerMessageBus) {

  override val requestMsg = GetNodeData(requestedHashes.map(_.v))
  override val responseMsgCode: Int = NodeData.code

  override def handleResponseMsg(nodeData: NodeData): Unit = {
    if (nodeData.values.isEmpty) {
      log.debug(s"got empty mpt node response for known hashes switching to blockchain only: ${requestedHashes.map(h => Hex.toHexString(h.v.toArray[Byte]))}")
      syncController ! MarkPeerBlockchainOnly(peer)
    }

    val receivedHashes = nodeData.values.map(v => ByteString(kec256(v.toArray[Byte])))
    val remainingHashes = requestedHashes.filterNot(h => receivedHashes.contains(h.v))
    if (remainingHashes.nonEmpty) {
      syncController ! FastSync.EnqueueNodes(remainingHashes)
    }

    val hashesToRequest = (nodeData.values.indices zip receivedHashes) flatMap { case (idx, valueHash) =>
      requestedHashes.find(_.v == valueHash) map {
        case _: StateMptNodeHash =>
          handleMptNode(nodeData.getMptNode(idx))

        case _: ContractStorageMptNodeHash =>
          handleContractMptNode(nodeData.getMptNode(idx))

        case EvmCodeHash(hash) =>
          val evmCode = nodeData.values(idx)
          blockchain.save(hash, evmCode)
          Nil

        case StorageRootHash(_) =>
          val rootNode = nodeData.getMptNode(idx)
          handleContractMptNode(rootNode)
      }
    }

    syncController ! FastSync.EnqueueNodes(hashesToRequest.flatten)
    syncController ! FastSync.UpdateDownloadedNodesCount(nodeData.values.size)

    log.info("Received {} state nodes in {} ms", nodeData.values.size, timeTakenSoFar())
    cleanupAndStop()
  }

  override def handleTimeout(): Unit = {
    val reason = s"time out on mpt node response for known hashes: ${requestedHashes.map(h => Hex.toHexString(h.v.toArray[Byte]))}"
    syncController ! BlacklistSupport.BlacklistPeer(peer.id, reason)
    syncController ! FastSync.EnqueueNodes(requestedHashes)
    cleanupAndStop()
  }

  override def handleTerminated(): Unit = {
    syncController ! FastSync.EnqueueNodes(requestedHashes)
    cleanupAndStop()
  }

  private def handleMptNode(mptNode: MptNode): Seq[HashType] = mptNode match {
    case n: LeafNode =>
      import AccountImplicits._
      val account = n.value.toArray[Byte].toAccount

      val evm = account.codeHash
      val storage = account.storageRoot

      nodesKeyValueStorage.put(ByteString(n.hash), n.toBytes)

      val evmRequests =
        if (evm != Account.EmptyCodeHash) Seq(EvmCodeHash(evm))
        else Nil

      val storageRequests =
        if (storage != Account.EmptyStorageRootHash) Seq(StorageRootHash(storage))
        else Nil

      evmRequests ++ storageRequests

    case n: BranchNode =>
      val hashes = n.children.collect { case Some(Left(childHash)) => childHash }
      nodesKeyValueStorage.put(ByteString(n.hash), n.toBytes)
      hashes.map(e => StateMptNodeHash(e))

    case n: ExtensionNode =>
      nodesKeyValueStorage.put(ByteString(n.hash), n.toBytes)
      n.next.fold(
        mptHash => Seq(StateMptNodeHash(mptHash)),
        _ => Nil)
  }

  private def handleContractMptNode(mptNode: MptNode): Seq[HashType] = {
    mptNode match {
      case n: LeafNode =>
        nodesKeyValueStorage.put(ByteString(n.hash), n.toBytes)
        Nil

      case n: BranchNode =>
        val hashes = n.children.collect { case Some(Left(childHash)) => childHash }
        nodesKeyValueStorage.put(ByteString(n.hash), n.toBytes)
        hashes.map(e => ContractStorageMptNodeHash(e))

      case n: ExtensionNode =>
        nodesKeyValueStorage.put(ByteString(n.hash), n.toBytes)
        n.next.fold(
          mptHash => Seq(ContractStorageMptNodeHash(mptHash)),
          _ => Nil)
    }
  }
}

object FastSyncNodesRequestHandler {
  def props(peer: Peer, peerTimeout: FiniteDuration, etcPeerManager: ActorRef, peerMessageBus: ActorRef,
            requestedHashes: Seq[HashType], blockchain: Blockchain, nodesKeyValueStorage: NodesKeyValueStorage)
           (implicit scheduler: Scheduler): Props =
    Props(new FastSyncNodesRequestHandler(peer, peerTimeout, etcPeerManager, peerMessageBus, requestedHashes, blockchain, nodesKeyValueStorage))
}
