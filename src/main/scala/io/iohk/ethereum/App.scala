package io.iohk.ethereum

import java.lang.Runtime

import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor.ActorSystem
import akka.agent._
import akka.util.ByteString
import io.iohk.ethereum.blockchain.sync.FastSyncController
import io.iohk.ethereum.crypto._
import io.iohk.ethereum.db.components.{SharedLevelDBDataSources, _}
import io.iohk.ethereum.domain.{Blockchain, BlockchainImpl, BlockchainStorages}
import io.iohk.ethereum.network.{PeerManagerActor, ServerActor}
import io.iohk.ethereum.rpc.JsonRpcServer
import io.iohk.ethereum.utils.{BlockchainStatus, Config, NodeStatus, ServerStatus}
import org.spongycastle.util.encoders.Hex

object App {

  import Config.{Network => NetworkConfig}

  val nodeKey = generateKeyPair()

  val storagesInstance =  new SharedLevelDBDataSources with Storages.DefaultStorages
  val blockchain: Blockchain = BlockchainImpl(storagesInstance.storages)

  def main(args: Array[String]): Unit = {
    val actorSystem = ActorSystem("etc-client_system")

    val nodeStatus =
      NodeStatus(
        key = nodeKey,
        serverStatus = ServerStatus.NotListening,
        blockchainStatus = BlockchainStatus(Config.Blockchain.genesisDifficulty, Config.Blockchain.genesisHash, 0))

    val nodeStatusHolder = Agent(nodeStatus)

    val peerManager = actorSystem.actorOf(PeerManagerActor.props(nodeStatusHolder), "peer-manager")
    val server = actorSystem.actorOf(ServerActor.props(nodeStatusHolder, peerManager), "server")

    server ! ServerActor.StartServer(NetworkConfig.Server.listenAddress)

    if(Config.Network.Rpc.enabled) JsonRpcServer.run(actorSystem, blockchain, Config.Network.Rpc)

    val fastSyncController = actorSystem.actorOf(
      FastSyncController.props(
        peerManager,
        nodeStatusHolder,
        blockchain,
        storagesInstance.storages.mptNodeStorage),
      "fast-sync-controller")

    fastSyncController ! FastSyncController.StartFastSync(ByteString(Hex.decode("81e2dcb132c2af3cb84591466aa904bb054f0b9ba52e369c06a271f6d92190db")))

    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run(): Unit = {
        storagesInstance.dataSources.closeAll
      }
    })
  }

}
