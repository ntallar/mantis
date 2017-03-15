package io.iohk.ethereum.blockchain.sync

import akka.actor.{ActorRef, Props, Scheduler}
import akka.util.ByteString
import io.iohk.ethereum.blockchain.sync.SyncController._
import io.iohk.ethereum.crypto._
import io.iohk.ethereum.db.storage.MptNodeStorage
import io.iohk.ethereum.domain.{Account, Blockchain}
import io.iohk.ethereum.network.p2p.messages.PV63._

class FastSyncNodesRequestHandler(
    peer: ActorRef,
    requestedHashes: Seq[HashType],
    blockchain: Blockchain,
    mptNodeStorage: MptNodeStorage)(implicit scheduler: Scheduler)
  extends FastSyncRequestHandler[GetNodeData, NodeData](peer) {

  override val requestMsg = GetNodeData(requestedHashes.map(_.v))
  override val responseMsgCode = NodeData.code

  override def handleResponseMsg(nodeData: NodeData): Unit = {
    if (nodeData.values.isEmpty) {
      fastSyncController ! BlacklistSupport.BlacklistPeer(peer)
    }

    val receivedHashes = nodeData.values.map(v => ByteString(kec256(v.toArray[Byte])))
    val remainingHashes = requestedHashes.filterNot(h => receivedHashes.contains(h.v))
    if (remainingHashes.nonEmpty) {
      fastSyncController ! SyncController.EnqueueNodes(remainingHashes)
    }

    val hashesToRequest = (nodeData.values.indices zip receivedHashes) flatMap { case (idx, valueHash) =>
      requestedHashes.find(_.v == valueHash) map {
        case StateMptNodeHash(hash) =>
          handleMptNode(hash, nodeData.getMptNode(idx))

        case ContractStorageMptNodeHash(hash) =>
          handleContractMptNode(hash, nodeData.getMptNode(idx))

        case EvmCodeHash(hash) =>
          val evmCode = nodeData.values(idx)
          blockchain.save(hash, evmCode)
          Nil

        case StorageRootHash(hash) =>
          val rootNode = nodeData.getMptNode(idx)
          handleContractMptNode(hash, rootNode)
      }
    }

    fastSyncController ! SyncController.EnqueueNodes(hashesToRequest.flatten)
    fastSyncController ! SyncController.UpdateDownloadedNodesCount(nodeData.values.size)

    log.info("Received {} state nodes in {} ms", nodeData.values.size, timeTakenSoFar())
    cleanupAndStop()
  }

  override def handleTimeout(): Unit = {
    fastSyncController ! BlacklistSupport.BlacklistPeer(peer)
    fastSyncController ! SyncController.EnqueueNodes(requestedHashes)
    cleanupAndStop()
  }

  override def handleTerminated(): Unit = {
    fastSyncController ! SyncController.EnqueueNodes(requestedHashes)
    cleanupAndStop()
  }

  private def handleMptNode(hash: ByteString, mptNode: MptNode): Seq[HashType] = mptNode match {
    case n: MptLeaf =>
      val evm = n.getAccount.codeHash
      val storage = n.getAccount.storageRoot

      mptNodeStorage.put(n)

      val evmRequests =
        if (evm != Account.EmptyCodeHash) Seq(EvmCodeHash(evm))
        else Nil

      val storageRequests =
        if (storage != Account.EmptyStorageRootHash) Seq(StorageRootHash(storage))
        else Nil

      evmRequests ++ storageRequests

    case n: MptBranch =>
      val hashes = n.children.collect { case Left(MptHash(childHash)) => childHash }.filter(_.nonEmpty)
      mptNodeStorage.put(n)
      hashes.map(StateMptNodeHash)

    case n: MptExtension =>
      mptNodeStorage.put(n)
      n.child.fold(
        mptHash => Seq(StateMptNodeHash(mptHash.hash)),
        _ => Nil)
    }

  private def handleContractMptNode(hash: ByteString, mptNode: MptNode): Seq[HashType] = {
    mptNode match {
      case n: MptLeaf =>
        mptNodeStorage.put(n)
        Nil

      case n: MptBranch =>
        val hashes = n.children.collect { case Left(MptHash(childHash)) => childHash }.filter(_.nonEmpty)
        mptNodeStorage.put(n)
        hashes.map(ContractStorageMptNodeHash)

      case n: MptExtension =>
        mptNodeStorage.put(n)
        n.child.fold(
          mptHash => Seq(ContractStorageMptNodeHash(mptHash.hash)),
          _ => Nil)
    }
  }
}

object FastSyncNodesRequestHandler {
  def props(peer: ActorRef, requestedHashes: Seq[HashType], blockchain: Blockchain, mptNodeStorage: MptNodeStorage)
           (implicit scheduler: Scheduler): Props =
    Props(new FastSyncNodesRequestHandler(peer, requestedHashes, blockchain, mptNodeStorage))
}
