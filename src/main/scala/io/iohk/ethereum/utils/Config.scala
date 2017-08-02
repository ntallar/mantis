package io.iohk.ethereum.utils

import java.net.InetSocketAddress

import akka.util.ByteString
import com.typesafe.config.{ConfigFactory, Config => TypesafeConfig}
import io.iohk.ethereum.db.dataSource.LevelDbConfig
import io.iohk.ethereum.db.storage.pruning.{ArchivePruning, BasicPruning, PruningMode}
import io.iohk.ethereum.domain.Address
import io.iohk.ethereum.jsonrpc.JsonRpcController.JsonRpcConfig
import io.iohk.ethereum.jsonrpc.http.JsonRpcHttpServer.JsonRpcHttpServerConfig
import io.iohk.ethereum.network.PeerManagerActor.{FastSyncHostConfiguration, PeerConfiguration}
import io.iohk.ethereum.network.rlpx.RLPxConnectionHandler.RLPxConfiguration
import io.iohk.ethereum.vm.UInt256
import org.spongycastle.util.encoders.Hex

import scala.concurrent.duration._
import scala.util.Try

object Config {

  val config = ConfigFactory.load().getConfig("mantis")

  val clientId: String = config.getString("client-id")

  val clientVersion: String = config.getString("client-version")

  val nodeKeyFile: String = config.getString("node-key-file")

  val keyStoreDir: String = config.getString("keystore-dir")

  val shutdownTimeout: Duration = config.getDuration("shutdown-timeout").toMillis.millis

  val secureRandomAlgo: String = config.getString("secure-random-algo")

  object Network {
    private val networkConfig = config.getConfig("network")

    val protocolVersion = networkConfig.getString("protocol-version")

    object Server {
      private val serverConfig = networkConfig.getConfig("server-address")

      val interface: String = serverConfig.getString("interface")
      val port: Int = serverConfig.getInt("port")
      val listenAddress = new InetSocketAddress(interface, port)
    }

    val peer = new PeerConfiguration {
      private val peerConfig = networkConfig.getConfig("peer")

      val connectRetryDelay: FiniteDuration = peerConfig.getDuration("connect-retry-delay").toMillis.millis
      val connectMaxRetries: Int = peerConfig.getInt("connect-max-retries")
      val disconnectPoisonPillTimeout: FiniteDuration = peerConfig.getDuration("disconnect-poison-pill-timeout").toMillis.millis
      val waitForHelloTimeout: FiniteDuration = peerConfig.getDuration("wait-for-hello-timeout").toMillis.millis
      val waitForStatusTimeout: FiniteDuration = peerConfig.getDuration("wait-for-status-timeout").toMillis.millis
      val waitForChainCheckTimeout: FiniteDuration = peerConfig.getDuration("wait-for-chain-check-timeout").toMillis.millis
      val maxPeers: Int = peerConfig.getInt("max-peers")
      val maxIncomingPeers: Int = peerConfig.getInt("max-incoming-peers")
      val networkId: Int = peerConfig.getInt("network-id")

      val rlpxConfiguration = new RLPxConfiguration {
        val waitForHandshakeTimeout: FiniteDuration = peerConfig.getDuration("wait-for-handshake-timeout").toMillis.millis
        val waitForTcpAckTimeout: FiniteDuration = peerConfig.getDuration("wait-for-tcp-ack-timeout").toMillis.millis
      }

      val fastSyncHostConfiguration = new FastSyncHostConfiguration {
        val maxBlocksHeadersPerMessage: Int = peerConfig.getInt("max-blocks-headers-per-message")
        val maxBlocksBodiesPerMessage: Int = peerConfig.getInt("max-blocks-bodies-per-message")
        val maxReceiptsPerMessage: Int = peerConfig.getInt("max-receipts-per-message")
        val maxMptComponentsPerMessage: Int = peerConfig.getInt("max-mpt-components-per-message")
      }
      override val updateNodesInitialDelay: FiniteDuration = peerConfig.getDuration("update-nodes-initial-delay").toMillis.millis
      override val updateNodesInterval: FiniteDuration = peerConfig.getDuration("update-nodes-interval").toMillis.millis
    }

    object Rpc extends JsonRpcHttpServerConfig with JsonRpcConfig {
      private val rpcConfig = networkConfig.getConfig("rpc")

      val enabled = rpcConfig.getBoolean("enabled")
      val interface = rpcConfig.getString("interface")
      val port = rpcConfig.getInt("port")

      val apis = {
        val providedApis = rpcConfig.getString("apis").split(",").map(_.trim.toLowerCase)
        val invalidApis = providedApis.diff(List("web3", "eth", "net", "personal"))
        require(invalidApis.isEmpty, s"Invalid RPC APIs specified: ${invalidApis.mkString(",")}")
        providedApis
      }

    }

  }

  object Sync {
    private val syncConfig = config.getConfig("sync")

    val doFastSync: Boolean = syncConfig.getBoolean("do-fast-sync")

    val peersScanInterval: FiniteDuration = syncConfig.getDuration("peers-scan-interval").toMillis.millis
    val blacklistDuration: FiniteDuration = syncConfig.getDuration("blacklist-duration").toMillis.millis
    val startRetryInterval: FiniteDuration = syncConfig.getDuration("start-retry-interval").toMillis.millis
    val syncRetryInterval: FiniteDuration = syncConfig.getDuration("sync-retry-interval").toMillis.millis
    val peerResponseTimeout: FiniteDuration = syncConfig.getDuration("peer-response-timeout").toMillis.millis
    val printStatusInterval: FiniteDuration = syncConfig.getDuration("print-status-interval").toMillis.millis

    val maxConcurrentRequests: Int = syncConfig.getInt("max-concurrent-requests")
    val blockHeadersPerRequest: Int = syncConfig.getInt("block-headers-per-request")
    val blockBodiesPerRequest: Int = syncConfig.getInt("block-bodies-per-request")
    val receiptsPerRequest: Int = syncConfig.getInt("receipts-per-request")
    val nodesPerRequest: Int = syncConfig.getInt("nodes-per-request")
    val minPeersToChooseTargetBlock: Int = syncConfig.getInt("min-peers-to-choose-target-block")
    val targetBlockOffset: Int = syncConfig.getInt("target-block-offset")
    val persistStateSnapshotInterval: FiniteDuration =
      syncConfig.getDuration("persist-state-snapshot-interval").toMillis.millis

    val checkForNewBlockInterval: FiniteDuration = syncConfig.getDuration("check-for-new-block-interval").toMillis.millis
    val blockResolveDepth: Int = syncConfig.getInt("block-resolving-depth")
    val blockChainOnlyPeersPoolSize: Int = syncConfig.getInt("fastsync-block-chain-only-peers-pool")
  }

  trait DbConfig {
    val batchSize: Int
  }

  object Db extends DbConfig {

    private val dbConfig = config.getConfig("db")
    private val iodbConfig = dbConfig.getConfig("iodb")
    private val levelDbConfig = dbConfig.getConfig("leveldb")

    val batchSize = dbConfig.getInt("batch-size")

    object Iodb  {
      val path: String = iodbConfig.getString("path")
    }

    object LevelDb extends LevelDbConfig {
      override val createIfMissing: Boolean = levelDbConfig.getBoolean("create-if-missing")
      override val paranoidChecks: Boolean = levelDbConfig.getBoolean("paranoid-checks")
      override val verifyChecksums: Boolean = levelDbConfig.getBoolean("verify-checksums")
      override val path: String = levelDbConfig.getString("path")
    }

  }

}

trait FilterConfig {
  val filterTimeout: FiniteDuration
  val filterManagerQueryTimeout: FiniteDuration
}

object FilterConfig {
  def apply(etcClientConfig: TypesafeConfig): FilterConfig = {
    val filterConfig = etcClientConfig.getConfig("filter")

    new FilterConfig {
      val filterTimeout: FiniteDuration = filterConfig.getDuration("filter-timeout").toMillis.millis
      val filterManagerQueryTimeout: FiniteDuration = filterConfig.getDuration("filter-manager-query-timeout").toMillis.millis
    }
  }
}

trait TxPoolConfig {
  val txPoolSize: Int
  val pendingTxManagerQueryTimeout: FiniteDuration
}

object TxPoolConfig {
  def apply(etcClientConfig: com.typesafe.config.Config): TxPoolConfig = {
    val txPoolConfig = etcClientConfig.getConfig("txPool")

    new TxPoolConfig {
      val txPoolSize: Int = txPoolConfig.getInt("tx-pool-size")
      val pendingTxManagerQueryTimeout: FiniteDuration = txPoolConfig.getDuration("pending-tx-manager-query-timeout").toMillis.millis
    }
  }
}

trait MiningConfig {
  val ommersPoolSize: Int
  val blockCacheSize: Int
  val coinbase: Address
  val activeTimeout: FiniteDuration
  val ommerPoolQueryTimeout: FiniteDuration
}

object MiningConfig {
  def apply(etcClientConfig: TypesafeConfig): MiningConfig = {
    val miningConfig = etcClientConfig.getConfig("mining")

    new MiningConfig {
      val coinbase: Address = Address(miningConfig.getString("coinbase"))
      val blockCacheSize: Int = miningConfig.getInt("block-cashe-size")
      val ommersPoolSize: Int = miningConfig.getInt("ommers-pool-size")
      val activeTimeout: FiniteDuration = miningConfig.getDuration("active-timeout").toMillis.millis
      val ommerPoolQueryTimeout: FiniteDuration = miningConfig.getDuration("ommer-pool-query-timeout").toMillis.millis
    }
  }
}

trait BlockchainConfig {
  val frontierBlockNumber: BigInt
  val homesteadBlockNumber: BigInt
  val eip150BlockNumber: BigInt
  val eip155BlockNumber: BigInt
  val eip160BlockNumber: BigInt
  val difficultyBombPauseBlockNumber: BigInt
  val difficultyBombContinueBlockNumber: BigInt

  val customGenesisFileOpt: Option[String]

  val daoForkBlockNumber: BigInt
  val daoForkBlockHash: ByteString
  val accountStartNonce: UInt256

  val chainId: Byte

  val monetaryPolicyConfig: MonetaryPolicyConfig
}

object BlockchainConfig {
  def apply(etcClientConfig: TypesafeConfig): BlockchainConfig = {
    val blockchainConfig = etcClientConfig.getConfig("blockchain")

    new BlockchainConfig {
      override val frontierBlockNumber: BigInt = BigInt(blockchainConfig.getString("frontier-block-number"))
      override val homesteadBlockNumber: BigInt = BigInt(blockchainConfig.getString("homestead-block-number"))
      override val eip150BlockNumber: BigInt = BigInt(blockchainConfig.getString("eip150-block-number"))
      override val eip155BlockNumber: BigInt = BigInt(blockchainConfig.getString("eip155-block-number"))
      override val eip160BlockNumber: BigInt = BigInt(blockchainConfig.getString("eip160-block-number"))
      override val difficultyBombPauseBlockNumber: BigInt = BigInt(blockchainConfig.getString("difficulty-bomb-pause-block-number"))
      override val difficultyBombContinueBlockNumber: BigInt = BigInt(blockchainConfig.getString("difficulty-bomb-continue-block-number"))

      override val customGenesisFileOpt: Option[String] = Try(blockchainConfig.getString("custom-genesis-file")).toOption

      override val daoForkBlockNumber: BigInt = BigInt(blockchainConfig.getString("dao-fork-block-number"))
      override val daoForkBlockHash: ByteString = ByteString(Hex.decode(blockchainConfig.getString("dao-fork-block-hash")))
      override val accountStartNonce: UInt256 = UInt256(BigInt(blockchainConfig.getString("account-start-nonce")))

      override val chainId: Byte = Hex.decode(blockchainConfig.getString("chain-id")).head

      override val monetaryPolicyConfig = MonetaryPolicyConfig(blockchainConfig.getConfig("monetary-policy"))
    }
  }
}

case class MonetaryPolicyConfig(
  eraDuration: Int,
  rewardRedutionRate: Double,
  firstEraBlockReward: BigInt
) {
  require(rewardRedutionRate >= 0.0 && rewardRedutionRate <= 1.0,
    "reward-reduction-rate should be a value in range [0.0, 1.0]")
}

object MonetaryPolicyConfig {
  def apply(mpConfig: TypesafeConfig): MonetaryPolicyConfig = {
    MonetaryPolicyConfig(
      mpConfig.getInt("era-duration"),
      mpConfig.getDouble("reward-reduction-rate"),
      BigInt(mpConfig.getString("first-era-block-reward"))
    )
  }
}

trait PruningConfig {
  val mode: PruningMode
}

object PruningConfig {
  def apply(etcClientConfig: com.typesafe.config.Config): PruningConfig = {
    val pruningConfig = etcClientConfig.getConfig("pruning")

    val pruningMode: PruningMode = pruningConfig.getString("mode") match {
      case "basic" => BasicPruning(pruningConfig.getInt("history"))
      case "archive" => ArchivePruning
    }

    new PruningConfig {
      override val mode: PruningMode = pruningMode
    }
  }
}
