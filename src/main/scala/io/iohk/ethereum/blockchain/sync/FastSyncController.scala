package io.iohk.ethereum.blockchain.sync

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.agent.Agent
import akka.util.ByteString
import io.iohk.ethereum.db.storage._
import io.iohk.ethereum.domain.{BlockHeader, Blockchain}
import io.iohk.ethereum.network.p2p.messages.PV62.{BlockHeaders, GetBlockHeaders}
import io.iohk.ethereum.network.{PeerActor, PeerManagerActor}
import io.iohk.ethereum.network.p2p.messages.CommonMessages.Status
import io.iohk.ethereum.utils.{Config, NodeStatus}

class FastSyncController(
    peerManager: ActorRef,
    nodeStatusHolder: Agent[NodeStatus],
    appStateStorage: AppStateStorage,
    blockchain: Blockchain,
    mptNodeStorage: MptNodeStorage,
    externalSchedulerOpt: Option[Scheduler] = None)
  extends Actor with ActorLogging with BlacklistSupport {

  import BlacklistSupport._
  import Config.FastSync._
  import FastSyncController._

  override val supervisorStrategy =
    OneForOneStrategy() {
      case _ => Stop
    }

  var handshakedPeers: Map[ActorRef, Status] = Map.empty

  scheduler.schedule(0.seconds, peersScanInterval, peerManager, PeerManagerActor.GetPeers)

  override implicit def scheduler: Scheduler = externalSchedulerOpt getOrElse context.system.scheduler

  override def receive: Receive = idle

  def idle: Receive = handlePeerUpdates orElse {
    case StartFastSync =>
      if (peersToDownloadFrom.size >= minPeersToChooseTargetBlock) {
        peersToDownloadFrom.foreach { case (peer, status) =>
          peer ! PeerActor.Subscribe(Set(BlockHeaders.code))
          peer ! PeerActor.SendMessage(GetBlockHeaders(Right(status.bestHash), 1, 0, reverse = false))
        }
        log.info("Asking {} peers for block headers", peersToDownloadFrom.size)
        val timeout = scheduler.scheduleOnce(peerResponseTimeout, self, BlockHeadersTimeout)
        context become waitingForBlockHeaders(peersToDownloadFrom.keySet, Map.empty, timeout)
      } else {
        log.warning("Cannot start fast sync, not enough peers to download from. Scheduling retry in {}", startRetryInterval)
        scheduleStartFastSync(startRetryInterval)
      }
  }

  def waitingForBlockHeaders(waitingFor: Set[ActorRef],
                             received: Map[ActorRef, BlockHeader],
                             timeout: Cancellable): Receive = {
    case PeerActor.MessageReceived(BlockHeaders(blockHeaders)) if blockHeaders.size == 1 =>
      sender() ! PeerActor.Unsubscribe

      val newWaitingFor = waitingFor - sender()
      val newReceived = received + (sender() -> blockHeaders.head)

      if (newWaitingFor.isEmpty) {
        timeout.cancel()
        tryStartSync(newReceived)
      } else context become waitingForBlockHeaders(newWaitingFor, newReceived, timeout)

    case msg @ PeerActor.MessageReceived(BlockHeaders(_)) =>
      sender() ! PeerActor.Unsubscribe
      blacklist(sender(), blacklistDuration)
      context become waitingForBlockHeaders(waitingFor - sender(), received, timeout)

    case BlockHeadersTimeout =>
      waitingFor.foreach { peer =>
        peer ! PeerActor.Unsubscribe
        blacklist(peer, blacklistDuration)
      }
      tryStartSync(received)
  }

  def tryStartSync(receivedHeaders: Map[ActorRef, BlockHeader]): Unit = {
    log.info("Trying to start fast sync. Received {} block headers", receivedHeaders.size)
    if (receivedHeaders.size >= minPeersToChooseTargetBlock) {
      val (mostUpToDatePeer, mostUpToDateBlockHeader) = receivedHeaders.maxBy(_._2.number)
      val targetBlock = mostUpToDateBlockHeader.number - targetBlockOffset

      log.info("Starting fast sync. Asking peer {} for target block header ({})", mostUpToDatePeer.path.name, targetBlock)
      mostUpToDatePeer ! PeerActor.Subscribe(Set(BlockHeaders.code))
      mostUpToDatePeer ! PeerActor.SendMessage(GetBlockHeaders(Left(targetBlock), 1, 0, reverse = false))
      val timeout = scheduler.scheduleOnce(peerResponseTimeout, self, TargetBlockTimeout)
      context become waitingForTargetBlock(mostUpToDatePeer, targetBlock, timeout)
    } else {
      log.info("Did not receive enough status block headers to start fast sync. Retry in {}", startRetryInterval)
      scheduleStartFastSync(startRetryInterval)
      context become idle
    }
  }

  def waitingForTargetBlock(peer: ActorRef,
                            targetBlockNumber: BigInt,
                            timeout: Cancellable): Receive = handlePeerUpdates orElse {
    case PeerActor.MessageReceived(blockHeaders: BlockHeaders) =>
      timeout.cancel()
      peer ! PeerActor.Unsubscribe

      val targetBlockHeaderOpt = blockHeaders.headers.find(header => header.number == targetBlockNumber)
      targetBlockHeaderOpt match {
        case Some(targetBlockHeader) =>
          log.info("Starting fast sync")

          scheduler.schedule(0.seconds, printStatusInterval, self, PrintStatus)
          context become new SyncingHandler(targetBlockHeader).receive

          self ! EnqueueNodes(Seq(StateMptNodeHash(targetBlockHeader.stateRoot)))
          self ! ProcessSyncing

        case None =>
          log.info("Peer ({}) did not respond with target block header, blacklisting and scheduling retry in {}",
            sender().path.name,
            startRetryInterval)

          blacklist(sender(), blacklistDuration)
          scheduleStartFastSync(startRetryInterval)
          context become idle
      }

    case TargetBlockTimeout =>
      log.info("Peer ({}) did not respond with target block header (timeout), blacklisting and scheduling retry in {}",
        sender().path.name,
        startRetryInterval)

      blacklist(sender(), blacklistDuration)
      peer ! PeerActor.Unsubscribe
      scheduleStartFastSync(startRetryInterval)
      context become idle
  }

  def peersToDownloadFrom: Map[ActorRef, Status] = handshakedPeers.filterNot { case (p, s) => isBlacklisted(p) }

  def scheduleStartFastSync(interval: FiniteDuration): Unit = {
    scheduler.scheduleOnce(interval, self, StartFastSync)
  }

  def handlePeerUpdates: Receive = {
    case PeerManagerActor.PeersResponse(peers) =>
      peers.foreach(_.ref ! PeerActor.GetStatus)

    case PeerActor.StatusResponse(PeerActor.Status.Handshaked(initialStatus)) =>
      if (!handshakedPeers.contains(sender()) && !isBlacklisted(sender())) {
        handshakedPeers += (sender() -> initialStatus)
        context watch sender()
      }

    case PeerActor.StatusResponse(_) =>
      removePeer(sender())

    case Terminated(ref) if handshakedPeers.contains(ref) =>
      removePeer(ref)

    case BlacklistPeer(ref) =>
      blacklist(ref, blacklistDuration)

    case UnblacklistPeer(ref) =>
      undoBlacklist(ref)
  }

  def removePeer(peer: ActorRef): Unit = {
    context.unwatch(peer)
    undoBlacklist(peer)
    handshakedPeers -= peer
  }

  class SyncingHandler(targetBlock: BlockHeader) {

    private val blockHeadersHandlerName = "block-headers-request-handler"

    private var nonMptNodesQueue: Set[HashType] = Set.empty
    private var mptNodesQueue: Set[HashType] = Set.empty

    private var blockBodiesQueue: Set[ByteString] = Set.empty
    private var receiptsQueue: Set[ByteString] = Set.empty

    private var downloadedNodesCount: Int = 0

    private var assignedHandlers: Map[ActorRef, ActorRef] = Map.empty

    private var bestBlockHeaderNumber: BigInt = appStateStorage.getBestBlockNumber()

    def receive: Receive = handlePeerUpdates orElse {
      case EnqueueNodes(hashes) =>
        hashes.foreach {
          case h: EvmCodeHash => nonMptNodesQueue += h
          case h: StorageRootHash => nonMptNodesQueue += h
          case h: StateMptNodeHash => mptNodesQueue += h
          case h: ContractStorageMptNodeHash => mptNodesQueue += h
        }

      case EnqueueBlockBodies(hashes) =>
        blockBodiesQueue ++= hashes

      case EnqueueReceipts(hashes) =>
        receiptsQueue ++= hashes

      case UpdateDownloadedNodesCount(num) =>
        downloadedNodesCount += num

      case UpdateBestBlockHeaderNumber(num) =>
        bestBlockHeaderNumber = num

      case ProcessSyncing =>
        processSyncing()

      case FastSyncRequestHandler.Done =>
        context unwatch sender()
        assignedHandlers -= sender()
        processSyncing()

      case Terminated(ref) if assignedHandlers.contains(ref) =>
        context unwatch ref
        assignedHandlers -= ref

      case PrintStatus =>
        val totalNodesCount = downloadedNodesCount + mptNodesQueue.size + nonMptNodesQueue.size
        log.info(
          s"""|Block: ${appStateStorage.getBestBlockNumber()}/${targetBlock.number}.
              |Peers: ${assignedHandlers.size}/${handshakedPeers.size} (${blacklistedPeers.size} blacklisted).
              |State: $downloadedNodesCount/$totalNodesCount known nodes.""".stripMargin.replace("\n", " "))
    }

    def processSyncing(): Unit = {
      if (fullySynced) finish()
      else {
        if (anythingQueued) processQueues()
        else log.info("No more items to request, waiting for {} responses", assignedHandlers.size)
      }
    }

    def finish(): Unit = {
      log.info("Fast sync finished")
      context.parent ! FastSyncDone
      context stop self
    }

    def processQueues(): Unit = {
      if (unassignedPeers.isEmpty) {
        if (assignedHandlers.nonEmpty) {
          log.warning("There are no available peers, waiting for responses")
        } else {
          log.warning("There are no peers to download from, scheduling a retry in {}", syncRetryInterval)
          scheduler.scheduleOnce(syncRetryInterval, self, ProcessSyncing)
        }
      } else {
        unassignedPeers
          .take(maxConcurrentRequests - assignedHandlers.size)
          .foreach(assignWork)
      }
    }

    def assignWork(peer: ActorRef): Unit = {
      if (receiptsQueue.nonEmpty) {
        requestReceipts(peer)
      } else if (blockBodiesQueue.nonEmpty) {
        requestBlockBodies(peer)
      } else if (context.child(blockHeadersHandlerName).isEmpty &&
        targetBlock.number > bestBlockHeaderNumber) {
        requestBlockHeaders(peer)
      } else if (nonMptNodesQueue.nonEmpty || mptNodesQueue.nonEmpty) {
        requestNodes(peer)
      }
    }

    def requestReceipts(peer: ActorRef): Unit = {
      val (receiptsToGet, remainingReceipts) = receiptsQueue.splitAt(receiptsPerRequest)
      val handler = context.actorOf(FastSyncReceiptsRequestHandler.props(
        peer, receiptsToGet.toSeq, appStateStorage, blockchain))
      context watch handler
      assignedHandlers += (handler -> peer)
      receiptsQueue = remainingReceipts
    }

    def requestBlockBodies(peer: ActorRef): Unit = {
      val (blockBodiesToGet, remainingBlockBodies) = blockBodiesQueue.splitAt(blockBodiesPerRequest)
      val handler = context.actorOf(FastSyncBlockBodiesRequestHandler.props(
        peer, blockBodiesToGet.toSeq, appStateStorage, blockchain))
      context watch handler
      assignedHandlers += (handler -> peer)
      blockBodiesQueue = remainingBlockBodies
    }

    def requestBlockHeaders(peer: ActorRef): Unit = {
      val handler = context.actorOf(FastSyncBlockHeadersRequestHandler.props(
        peer, bestBlockHeaderNumber + 1, blockHeadersPerRequest, nodeStatusHolder, blockchain), blockHeadersHandlerName)
      context watch handler
      assignedHandlers += (handler -> peer)
    }

    def requestNodes(peer: ActorRef): Unit = {
      val (nonMptNodesToGet, remainingNonMptNodes) = nonMptNodesQueue.splitAt(nodesPerRequest)
      val (mptNodesToGet, remainingMptNodes) = mptNodesQueue.splitAt(nodesPerRequest - nonMptNodesToGet.size)
      val nodesToGet = nonMptNodesToGet.toSeq ++ mptNodesToGet.toSeq
      val handler = context.actorOf(FastSyncNodesRequestHandler.props(peer, nodesToGet, blockchain, mptNodeStorage))
      context watch handler
      assignedHandlers += (handler -> peer)
      nonMptNodesQueue = remainingNonMptNodes
      mptNodesQueue = remainingMptNodes
    }

    def unassignedPeers: Set[ActorRef] = peersToDownloadFrom.keySet diff assignedHandlers.values.toSet

    def anythingQueued: Boolean =
      nonMptNodesQueue.nonEmpty ||
      mptNodesQueue.nonEmpty ||
      blockBodiesQueue.nonEmpty ||
      receiptsQueue.nonEmpty

    def fullySynced: Boolean =
      bestBlockHeaderNumber >= targetBlock.number &&
      !anythingQueued &&
      assignedHandlers.isEmpty
  }
}

object FastSyncController {
  def props(
             peerManager: ActorRef,
             nodeStatusHolder: Agent[NodeStatus],
             appStateStorage: AppStateStorage,
             blockchain: Blockchain,
             mptNodeStorage: MptNodeStorage):
  Props = Props(new FastSyncController(peerManager, nodeStatusHolder, appStateStorage, blockchain, mptNodeStorage))

  case object StartFastSync

  case class EnqueueNodes(hashes: Seq[HashType])
  case class EnqueueBlockBodies(hashes: Seq[ByteString])
  case class EnqueueReceipts(hashes: Seq[ByteString])

  case class UpdateDownloadedNodesCount(update: Int)
  case class UpdateBestBlockHeaderNumber(bestBlockHeaderNumber: BigInt)

  sealed trait HashType {
    def v: ByteString
  }
  case class StateMptNodeHash(v: ByteString) extends HashType
  case class ContractStorageMptNodeHash(v: ByteString) extends HashType
  case class EvmCodeHash(v: ByteString) extends HashType
  case class StorageRootHash(v: ByteString) extends HashType

  private case object PrintStatus
  private case object TargetBlockTimeout
  private case object BlockHeadersTimeout
  private case object ProcessSyncing
  case object FastSyncDone
}
