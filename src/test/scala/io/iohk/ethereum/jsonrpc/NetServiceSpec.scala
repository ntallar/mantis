package io.iohk.ethereum.jsonrpc

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import akka.agent.Agent
import akka.testkit.TestProbe
import io.iohk.ethereum.crypto
import io.iohk.ethereum.jsonrpc.NetService.{ListeningRequest, ListeningResponse, PeerCountRequest, PeerCountResponse}
import io.iohk.ethereum.network.{PeerActor, PeerManagerActor}
import io.iohk.ethereum.network.PeerManagerActor.Peer
import io.iohk.ethereum.network.p2p.messages.CommonMessages.Status
import io.iohk.ethereum.utils.{NodeStatus, ServerStatus}
import org.scalamock.scalatest.MockFactory
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global

class NetServiceSpec extends FlatSpec with Matchers with MockFactory {

  "NetService" should "return handshaked peer count" in new TestSetup {
    val resF = netService.peerCount(PeerCountRequest())

    peerManager.expectMsg(PeerManagerActor.GetPeers)
    peerManager.reply(PeerManagerActor.Peers(Map(
      Peer(new InetSocketAddress(1), testRef) -> PeerActor.Status.Handshaked(mock[Status], true, 0),
      Peer(new InetSocketAddress(2), testRef) -> PeerActor.Status.Handshaked(mock[Status], true, 0),
      Peer(new InetSocketAddress(3), testRef) -> PeerActor.Status.Connecting)))

    Await.result(resF, Duration.Inf) shouldBe PeerCountResponse(2)
  }

  it should "return listening response" in new TestSetup {
    Await.result(netService.listening(ListeningRequest()), Duration.Inf) shouldBe ListeningResponse(true)
  }

  trait TestSetup {
    implicit val system = ActorSystem("Testsystem")

    val testRef = TestProbe().ref

    val peerManager = TestProbe()

    val nodeStatus = NodeStatus(crypto.generateKeyPair(), ServerStatus.Listening(new InetSocketAddress(9000)))
    val netService = new NetService(Agent(nodeStatus), peerManager.ref)
  }

}
