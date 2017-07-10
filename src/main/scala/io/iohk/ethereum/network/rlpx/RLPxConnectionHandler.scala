package io.iohk.ethereum.network.rlpx

import java.net.{InetSocketAddress, URI}

import akka.actor._
import akka.io.Tcp._
import akka.io.{IO, Tcp}
import akka.util.ByteString
import io.iohk.ethereum.network.p2p.{Message, MessageDecoder, MessageSerializable}
import io.iohk.ethereum.network.rlpx.RLPxConnectionHandler.RLPxConfiguration
import io.iohk.ethereum.utils.ByteUtils
import org.spongycastle.crypto.AsymmetricCipherKeyPair
import org.spongycastle.util.encoders.Hex

import scala.collection.immutable.Queue
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
  * This actors takes care of initiating a secure connection (auth handshake) between peers.
  * Once such connection is established it allows to send/receive frames (messages) over it.
  *
  * The actor can be in one of four states:
  * 1. when created it waits for initial command (either handle incoming connection or connect usin g uri)
  * 2. when new connection is requested the actor waits for the result (waitingForConnectionResult)
  * 3. once underlying connection is established it either waits for handshake init message or for response message
  *    (depending on who initiated the connection)
  * 4. once handshake is done (and secure connection established) actor can send/receive messages (`handshaked` state)
  */
class RLPxConnectionHandler(
    nodeKey: AsymmetricCipherKeyPair,
    messageDecoder: MessageDecoder,
    protocolVersion: Message.Version,
    authHandshaker: AuthHandshaker,
    messageCodecFactory: (Secrets, MessageDecoder, Message.Version) => MessageCodec,
    rlpxConfiguration: RLPxConfiguration)
  extends Actor with ActorLogging {

  import AuthHandshaker.{InitiatePacketLength, ResponsePacketLength}
  import RLPxConnectionHandler._
  import context.{dispatcher, system}

  val peerId: String = context.parent.path.name

  override def receive: Receive = waitingForCommand

  def waitingForCommand: Receive = {
    case ConnectTo(uri) =>
      IO(Tcp) ! Connect(new InetSocketAddress(uri.getHost, uri.getPort))
      context become waitingForConnectionResult(uri)

    case HandleConnection(connection) =>
      connection ! Register(self)
      val timeout = system.scheduler.scheduleOnce(rlpxConfiguration.waitForHandshakeTimeout, self, AuthHandshakeTimeout)
      context become new ConnectedHandler(connection).waitingForAuthHandshakeInit(authHandshaker, timeout)
  }

  def waitingForConnectionResult(uri: URI): Receive = {
    case Connected(_, _) =>
      val connection = sender()
      connection ! Register(self)
      val (initPacket, handshaker) = authHandshaker.initiate(uri)
      connection ! Write(initPacket)
      val timeout = system.scheduler.scheduleOnce(rlpxConfiguration.waitForHandshakeTimeout, self, AuthHandshakeTimeout)
      context become new ConnectedHandler(connection).waitingForAuthHandshakeResponse(handshaker, timeout)

    case CommandFailed(_: Connect) =>
      log.warning("[Stopping Connection] Connection to {} failed", uri)
      context.parent ! ConnectionFailed
      context stop self
  }

  class ConnectedHandler(connection: ActorRef) {

    def waitingForAuthHandshakeInit(handshaker: AuthHandshaker, timeout: Cancellable): Receive =
      handleTimeout orElse handleConnectionClosed orElse {
        case Received(data) =>
          timeout.cancel()
          Try(handshaker.handleInitialMessage(data.take(InitiatePacketLength))) match {
            case Success((responsePacket, result)) =>
              // process pre-eip8 message
              val remainingData = data.drop(InitiatePacketLength)
              connection ! Write(responsePacket)
              processHandshakeResult(result, remainingData)

            case Failure(_) =>
              // process as eip8 message
              val encryptedPayloadSize = ByteUtils.bigEndianToShort(data.take(2).toArray)
              val (packetData, remainingData) = data.splitAt(encryptedPayloadSize + 2)
              val (responsePacket, result) = handshaker.handleInitialMessageV4(packetData)
              connection ! Write(responsePacket)
              processHandshakeResult(result, remainingData)
          }
      }

    def waitingForAuthHandshakeResponse(handshaker: AuthHandshaker, timeout: Cancellable): Receive =
      handleWriteFailed orElse handleTimeout orElse handleConnectionClosed orElse {
        case Received(data) =>
          timeout.cancel()
          Try(handshaker.handleResponseMessage(data.take(ResponsePacketLength))) match {
            case Success(result) =>
              // process pre-eip8 message
              val remainingData = data.drop(ResponsePacketLength)
              processHandshakeResult(result, remainingData)

            case Failure(_) =>
              // process as eip8 message
              val size = ByteUtils.bigEndianToShort(data.take(2).toArray)
              val (packetData, remainingData) = data.splitAt(size + 2)
              val result = handshaker.handleResponseMessageV4(packetData)
              processHandshakeResult(result, remainingData)
          }
      }

    def handleTimeout: Receive = {
      case AuthHandshakeTimeout =>
        log.warning(s"[Stopping Connection] Auth handshake timeout for peer $peerId")
        context.parent ! ConnectionFailed
        context stop self
    }

    def processHandshakeResult(result: AuthHandshakeResult, remainingData: ByteString): Unit =
      result match {
        case AuthHandshakeSuccess(secrets) =>
          log.debug(s"Auth handshake succeeded for peer $peerId")
          context.parent ! ConnectionEstablished
          val messageCodec = messageCodecFactory(secrets, messageDecoder, protocolVersion)
          val messagesSoFar = messageCodec.readMessages(remainingData)
          messagesSoFar foreach processMessage
          context become handshaked(messageCodec)

        case AuthHandshakeError =>
          log.warning(s"[Stopping Connection] Auth handshake failed for peer $peerId")
          context.parent ! ConnectionFailed
          context stop self
      }

    def processMessage(messageTry: Try[Message]): Unit = messageTry match {
      case Success(message) =>
        context.parent ! MessageReceived(message)

      case Failure(ex) =>
        log.error(ex, s"Cannot decode message from $peerId")
    }

    /**
      * Handles sending and receiving messages from the Akka TCP connection, while also handling acknowledgement of
      * messages sent. Messages are only sent when all Ack from previous messages were received.
      *
      * @param messageCodec, for encoding the messages sent
      * @param messagesNotSent, messages not yet sent
      * @param tcpAckTimeout, timeout for the message sent for which we are awaiting an acknowledgement (if there is one)
      */
    def handshaked(messageCodec: MessageCodec,
                   messagesNotSent: Queue[MessageSerializable] = Queue.empty,
                   tcpAckTimeout: Option[Cancellable] = None): Receive =
      handleWriteFailed orElse handleConnectionClosed orElse {
        case sm: SendMessage =>
          if(tcpAckTimeout.isEmpty) {
            sendMessage(messageCodec, sm.serializable)
            val timeout = system.scheduler.scheduleOnce(rlpxConfiguration.waitForTcpAckTimeout, self, AckTimeout)
            context become handshaked(messageCodec, messagesNotSent, Some(timeout))
          } else
            context become handshaked(messageCodec, messagesNotSent :+ sm.serializable, tcpAckTimeout)

        case Received(data) =>
          val messages = messageCodec.readMessages(data)
          messages foreach processMessage

        case Ack if tcpAckTimeout.nonEmpty =>
          //Cancel pending message timeout
          tcpAckTimeout.foreach(_.cancel())

          //Send next message if there is one
          if(messagesNotSent.nonEmpty) {
            sendMessage(messageCodec, messagesNotSent.head)
            val timeout = system.scheduler.scheduleOnce(rlpxConfiguration.waitForTcpAckTimeout, self, AckTimeout)
            context become handshaked(messageCodec, messagesNotSent.tail, Some(timeout))
          } else
            context become handshaked(messageCodec, Queue.empty, None)

        case AckTimeout if tcpAckTimeout.nonEmpty =>
          tcpAckTimeout.foreach(_.cancel())
          log.warning(s"[Stopping Connection] Write to $peerId failed")
          context stop self
      }

    /**
      * Sends an encoded message through the TCP connection, an Ack will be received when the message was
      * successfully queued for delivery.
      *
      * @param messageCodec
      * @param messageSerializable, message to be sent
      */
    private def sendMessage(messageCodec: MessageCodec, messageSerializable: MessageSerializable): Unit = {
      val out = messageCodec.encodeMessage(messageSerializable)
      connection ! Write(out, Ack)
      log.debug(s"Sent message: $messageSerializable from $peerId")
    }

    def handleWriteFailed: Receive = {
      case CommandFailed(cmd: Write) =>
        log.warning(s"[Stopping Connection] Write to peer $peerId failed, trying to send ${Hex.toHexString(cmd.data.toArray[Byte])}")
        context stop self
    }

    def handleConnectionClosed: Receive = {
      case msg: ConnectionClosed =>
        if (msg.isPeerClosed) {
          log.warning(s"[Stopping Connection] Connection with $peerId closed by peer")
        }
        if(msg.isErrorClosed){
          log.warning(s"[Stopping Connection] Connection with $peerId closed because of error ${msg.getErrorCause}")
        }

        context stop self
    }
  }
}

object RLPxConnectionHandler {
  def props(nodeKey: AsymmetricCipherKeyPair, messageDecoder: MessageDecoder, protocolVersion: Int,
            authHandshaker: AuthHandshaker, rlpxConfiguration: RLPxConfiguration): Props =
    Props(new RLPxConnectionHandler(nodeKey, messageDecoder, protocolVersion, authHandshaker,
      messageCodecFactory, rlpxConfiguration))

  def messageCodecFactory(secrets: Secrets, messageDecoder: MessageDecoder,
                          protocolVersion: Message.Version): MessageCodec =
    new MessageCodec(new FrameCodec(secrets), messageDecoder, protocolVersion)

  case class ConnectTo(uri: URI)

  case class HandleConnection(connection: ActorRef)

  case object ConnectionEstablished

  case object ConnectionFailed

  case class MessageReceived(message: Message)

  case class SendMessage(serializable: MessageSerializable)

  private case object AuthHandshakeTimeout

  case object Ack extends Tcp.Event

  case object AckTimeout

  trait RLPxConfiguration {
    val waitForHandshakeTimeout: FiniteDuration
    val waitForTcpAckTimeout: FiniteDuration
  }

}
