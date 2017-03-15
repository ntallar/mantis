package io.iohk.ethereum.blockchain.sync

import akka.actor.{ActorRef, Props, Scheduler}
import akka.util.ByteString
import io.iohk.ethereum.db.storage.AppStateStorage
import io.iohk.ethereum.domain.Blockchain
import io.iohk.ethereum.network.p2p.messages.PV62.{BlockBodies, GetBlockBodies}

class FastSyncBlockBodiesRequestHandler(
    peer: ActorRef,
    requestedHashes: Seq[ByteString],
    appStateStorage: AppStateStorage,
    blockchain: Blockchain)(implicit scheduler: Scheduler)
  extends FastSyncRequestHandler[GetBlockBodies, BlockBodies](peer) {

  override val requestMsg = GetBlockBodies(requestedHashes)
  override val responseMsgCode = BlockBodies.code

  override def handleResponseMsg(blockBodies: BlockBodies): Unit = {
    (requestedHashes zip blockBodies.bodies).foreach { case (hash, body) =>
      blockchain.save(hash, body)
    }

    val receivedHashes = requestedHashes.take(blockBodies.bodies.size)
    updateBestBlockIfNeeded(receivedHashes)

    if (blockBodies.bodies.isEmpty) {
      fastSyncController ! BlacklistSupport.BlacklistPeer(peer)
    }

    val remainingBlockBodies = requestedHashes.drop(blockBodies.bodies.size)
    if (remainingBlockBodies.nonEmpty) {
      fastSyncController ! SyncController.EnqueueBlockBodies(remainingBlockBodies)
    }

    log.info("Received {} block bodies in {} ms", blockBodies.bodies.size, timeTakenSoFar())
    cleanupAndStop()
  }

  private def updateBestBlockIfNeeded(receivedHashes: Seq[ByteString]): Unit = {
    val fullBlocks = receivedHashes.flatMap { hash =>
      for {
        header <- blockchain.getBlockHeaderByHash(hash)
        _ <- blockchain.getReceiptsByHash(hash)
      } yield header
    }

    if (fullBlocks.nonEmpty) {
      val bestReceivedBlock = fullBlocks.maxBy(_.number)
      appStateStorage.putBestBlockNumber(bestReceivedBlock.number)
    }
  }

  override def handleTimeout(): Unit = {
    fastSyncController ! BlacklistSupport.BlacklistPeer(peer)
    fastSyncController ! SyncController.EnqueueBlockBodies(requestedHashes)
    cleanupAndStop()
  }

  override def handleTerminated(): Unit = {
    fastSyncController ! SyncController.EnqueueBlockBodies(requestedHashes)
    cleanupAndStop()
  }

}

object FastSyncBlockBodiesRequestHandler {
  def props(peer: ActorRef, requestedHashes: Seq[ByteString], appStateStorage: AppStateStorage, blockchain: Blockchain)
           (implicit scheduler: Scheduler): Props =
    Props(new FastSyncBlockBodiesRequestHandler(peer, requestedHashes, appStateStorage, blockchain))
}
