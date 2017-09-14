package io.iohk.ethereum.blockchain.sync

import akka.actor._
import io.iohk.ethereum.blockchain.sync.PeerRequestHandler.ResponseReceived
import io.iohk.ethereum.db.storage.AppStateStorage
import io.iohk.ethereum.domain._
import io.iohk.ethereum.ledger._
import io.iohk.ethereum.network.Peer
import io.iohk.ethereum.network.EtcPeerManagerActor.PeerInfo
import io.iohk.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import io.iohk.ethereum.network.PeerEventBusActor.{PeerSelector, Subscribe}
import io.iohk.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import io.iohk.ethereum.network.p2p.messages.CommonMessages.NewBlock
import io.iohk.ethereum.network.p2p.messages.PV62._
import io.iohk.ethereum.transactions.PendingTransactionsManager
import io.iohk.ethereum.ommers.OmmersPool.{AddOmmers, RemoveOmmers}
import io.iohk.ethereum.transactions.PendingTransactionsManager.{AddTransactions, RemoveTransactions}
import io.iohk.ethereum.utils.Config.SyncConfig
import io.iohk.ethereum.validators.Validators
import org.spongycastle.util.encoders.Hex

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global

class RegularSync(
    val appStateStorage: AppStateStorage,
    val blockchain: Blockchain,
    val validators: Validators,
    val etcPeerManager: ActorRef,
    val peerEventBus: ActorRef,
    val ommersPool: ActorRef,
    val pendingTransactionsManager: ActorRef,
    val ledger: Ledger,
    val syncConfig: SyncConfig,
    implicit val scheduler: Scheduler)
  extends Actor with ActorLogging with PeerListSupport with BlacklistSupport with SyncBlocksValidator with BlockBroadcast {

  import RegularSync._
  import syncConfig._

  private var headersQueue: Seq[BlockHeader] = Nil
  private var waitingForActor: Option[ActorRef] = None
  private var resolvingBranches: Boolean = false
  private var resumeRegularSyncTimeout: Option[Cancellable] = None

  scheduler.schedule(printStatusInterval, printStatusInterval, self, PrintStatus)

  peerEventBus ! Subscribe(MessageClassifier(Set(NewBlock.code), PeerSelector.AllPeers))

  def handleCommonMessages: Receive = handlePeerListMessages orElse handleBlacklistMessages

  override def receive: Receive = idle

  def idle: Receive = handleCommonMessages orElse {
    case Start =>
      log.info("Starting block synchronization")
      appStateStorage.fastSyncDone()
      context become running
      askForHeaders()
  }

  def running: Receive = handleCommonMessages orElse handleBroadcastedBlockMessages orElse handleResponseToRequest orElse
    handleMinedBlock orElse {
    case ResumeRegularSync =>
      resumeRegularSync()

    case PrintStatus =>
      log.info(s"Block: ${blockchain.getBestBlockNumber()}. Peers: ${handshakedPeers.size} (${blacklistedPeers.size} blacklisted)")
  }

  private def resumeRegularSync(): Unit = {
    resumeRegularSyncTimeout.foreach(_.cancel)
    resumeRegularSyncTimeout = None
    askForHeaders()
  }

  /**
    * Handles broadcasted blocks, importing them if we're not currently syncing
    */
  def handleBroadcastedBlockMessages: Receive = {
    case MessageFromPeer(NewBlock(newBlock, _), peerId) =>
      //we allow inclusion of new block only if we are not syncing
      if (headersQueue.isEmpty && waitingForActor.isEmpty) {
        val importResult = ledger.importBlock(newBlock)

        importResult match {
          case BlockImportedToTop(td) =>
            broadcastBlock(NewBlock(newBlock, td), handshakedPeers)
            ommersPool ! RemoveOmmers((newBlock.header +: newBlock.body.uncleNodesList).toList)
            pendingTransactionsManager ! PendingTransactionsManager.RemoveTransactions(newBlock.body.transactionList)
            log.debug(s"Added new block ${newBlock.header.number} to the top of the chain received from $peerId")

          case BlockEnqueued =>
            log.debug(s"Block ${newBlock.header.number} (${Hex.toHexString(newBlock.header.hash.toArray)}) from $peerId " +
              s"added to queue")
            ommersPool ! AddOmmers(newBlock.header)

          case DuplicateBlock =>
            log.debug(s"Ignoring duplicate block ${newBlock.header.number} (${Hex.toHexString(newBlock.header.hash.toArray)}) from $peerId")

          case ChainReorganised(oldBranch, newBranch) =>
            ommersPool ! AddOmmers(oldBranch.map(_.header))
            oldBranch.foreach(block => pendingTransactionsManager ! AddTransactions(block.body.transactionList.toList))

            ommersPool ! RemoveOmmers(newBranch.map(_.header))
            newBranch.foreach(block => pendingTransactionsManager ! RemoveTransactions(block.body.transactionList))

            //TODO: broadcast?

            log.debug(s"Imported block ${newBlock.header.number} (${Hex.toHexString(newBlock.header.hash.toArray)}) from $peerId, " +
              s"resulting in chain reorganisation: new branch of length ${newBranch.size} with head at block " +
              s"${newBranch.last.header.number} (${Hex.toHexString(newBranch.last.header.hash.toArray)})")

          case BlockImportFailed(error) =>
            blacklist(peerId, blacklistDuration, error)
        }
      }
  }

  def handleResponseToRequest: Receive = {
    case ResponseReceived(peer: Peer, BlockHeaders(headers), timeTaken) =>
      log.info("Received {} block headers in {} ms", headers.size, timeTaken)
      waitingForActor = None
      if (resolvingBranches) handleBlockBranchResolution(peer, headers.reverse)
      else handleDownload(peer, headers)

    case ResponseReceived(peer, BlockBodies(blockBodies), timeTaken) =>
      log.info("Received {} block bodies in {} ms", blockBodies.size, timeTaken)
      waitingForActor = None
      handleBlockBodies(peer, blockBodies)

    case PeerRequestHandler.RequestFailed(peer, reason) if waitingForActor.contains(sender()) =>
      waitingForActor = None
      if (handshakedPeers.contains(peer)) {
        blacklist(peer.id, blacklistDuration, reason)
      }
      scheduleResume()
  }

  def handleMinedBlock: Receive = {

    //todo improve mined block handling - add info that block was not included because of syncing [EC-250]
    //we allow inclusion of mined block only if we are not syncing / reorganising chain
    case MinedBlock(block) =>
      if (headersQueue.isEmpty && waitingForActor.isEmpty) {
        val importResult = ledger.importBlock(block)

        importResult match {
          case BlockImportedToTop(td) =>
            log.debug(s"Added new mined block ${block.header.number} to top of the chain")
            // TODO: shouldn't we broadcast it?

          case ChainReorganised(oldBranch, newBranch) =>
            log.debug(s"Added new mined block ${block.header.number} resulting in chain reorganization")
            // TODO: ommers? txs? broadcast?

          case DuplicateBlock =>
            log.warning(s"Mined block is a duplicate, this should never happen")

          case BlockEnqueued =>
            log.debug(s"Mined block ${block.header.number} was added to the queue")
            // TODO: ommer?

          case BlockImportFailed(err) =>
            log.warning(s"Failed to execute mined block because of $err")
        }

      } else {
        ommersPool ! AddOmmers(block.header)
      }
  }

  private def askForHeaders() = {
    bestPeer match {
      case Some(peer) =>
        val blockNumber = appStateStorage.getBestBlockNumber()
        requestBlockHeaders(peer, GetBlockHeaders(Left(blockNumber + 1), blockHeadersPerRequest, skip = 0, reverse = false))
        resolvingBranches = false

      case None =>
        log.debug("No peers to download from")
        scheduleResume()
    }
  }

  private def handleBlockBranchResolution(peer: Peer, message: Seq[BlockHeader]) = {
    if (message.nonEmpty && message.last.hash == headersQueue.head.parentHash) {
      headersQueue = message ++ headersQueue
      processBlockHeaders(peer, headersQueue)
    } else {
      //we did not get previous blocks, there is no way to resolve, blacklist peer and continue download
      resumeWithDifferentPeer(peer)
    }
  }

  private def handleDownload(peer: Peer, message: Seq[BlockHeader]) = if (message.nonEmpty) {
    headersQueue = message
    processBlockHeaders(peer, message)
  } else {
    //no new headers to process, schedule to ask again in future, we are at the top of chain
    scheduleResume()
  }

  private def processBlockHeaders(peer: Peer, headers: Seq[BlockHeader]) = ledger.resolveBranch(headers) match {
    case NewBetterBranch(oldBranch) =>
      // TODO: should we postpone handling of old blocks until we receive block bodies for the new branch?
      val transactionsToAdd = oldBranch.flatMap(_.body.transactionList)
      pendingTransactionsManager ! PendingTransactionsManager.AddTransactions(transactionsToAdd.toList)

      val hashes = headers.take(blockBodiesPerRequest).map(_.hash)
      requestBlockBodies(peer, GetBlockBodies(hashes))

      //add first block from branch as ommer
      oldBranch.headOption.foreach { h => ommersPool ! AddOmmers(h.header) }

    case NoChainSwitch =>
      //add first block from branch as ommer
      headersQueue.headOption.foreach { h => ommersPool ! AddOmmers(h) }
      scheduleResume()

    case UnknownBranch =>
      if ((headersQueue.length - 1) / branchResolutionBatchSize >= branchResolutionMaxRequests) {
        log.debug("fail to resolve branch, branch too long, it may indicate malicious peer")
        resumeWithDifferentPeer(peer)
      } else {
        val request = GetBlockHeaders(Right(headersQueue.head.parentHash), branchResolutionBatchSize, skip = 0, reverse = true)
        requestBlockHeaders(peer, request)
        resolvingBranches = true
      }

    case InvalidBranch =>
      log.debug("Got block header that does not have parent")
      resumeWithDifferentPeer(peer)
  }

  private def requestBlockHeaders(peer: Peer, msg: GetBlockHeaders): Unit = {
    waitingForActor = Some(context.actorOf(
      PeerRequestHandler.props[GetBlockHeaders, BlockHeaders](
        peer, peerResponseTimeout, etcPeerManager, peerEventBus,
        requestMsg = msg,
        responseMsgCode = BlockHeaders.code)))
  }

  private def requestBlockBodies(peer: Peer, msg: GetBlockBodies): Unit = {
    waitingForActor = Some(context.actorOf(
      PeerRequestHandler.props[GetBlockBodies, BlockBodies](
        peer, peerResponseTimeout, etcPeerManager, peerEventBus,
        requestMsg = msg,
        responseMsgCode = BlockBodies.code)))
  }

  def getOldBlocks(blockNumbers: Seq[BigInt]): List[Block] = blockNumbers match {
    case Seq(blockNumber, tail @ _*) =>
      blockchain.getBlockByNumber(blockNumber).map(_ :: getOldBlocks(tail)).getOrElse(Nil)
    case Seq() =>
      Nil
  }

  private def handleBlockBodies(peer: Peer, m: Seq[BlockBody]) = {
    if (m.nonEmpty && headersQueue.nonEmpty) {
      val blocks = headersQueue.zip(m).map{ case (header, body) => Block(header, body) }

      @tailrec
      def importBlocks(blocks: List[Block], newBlocks: List[NewBlock] = Nil): (List[NewBlock], Option[Any]) =
        blocks match {
          case Nil =>
            (newBlocks, None)

          case block :: tail =>
            ledger.importBlock(block) match {
              case BlockImportedToTop(td) =>
                importBlocks(tail, NewBlock(block, td) :: newBlocks)

              case ChainReorganised(oldBranch, newBranch) =>
                // revert reorganisation
                // TODO: replace with something like ledger.importBlockOnlyToTop
                newBranch.reverse.foreach(b => blockchain.removeBlock(b.header.hash))
                oldBranch.foreach(ledger.importBlock)
                (newBlocks, Some("Unexpected chain reorganisation"))

              case error @ (DuplicateBlock | BlockEnqueued) =>
                (newBlocks, Some(error))

              case BlockImportFailed(error) =>
                (newBlocks, Some(error))
            }
        }

      val (newBlocks, errorOpt) = importBlocks(blocks.toList)

      if (newBlocks.nonEmpty) {
        log.debug(s"got new blocks up till block: ${newBlocks.last.block.header.number} " +
          s"with hash ${Hex.toHexString(newBlocks.last.block.header.hash.toArray[Byte])}")
      }

      errorOpt match {
        case Some(error) =>
          val numberBlockFailed = blocks.head.header.number + newBlocks.length
          resumeWithDifferentPeer(peer, reason = s"a block execution error: ${error.toString}, in block $numberBlockFailed")
        case None =>
          headersQueue = headersQueue.drop(blocks.length)
          if (headersQueue.nonEmpty) {
            val hashes = headersQueue.take(blockBodiesPerRequest).map(_.hash)
            requestBlockBodies(peer, GetBlockBodies(hashes))
          } else {
            context.self ! ResumeRegularSync
          }
      }
    } else {
      //we got empty response for bodies from peer but we got block headers earlier
      resumeWithDifferentPeer(peer)
    }
  }

  private def scheduleResume() = {
    headersQueue = Nil
    resumeRegularSyncTimeout = Some(scheduler.scheduleOnce(checkForNewBlockInterval, self, ResumeRegularSync))
  }

  private def resumeWithDifferentPeer(currentPeer: Peer, reason: String = "error in response") = {
    blacklist(currentPeer.id, blacklistDuration, reason)
    headersQueue = Nil
    context.self ! ResumeRegularSync
  }

  private def bestPeer: Option[Peer] = {
    val peersToUse = peersToDownloadFrom
      .collect {
        case (ref, PeerInfo(_, totalDifficulty, true, _)) => (ref, totalDifficulty)
      }

    if (peersToUse.nonEmpty) Some(peersToUse.maxBy { case (_, td) => td }._1)
    else None
  }

}

object RegularSync {
  // scalastyle:off parameter.number
  def props(appStateStorage: AppStateStorage, blockchain: Blockchain, validators: Validators,
            etcPeerManager: ActorRef, peerEventBus: ActorRef, ommersPool: ActorRef, pendingTransactionsManager: ActorRef, ledger: Ledger,
            syncConfig: SyncConfig, scheduler: Scheduler): Props =
    Props(new RegularSync(appStateStorage, blockchain, validators, etcPeerManager, peerEventBus, ommersPool, pendingTransactionsManager,
      ledger, syncConfig, scheduler))

  private[sync] case object ResumeRegularSync
  private case class ResolveBranch(peer: ActorRef)
  private case object PrintStatus

  case object Start
  case class MinedBlock(block: Block)
}
