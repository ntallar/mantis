package io.iohk.ethereum.network

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props}
import akka.agent.Agent
import akka.util.ByteString
import io.iohk.ethereum.db.storage.AppStateStorage
import io.iohk.ethereum.domain.{Block, BlockHeader, Blockchain}
import io.iohk.ethereum.network.PeerActor.MessageReceived
import io.iohk.ethereum.network.PeerManagerActor.{GetPeers, Peer}
import io.iohk.ethereum.network.p2p.messages.CommonMessages.NewBlock
import io.iohk.ethereum.network.p2p.messages.PV62
import io.iohk.ethereum.network.p2p.messages.PV62._
import io.iohk.ethereum.network.p2p.validators.BlockHeaderError.HeaderParentNotFoundError
import io.iohk.ethereum.network.p2p.validators.{BlockHeaderValidator, BlockValidator}
import io.iohk.ethereum.utils.NodeStatus
import org.spongycastle.util.encoders.Hex

//FIXME: Handle peers not responding to block headers and bodies request [EC-107]
class BlockBroadcastActor(
  nodeStatusHolder: Agent[NodeStatus],
  peer: ActorRef,
  peerManagerActor: ActorRef,
  appStateStorage: AppStateStorage,
  blockchain: Blockchain) extends Actor with ActorLogging {

  import BlockBroadcastActor._

  override val supervisorStrategy =
    OneForOneStrategy() {
      case _ => Stop
    }

  override def receive: Receive = idle

  private val msgsToSubscribe = Set(NewBlock.code, PV62.NewBlockHashes.code, BlockHeaders.code, BlockBodies.code)

  def idle: Receive = {
    case StartBlockBroadcast =>
      peer ! PeerActor.Subscribe(msgsToSubscribe)
      context become processMessages(ProcessingState(Nil, Nil, Nil, Nil))
  }

  def processMessages(state: ProcessingState): Receive =
    handleReceivedMessages(state) orElse {

      case ProcessNewBlocks if state.unprocessedBlocks.nonEmpty =>
        if(state.unprocessedBlocks.tail.nonEmpty) self ! ProcessNewBlocks

        val blockToProcess = state.unprocessedBlocks.head
        val blockHeader = BlockHeaderValidator.validate(blockToProcess.header, blockchain)
        val blockParentTotalDifficulty = blockchain.getTotalDifficultyByHash(blockToProcess.header.parentHash)

        (blockHeader, blockParentTotalDifficulty) match {
          case (Right(_), Some(parentTD)) =>
            val blockToProcessDifficulty = parentTD + blockToProcess.header.difficulty
            importBlockToBlockchain(blockToProcess, blockToProcessDifficulty)

            peerManagerActor ! GetPeers
            val newState = state.copy(
              unprocessedBlocks = state.unprocessedBlocks.tail,
              toBroadcastBlocks = state.toBroadcastBlocks :+ NewBlock(blockToProcess, blockToProcessDifficulty)
            )
            context become processMessages(newState)

          case (Left(HeaderParentNotFoundError), _) | (Right(_), None) =>
            log.info("Block parent not found, block {} will not be broadcasted", Hex.toHexString(blockToProcess.header.hash.toArray))
            context become processMessages(state.copy(unprocessedBlocks = state.unprocessedBlocks.tail))

          case (Left(_), _) =>
            log.info("Block {} not valid", Hex.toHexString(blockToProcess.header.hash.toArray))
            context become processMessages(state.copy(unprocessedBlocks = state.unprocessedBlocks.tail))
        }

      case PeerManagerActor.PeersResponse(peers) if state.toBroadcastBlocks.nonEmpty =>
        sendNewBlockMsgToPeers(peers, state.toBroadcastBlocks)
        context become processMessages(state.copy(toBroadcastBlocks = Nil))

      case _ => //Nothing
    }

  def handleReceivedMessages(state: ProcessingState): Receive = {

    case MessageReceived(m: NewBlock) =>
      val newBlock = Block(m.block.header, m.block.body)
      if(blockToProcess(newBlock.header.hash, state)){
        log.info("Got NewBlock message {}", Hex.toHexString(newBlock.header.hash.toArray))
        self ! ProcessNewBlocks
        context become processMessages(state.copy(unprocessedBlocks = state.unprocessedBlocks :+ newBlock))
      }

    case MessageReceived(m: PV62.NewBlockHashes) =>
      val newHashes = m.hashes.map(_.hash)
      val newHashesToProcess = newHashes.filter(hash => blockToProcess(hash, state))
      log.info("Got NewBlockHashes message {}", newHashes.map( hash => Hex.toHexString(hash.toArray)))
      newHashesToProcess.foreach{ hash =>
        val getBlockHeadersMsg = GetBlockHeaders(block = Right(hash), maxHeaders = 1, skip = 0, reverse =  false)
        peer ! PeerActor.SendMessage(getBlockHeadersMsg)
      }
      context become processMessages(state.copy(fetchedBlockHashes = state.fetchedBlockHashes ++ newHashesToProcess))

    case MessageReceived(BlockHeaders(Seq(blockHeader))) if state.fetchedBlockHashes.contains(blockHeader.hash)=>
      log.info("Got BlockHeaders message {}", blockHeader)
      val newFetchedBlockHeaders = state.fetchedBlockHashes.filterNot(_ == blockHeader.hash)
      peer ! PeerActor.SendMessage(GetBlockBodies(Seq(blockHeader.hash)))
      val newState = state.copy(fetchedBlockHashes = newFetchedBlockHeaders, blockHeaders = state.blockHeaders :+ blockHeader)
      context become processMessages(newState)

    case MessageReceived(BlockBodies(Seq(blockBody))) =>
      val block: Option[Block] = matchHeaderAndBody(state.blockHeaders, blockBody)
      block foreach { b =>
        log.info("Got BlockBodies message {}", blockBody)
        val newBlockHeaders = state.blockHeaders.filterNot(_.hash == b.header.hash)
        val newState = state.copy(unprocessedBlocks = state.unprocessedBlocks :+ b, blockHeaders = newBlockHeaders)
        self ! ProcessNewBlocks
        context become processMessages(newState)
      }

  }

  private def blockInProgress(hash: BlockHash, state: ProcessingState): Boolean =
    (state.unprocessedBlocks.map(_.header.hash) ++
      state.toBroadcastBlocks.map(_.block.header.hash) ++
      state.fetchedBlockHashes ++
      state.blockHeaders.map(_.hash)).contains(hash)

  private def blockInStorage(hash: BlockHash): Boolean =
    blockchain.getBlockByHash(hash).isDefined

  private def blockToProcess(hash: BlockHash, state: ProcessingState): Boolean = !blockInProgress(hash, state) && !blockInStorage(hash)

  //FIXME: Decide block propagation algorithm (for now we send block to every peer except the sender) [EC-87]
  private def sendNewBlockMsgToPeers(peers: Seq[Peer], newBlocks: Seq[NewBlock]) = {
    peers.foreach{ p =>
      if(p.id != peer.path.name) context.actorOf(BlockBroadcastMaxBlockRequestHandler.props(p.ref, newBlocks))
    }
  }

  private def matchHeaderAndBody(blockHeaders: Seq[BlockHeader], blockBody: BlockBody): Option[Block] =
    blockHeaders.collectFirst{ case blockHeader if BlockValidator.validateHeaderAndBody(blockHeader, blockBody).isRight =>
      Block(blockHeader, blockBody)
    }

  private def importBlockToBlockchain(block: Block, blockTotalDifficulty: BigInt) = {
    val blockHash = block.header.hash

    //Insert block to blockchain
    blockchain.save(block)
    blockchain.save(blockHash, blockTotalDifficulty)

    //Update best block number in app state
    appStateStorage.putBestBlockNumber(block.header.number)
  }
}

object BlockBroadcastActor {
  type BlockHash = ByteString

  def props(nodeStatusHolder: Agent[NodeStatus],
            peer: ActorRef,
            peerManagerActor: ActorRef,
            appStateStorage: AppStateStorage,
            blockchain: Blockchain): Props = {
    Props(new BlockBroadcastActor(nodeStatusHolder, peer, peerManagerActor, appStateStorage, blockchain))
  }

  case object StartBlockBroadcast
  case object ProcessNewBlocks

  case class ProcessingState(unprocessedBlocks: Seq[Block],
                             toBroadcastBlocks: Seq[NewBlock],
                             fetchedBlockHashes: Seq[BlockHash],
                             blockHeaders: Seq[BlockHeader])
}
