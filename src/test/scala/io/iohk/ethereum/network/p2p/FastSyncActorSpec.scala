package io.iohk.ethereum.network.p2p

import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestProbe}
import akka.util.ByteString
import io.iohk.ethereum.blockchain
import io.iohk.ethereum.blockchain.{Blockchain, BlockchainComp, BlockchainCompImpl}
import io.iohk.ethereum.crypto
import io.iohk.ethereum.db.components.{SharedEphemDataSources, SharedIodbDataSources, Storages, StoragesComp}
import io.iohk.ethereum.db.components.Storages.DefaultStorages
import io.iohk.ethereum.domain.{Block, BlockHeader}
import io.iohk.ethereum.network.FastSyncActor.{FastSyncDone, SyncFailure}
import io.iohk.ethereum.network.PeerActor.MessageReceived
import io.iohk.ethereum.network.p2p.messages.PV62._
import io.iohk.ethereum.network.p2p.messages.PV63._
import io.iohk.ethereum.network.{FastSyncActor, PeerActor}
import org.scalatest.{FlatSpec, Matchers}
import org.spongycastle.util.encoders.Hex

class FastSyncActorSpec extends FlatSpec with Matchers {


  "FastSyncActor" should "subscribe for messages and ask for basic info" in new TestSetup {
    //when
    fastSync ! FastSyncActor.StartSync(targetBlockHash)

    //then
    peer.expectMsg(PeerActor.Subscribe(Set(NodeData.code, Receipts.code, BlockBodies.code, BlockHeaders.code)))
    peer.expectMsgClass(classOf[PeerActor.SendMessage[GetBlockHeaders]])

    peer.reply(MessageReceived(BlockHeaders(Seq(targetBlockHeader))))

    peer.expectMsg(PeerActor.SendMessage(GetNodeData(Seq(targetBlockHeader.stateRoot))))
    peer.expectMsgClass(classOf[PeerActor.SendMessage[GetBlockHeaders]])

  }

  it should "recursively process chain" in new TestSetup {
    // before
    fastSync ! FastSyncActor.StartSync(targetBlockHash)

    peer.expectMsgClass(classOf[PeerActor.Subscribe])
    peer.expectMsgClass(classOf[PeerActor.SendMessage[GetBlockHeaders]])

    peer.reply(MessageReceived(BlockHeaders(Seq(targetBlockHeader))))

    peer.expectMsgClass(classOf[PeerActor.SendMessage[GetNodeData]])
    peer.expectMsgClass(classOf[PeerActor.SendMessage[GetBlockHeaders]])

    //when
    peer.reply(MessageReceived(BlockHeaders(responseBlockHeaders)))

    //then
    peer.expectMsg(PeerActor.SendMessage(GetBlockBodies(blockHashes)))
    peer.expectMsg(PeerActor.SendMessage(GetReceipts(blockHashes)))
  }

  it should "stop when chain and mpt is downloaded" in new TestSetup {
    // before
    fastSync ! FastSyncActor.StartSync(targetBlockHash)

    peer.expectMsgClass(classOf[PeerActor.Subscribe])
    peer.expectMsgClass(classOf[PeerActor.SendMessage[GetBlockHeaders]])

    peer.reply(MessageReceived(BlockHeaders(Seq(targetBlockHeader))))

    peer.expectMsgClass(classOf[PeerActor.SendMessage[GetNodeData]])
    peer.expectMsgClass(classOf[PeerActor.SendMessage[GetBlockHeaders]])

    peer.reply(MessageReceived(BlockHeaders(Seq(targetBlockHeader))))

    peer.expectMsgClass(classOf[PeerActor.SendMessage[GetBlockBodies]])
    peer.expectMsgClass(classOf[PeerActor.SendMessage[GetReceipts]])

    //when
    peer.reply(MessageReceived(BlockBodies(Seq(BlockBody(transactionList = Seq.empty, uncleNodesList = Seq.empty)))))
    peer.reply(MessageReceived(Receipts(Seq(Seq.empty))))
    peer.reply(MessageReceived(NodeData(Seq(stateMptLeafWithAccount))))

    //then
    peer.expectMsgClass(classOf[FastSyncDone])

    val block = blockchain.getBlockByHash(targetBlockHeader.hash)
    block shouldBe Some(Block(targetBlockHeader, BlockBody(transactionList = Seq.empty, uncleNodesList = Seq.empty)))
    blockchain.getReceiptsByHash(targetBlockHeader.hash) shouldBe Some(Seq.empty)

    mptNodeStorage.get(targetBlockHeader.stateRoot) shouldBe Some(NodeData(Seq(stateMptLeafWithAccount)).getMptNode(0))
  }

  it should "send failure message when got malformed response" in new TestSetup {
    // before
    fastSync ! FastSyncActor.StartSync(targetBlockHash)

    peer.expectMsgClass(classOf[PeerActor.Subscribe])
    peer.expectMsgClass(classOf[PeerActor.SendMessage[GetBlockHeaders]])

    peer.reply(MessageReceived(BlockHeaders(Seq(targetBlockHeader))))

    peer.expectMsgClass(classOf[PeerActor.SendMessage[GetNodeData]])
    peer.expectMsgClass(classOf[PeerActor.SendMessage[GetBlockHeaders]])


    peer.reply(MessageReceived(BlockHeaders(responseBlockHeaders)))

    peer.expectMsg(PeerActor.SendMessage(GetBlockBodies(blockHashes)))
    peer.expectMsg(PeerActor.SendMessage(GetReceipts(blockHashes)))

    //when
    peer.reply(MessageReceived(Receipts(Seq())))

    //then
    peer.expectMsg(SyncFailure)
  }

  it should "get state MPT nodes" in new TestSetup {
    // before
    val stateRootHash = ByteString(Hex.decode("2b84be521a9825016427a81d946c43a79ca2a4e1c89a55a6d8cda21719f799ec"))
    fastSync ! FastSyncActor.StartSync(targetBlockHash)

    peer.expectMsgClass(classOf[PeerActor.Subscribe])
    peer.expectMsgClass(classOf[PeerActor.SendMessage[GetBlockHeaders]])

    peer.reply(MessageReceived(BlockHeaders(Seq(targetBlockHeader))))

    peer.expectMsgClass(classOf[PeerActor.SendMessage[GetNodeData]])
    peer.expectMsgClass(classOf[PeerActor.SendMessage[GetBlockHeaders]])

    peer.reply(MessageReceived(BlockHeaders(Seq(targetBlockHeader.copy(stateRoot = stateRootHash)))))

    peer.expectMsgClass(classOf[PeerActor.SendMessage[GetBlockBodies]])
    peer.expectMsgClass(classOf[PeerActor.SendMessage[GetReceipts]])

    //when
    peer.reply(MessageReceived(NodeData(Seq(mptBranchWithTwoChild))))

    //then
    peer.expectMsgClass(classOf[PeerActor.SendMessage[GetNodeData]])
    peer.reply(MessageReceived(NodeData(Seq(mptExtension, stateMptLeafWithAccount))))
    peer.expectMsgClass(classOf[PeerActor.SendMessage[GetNodeData]])
    peer.reply(MessageReceived(NodeData(Seq(stateMptLeafWithAccount))))

    mptNodeStorage.get(stateRootHash) shouldBe Some(NodeData(Seq(mptBranchWithTwoChild)).getMptNode(0))

    mptNodeStorage.get(NodeData(Seq(mptBranchWithTwoChild)).getMptNode(0)
      .asInstanceOf[MptBranch].children(0).asInstanceOf[Left[MptHash, MptValue]].a.hash) shouldBe Some(NodeData(Seq(mptExtension)).getMptNode(0))

    mptNodeStorage.get(NodeData(Seq(mptBranchWithTwoChild)).getMptNode(0)
      .asInstanceOf[MptBranch].children(1).asInstanceOf[Left[MptHash, MptValue]].a.hash) shouldBe Some(NodeData(Seq(stateMptLeafWithAccount)).getMptNode(0))

    mptNodeStorage.get(NodeData(Seq(mptExtension)).getMptNode(0)
      .asInstanceOf[MptExtension].child.asInstanceOf[Left[MptHash, MptValue]].a.hash) shouldBe Some(NodeData(Seq(stateMptLeafWithAccount)).getMptNode(0))
  }

  it should "get contract MPT nodes" in new TestSetup {
    // before
    val stateRootHash = ByteString(Hex.decode("b873d163a301e493dd3ec03cd47940149292ebef988c9d9ecefb245c08ef96ed"))
    fastSync ! FastSyncActor.StartSync(targetBlockHash)

    peer.expectMsgClass(classOf[PeerActor.Subscribe])
    peer.expectMsgClass(classOf[PeerActor.SendMessage[GetBlockHeaders]])

    peer.reply(MessageReceived(BlockHeaders(Seq(targetBlockHeader))))

    peer.expectMsgClass(classOf[PeerActor.SendMessage[GetNodeData]])
    peer.expectMsgClass(classOf[PeerActor.SendMessage[GetBlockHeaders]])

    peer.reply(MessageReceived(BlockHeaders(Seq(targetBlockHeader.copy(stateRoot = stateRootHash)))))

    peer.expectMsgClass(classOf[PeerActor.SendMessage[GetBlockBodies]])
    peer.expectMsgClass(classOf[PeerActor.SendMessage[GetReceipts]])

    //when
    peer.reply(MessageReceived(NodeData(Seq(stateMptLeafWithContractAndNotEmptyStorage))))

    //then
    peer.expectMsgClass(classOf[PeerActor.SendMessage[GetNodeData]])
    peer.reply(MessageReceived(NodeData(Seq(contractMptBranchWithTwoChild, contractEvmCode))))
    peer.expectMsgClass(classOf[PeerActor.SendMessage[GetNodeData]])
    peer.reply(MessageReceived(NodeData(Seq(contractMptExtension, contractMptLeafNode))))
    peer.expectMsgClass(classOf[PeerActor.SendMessage[GetNodeData]])
    peer.reply(MessageReceived(NodeData(Seq(contractMptLeafNode))))

    blockchain.getEvmCodeByHash(ByteString(crypto.sha3(contractEvmCode.toArray[Byte])))

    mptNodeStorage.get(stateRootHash) shouldBe Some(NodeData(Seq(contractMptBranchWithTwoChild)).getMptNode(0))

    mptNodeStorage.get(NodeData(Seq(contractMptBranchWithTwoChild)).getMptNode(0)
      .asInstanceOf[MptBranch].children(1).asInstanceOf[Left[MptHash, MptValue]].a.hash) shouldBe Some(NodeData(Seq(contractMptExtension)).getMptNode(0))

    mptNodeStorage.get(NodeData(Seq(contractMptBranchWithTwoChild)).getMptNode(0)
      .asInstanceOf[MptBranch].children(0).asInstanceOf[Left[MptHash, MptValue]].a.hash) shouldBe Some(NodeData(Seq(contractMptLeafNode)).getMptNode(0))

    mptNodeStorage.get(NodeData(Seq(contractMptExtension)).getMptNode(0)
      .asInstanceOf[MptExtension].child.asInstanceOf[Left[MptHash, MptValue]].a.hash) shouldBe Some(NodeData(Seq(contractMptLeafNode)).getMptNode(0))
  }

  trait TestSetup {
    val targetBlockHash = ByteString(Hex.decode("ca2b65cf841b7acc2548977ad69a3e118940d0934cdbf2d3645c44bdf5023465"))
    val targetBlockHeader = BlockHeader(
      parentHash = ByteString(Hex.decode("952fe52067413118cd8a69b9436a86ebefe6e9e498378348dbdf2fdc42045f71")),
      ommersHash = ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347")),
      beneficiary = ByteString(Hex.decode("4bb96091ee9d802ed039c4d1a5f6216f90f81b01")),
      stateRoot = ByteString(Hex.decode("deae1dfad5ec8dcef15915811e1f044d2543674fd648f94345231da9fc2646cc")),
      transactionsRoot = ByteString(Hex.decode("f3f6b2e6e1ac8d58780deebb4c9d139c89e52a6255bd9d9183c52a7afbb7bfd6")),
      receiptsRoot = ByteString(Hex.decode("e9dab9e32635c254966fbb01b61e74f0d66ce16208bcca497fde96eedc53a012")),
      logsBloom = ByteString(Array.fill[Byte](256)(0)),
      difficulty = BigInt("111131897370924"),
      number = BigInt("3025678"),
      gasLimit = BigInt("4007810"),
      gasUsed = BigInt("340142"),
      unixTimestamp = 1484840522,
      extraData = ByteString(Hex.decode("61736961312e657468706f6f6c2e6f7267")),
      mixHash = ByteString(Hex.decode("9a983833b7b0e65d4e3bdac935257894f136845435d15619baae13d3e4f60e4c")),
      nonce = ByteString(Hex.decode("f81fcf00002150c8"))
    )

    val genesisBlockHeader = BlockHeader(
      parentHash = ByteString(Hex.decode("0000000000000000000000000000000000000000000000000000000000000000")),
      ommersHash = ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347")),
      beneficiary = ByteString(Hex.decode("0000000000000000000000000000000000000000")),
      stateRoot = ByteString(Hex.decode("d7f8974fb5ac78d9ac099b9ad5018bedc2ce0a72dad1827a1709da30580f0544")),
      transactionsRoot = ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")),
      receiptsRoot = ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")),
      logsBloom = ByteString(Array.fill[Byte](256)(0)),
      difficulty = BigInt("0"),
      number = BigInt("5000"),
      gasLimit = BigInt("0"),
      gasUsed = BigInt("0"),
      unixTimestamp = 0,
      extraData = ByteString(Hex.decode("11bbe8db4e347b4e8c937c1c8370e4b5ed33adb3db69cbdb7a38e1e50b1b82fa")),
      mixHash = ByteString(Hex.decode("0000000000000000000000000000000000000000000000000000000000000000")),
      nonce = ByteString(Hex.decode("0000000000000042"))
    )

    val responseBlockHeaders = Seq(
      genesisBlockHeader,
      genesisBlockHeader.copy(number = 1),
      genesisBlockHeader.copy(number = 2),
      genesisBlockHeader.copy(number = 3))
    val blockHashes: Seq[ByteString] = responseBlockHeaders.map(_.hash)

    val mptBranchWithTwoChild =
      ByteString(Hex.decode("f851a0ce34e58455f786d71e7d5528e94b2141bfef9496732ce884d951010ae29d5937a0deae1dfad5ec8dcef15915811e1f044d2543674fd648f94345231da9fc2646cc808080808080808080808080808080"))

    val mptExtension =
      ByteString(Hex.decode("e48200cfa0deae1dfad5ec8dcef15915811e1f044d2543674fd648f94345231da9fc2646cc"))

    val stateMptLeafWithAccount =
      ByteString(Hex.decode("f86d9e328415c225a782bb339b22acad1c739e42277bc7ef34de3623114997ce78b84cf84a0186cb7d8738d800a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421a0c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470"))

    val stateMptLeafWithContractAndNotEmptyStorage =
      ByteString(Hex.decode("f8679e20ed81b2e9dea837899e68d7467ffb53b1beaed7e3ec0f5e96bc0c0e3f8eb846f8448080a096dcdcf98b4514062f687e328429cfd8425f0f095e86e6a67fecdabe3eadf7f8a094f25c272c161b7e4827e2806ba596233720ec831884a0e1cebea49f26cd9b2e"))

    val contractMptBranchWithTwoChild =
      ByteString(Hex.decode("f851a02eea913239753940e01d22b7cf59db93420916452c0081a74f28b2637f12ccada0d3c7a5a650e03272c2ea5b10a4bb586fa6e11d5b1ddd2b55174c4d1f4d975d97808080808080808080808080808080"))

    val contractMptExtension =
      ByteString(Hex.decode("e48200cfa02eea913239753940e01d22b7cf59db93420916452c0081a74f28b2637f12ccad"))

    val contractMptLeafNode =
      ByteString(Hex.decode("e6a0390decd9548b62a8d60345a988386fc84ba6bc95484008f6362f93160ef3e56384831dfeac"))

    val contractEvmCode =
      ByteString(Hex.decode("60606040523615600a575b601d6000600060013411601f5760006024565b005b600134035b91508134039050731c814ab24c152fa279f9ec864cc6321090b86d8c73ffffffffffffffffffffffffffffffffffffffff16826000366040518083838082843782019150509250505060006040518083038185876185025a03f1505060405173410c06478eb2fec6ef35d88c179eb38636a666089250839150600081818185876185025a03f150505050505056"))

    implicit val system = ActorSystem("PeerActorSpec_System")

    val storagesImpl = new SharedEphemDataSources with Storages.DefaultStorages
    val blockchainComp: BlockchainComp = new BlockchainCompImpl {
      override val storagesComp: StoragesComp = storagesImpl
      override val blockchain: Blockchain = new BlockchainImpl
    }
    val blockchain = blockchainComp.blockchain
    val mptNodeStorage = storagesImpl.storages.mptNodeStorage
    
    val peer = TestProbe()
    val fastSync = TestActorRef(FastSyncActor.props(peer.ref, blockchain, mptNodeStorage))
  }

}
