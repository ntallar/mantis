package io.iohk.ethereum.network.handshaker

import io.iohk.ethereum.network.EtcMessageHandler.EtcPeerInfo
import io.iohk.ethereum.network.ForkResolver
import io.iohk.ethereum.network.handshaker.Handshaker.NextMessage
import io.iohk.ethereum.network.p2p.{Message, MessageSerializable}
import io.iohk.ethereum.network.p2p.messages.CommonMessages.Status
import io.iohk.ethereum.network.p2p.messages.PV62.{BlockHeaders, GetBlockHeaders}
import io.iohk.ethereum.network.p2p.messages.WireProtocol.Disconnect
import io.iohk.ethereum.utils.Logger

case class EtcForkBlockExchangeState(handshakerConfiguration: EtcHandshakerConfiguration,
                                     forkResolver: ForkResolver, remoteStatus: Status) extends InProgressState[EtcPeerInfo] with Logger {

  import handshakerConfiguration._

  def nextMessage: NextMessage =
    NextMessage(
      messageToSend = GetBlockHeaders(Left(forkResolver.forkBlockNumber), maxHeaders = 1, skip = 0, reverse = false),
      timeout = peerConfiguration.waitForChainCheckTimeout
    )

  def applyResponseMessage: PartialFunction[Message, HandshakerState[EtcPeerInfo]] = {

    case BlockHeaders(blockHeaders) =>

      val forkBlockHeaderOpt = blockHeaders.find(_.number == forkResolver.forkBlockNumber)

      forkBlockHeaderOpt match {
        case Some(forkBlockHeader) =>
          val fork = forkResolver.recognizeFork(forkBlockHeader)

          log.info("Peer is running the {} fork", fork)

          if (forkResolver.isAccepted(fork)) {
            log.info("Fork is accepted")
            val peerInfo: EtcPeerInfo = EtcPeerInfo(remoteStatus, remoteStatus.totalDifficulty, true, forkBlockHeader.number)
            ConnectedState(peerInfo)
          } else {
            log.warn("Fork is not accepted")
            DisconnectedState[EtcPeerInfo](Disconnect.Reasons.UselessPeer)
          }

        case None =>
          log.info("Peer did not respond with fork block header")
          ConnectedState(EtcPeerInfo(remoteStatus, remoteStatus.totalDifficulty, false, 0))
      }

  }

  override def respondToRequest(receivedMessage: Message): Option[MessageSerializable] = receivedMessage match {

    case GetBlockHeaders(Left(number), numHeaders, _, _) if number == forkResolver.forkBlockNumber && numHeaders == 1 =>
      log.debug("Received request for fork block")
      blockchain.getBlockHeaderByNumber(number) match {
        case Some(header) => Some(BlockHeaders(Seq(header)))
        case None => Some(BlockHeaders(Nil))
      }

    case _ => None

  }

  def processTimeout: HandshakerState[EtcPeerInfo] =
    DisconnectedState(Disconnect.Reasons.TimeoutOnReceivingAMessage)

}
