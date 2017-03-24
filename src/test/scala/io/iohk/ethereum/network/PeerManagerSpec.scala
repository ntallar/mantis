package io.iohk.ethereum.network

import java.net.{URI, InetSocketAddress}

import scala.concurrent.ExecutionContext.Implicits.global

import akka.actor._
import akka.agent.Agent
import akka.testkit.{TestActorRef, TestProbe}
import com.miguno.akka.testing.VirtualTime
import io.iohk.ethereum.crypto
import io.iohk.ethereum.utils.{Config, ServerStatus, NodeStatus}
import org.scalatest.{FlatSpec, Matchers}

class PeerManagerSpec extends FlatSpec with Matchers {

  import Config.Network.Discovery._

  "PeerManager" should "try to connect to bootstrap nodes on startup" in new TestSetup {
    val peerManager = TestActorRef(Props(new PeerManagerActor(nodeStatusHolder, peerFactory, Some(time.scheduler))))

    time.advance(800)

    createdPeers.size shouldBe 2
    createdPeers.head.expectMsgAnyOf(PeerActor.ConnectTo(new URI(bootstrapNodes.head)))
    createdPeers(1).expectMsg(PeerActor.ConnectTo(new URI(bootstrapNodes.last)))
  }

  it should "retry connections to remaining bootstrap nodes" in new TestSetup {
    val peerManager = TestActorRef(Props(new PeerManagerActor(nodeStatusHolder, peerFactory, Some(time.scheduler))))

    time.advance(800)

    createdPeers.size shouldBe 2

    createdPeers.head.ref ! PoisonPill

    time.advance(500) // wait for next scan

    createdPeers.size shouldBe 3
    createdPeers(2).expectMsg(PeerActor.ConnectTo(new URI(bootstrapNodes.head)))
  }

  trait TestSetup {
    implicit val system = ActorSystem("PeerManagerActorSpec_System")

    val time = new VirtualTime

    val nodeKey = crypto.generateKeyPair()

    val nodeStatus = NodeStatus(
      key = nodeKey,
      serverStatus = ServerStatus.NotListening)

    val nodeStatusHolder = Agent(nodeStatus)

    var createdPeers: Seq[TestProbe] = Nil

    val peerFactory: (ActorContext, InetSocketAddress) => ActorRef = { (ctx, addr) =>
      val peer = TestProbe()
      createdPeers :+= peer
      peer.ref
    }
  }

}
