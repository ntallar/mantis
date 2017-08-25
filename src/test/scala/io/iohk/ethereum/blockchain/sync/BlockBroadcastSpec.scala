package io.iohk.ethereum.blockchain.sync

import java.net.InetSocketAddress

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.TestProbe
import io.iohk.ethereum.Fixtures
import io.iohk.ethereum.domain.{Block, BlockHeader}
import io.iohk.ethereum.network.{EtcPeerManagerActor, Peer}
import io.iohk.ethereum.network.EtcPeerManagerActor.PeerInfo
import io.iohk.ethereum.network.p2p.messages.CommonMessages.{NewBlock, Status}
import io.iohk.ethereum.network.p2p.messages.PV62.{BlockBody, NewBlockHashes}
import io.iohk.ethereum.network.p2p.messages.{PV62, Versions}
import org.scalatest.{FlatSpec, Matchers}

class BlockBroadcastSpec extends FlatSpec with Matchers  {

  it should "send a new block only when it is not known by the peer (known by comparing total difficulties)" in new TestSetup {
    //given
    //Block that shouldn't be sent as it's number and total difficulty is lower than known by peer
    val firstHeader: BlockHeader = baseBlockHeader.copy(number = initialPeerInfo.maxBlockNumber - 2)
    val firstBlock = NewBlock(Block(firstHeader, BlockBody(Nil, Nil)), initialPeerInfo.totalDifficulty - 2)

    //Block that should be sent as it's total difficulty is higher than known by peer
    val secondHeader: BlockHeader = baseBlockHeader.copy(number = initialPeerInfo.maxBlockNumber - 3)
    val secondBlockNewHashes = NewBlockHashes(Seq(PV62.BlockHash(secondHeader.hash, secondHeader.number)))
    val secondBlock = NewBlock(Block(secondHeader, BlockBody(Nil, Nil)), initialPeerInfo.totalDifficulty + 2)

    //when
    blockBroadcast.broadcastBlocks(Seq(firstBlock, secondBlock), Map(peer -> initialPeerInfo))

    //then
    etcPeerManagerProbe.expectMsg(EtcPeerManagerActor.SendMessage(secondBlock, peer.id))
    etcPeerManagerProbe.expectMsg(EtcPeerManagerActor.SendMessage(secondBlockNewHashes, peer.id))
    etcPeerManagerProbe.expectNoMsg()
  }

  it should "send a new block only when it is not known by the peer (known by comparing max block number)" in new TestSetup {
    //given
    val firstHeader: BlockHeader = baseBlockHeader.copy(number = initialPeerInfo.maxBlockNumber + 4)
    val firstBlockNewHashes = NewBlockHashes(Seq(PV62.BlockHash(firstHeader.hash, firstHeader.number)))
    val firstBlock = NewBlock(Block(firstHeader, BlockBody(Nil, Nil)), initialPeerInfo.totalDifficulty - 2)

    //Block should already be known by the peer due to max block known
    val secondHeader: BlockHeader = baseBlockHeader.copy(number = initialPeerInfo.maxBlockNumber - 2)
    val secondBlock = NewBlock(Block(secondHeader, BlockBody(Nil, Nil)), initialPeerInfo.totalDifficulty - 2)

    //when
    blockBroadcast.broadcastBlocks(Seq(firstBlock, secondBlock), Map(peer -> initialPeerInfo))

    //then
    etcPeerManagerProbe.expectMsg(EtcPeerManagerActor.SendMessage(firstBlock, peer.id))
    etcPeerManagerProbe.expectMsg(EtcPeerManagerActor.SendMessage(firstBlockNewHashes, peer.id))
    etcPeerManagerProbe.expectNoMsg()
  }

  it should "send block hashes to all peers while the blocks only to sqrt of them" in new TestSetup {
    //given
    val firstHeader: BlockHeader = baseBlockHeader.copy(number = initialPeerInfo.maxBlockNumber + 4)
    val firstBlockNewHashes = NewBlockHashes(Seq(PV62.BlockHash(firstHeader.hash, firstHeader.number)))
    val firstBlock = NewBlock(Block(firstHeader, BlockBody(Nil, Nil)), initialPeerInfo.totalDifficulty - 2)

    val peer2Probe = TestProbe()
    val peer2 = Peer(new InetSocketAddress("127.0.0.1", 0), peer2Probe.ref, false)
    val peer3Probe = TestProbe()
    val peer3 = Peer(new InetSocketAddress("127.0.0.1", 0), peer3Probe.ref, false)
    val peer4Probe = TestProbe()
    val peer4 = Peer(new InetSocketAddress("127.0.0.1", 0), peer4Probe.ref, false)

    //when
    val peersWithInfo = Map(
      peer -> initialPeerInfo,
      peer2 -> initialPeerInfo,
      peer3 -> initialPeerInfo,
      peer4 -> initialPeerInfo)
    val blockBroadcastFirstTwoPeers = new BlockBroadcast {
      override val etcPeerManager: ActorRef = etcPeerManagerProbe.ref

      override def obtainRandomPeerSubset(peers: Set[Peer]): Set[Peer] = Set(peer, peer2)
    }
    blockBroadcastFirstTwoPeers.broadcastBlocks(Seq(firstBlock), peersWithInfo)

    //then
    etcPeerManagerProbe.expectMsgAllOf(
      //Only first two peers receive the complete block
      EtcPeerManagerActor.SendMessage(firstBlock, peer.id),
      EtcPeerManagerActor.SendMessage(firstBlock, peer2.id),

      //All the peers should receive the block hashes
      EtcPeerManagerActor.SendMessage(firstBlockNewHashes, peer.id),
      EtcPeerManagerActor.SendMessage(firstBlockNewHashes, peer2.id),
      EtcPeerManagerActor.SendMessage(firstBlockNewHashes, peer3.id),
      EtcPeerManagerActor.SendMessage(firstBlockNewHashes, peer4.id))
    etcPeerManagerProbe.expectNoMsg()
  }

  trait TestSetup {
    implicit val system = ActorSystem("BlockBroadcastSpec_System")

    val etcPeerManagerProbe = TestProbe()

    val blockBroadcast = new BlockBroadcast {
      override val etcPeerManager: ActorRef = etcPeerManagerProbe.ref
    }

    val baseBlockHeader = Fixtures.Blocks.Block3125369.header

    val peerStatus = Status(
      protocolVersion = Versions.PV63,
      networkId = 1,
      totalDifficulty = BigInt(10000),
      bestHash = Fixtures.Blocks.Block3125369.header.hash,
      genesisHash = Fixtures.Blocks.Genesis.header.hash
    )
    val initialPeerInfo = PeerInfo(
      remoteStatus = peerStatus,
      totalDifficulty = peerStatus.totalDifficulty,
      forkAccepted = false,
      maxBlockNumber = Fixtures.Blocks.Block3125369.header.number
    )

    val peerProbe = TestProbe()
    val peer = Peer(new InetSocketAddress("127.0.0.1", 0), peerProbe.ref, false)
  }

}
