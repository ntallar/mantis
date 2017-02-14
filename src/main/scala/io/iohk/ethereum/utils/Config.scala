package io.iohk.ethereum.utils

import java.net.InetSocketAddress

import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import io.iohk.ethereum.db.dataSource.LevelDbConfig
import org.spongycastle.util.encoders.Hex

import scala.collection.JavaConversions._
import scala.concurrent.duration._

object Config {

  private val config = ConfigFactory.load().getConfig("etc-client")

  val clientId: String = config.getString("client-id")

  object Network {
    private val networkConfig = config.getConfig("network")

    val networkId: Int = networkConfig.getInt("network-id")

    object Server {
      private val serverConfig = networkConfig.getConfig("server-address")

      val interface: String = serverConfig.getString("interface")
      val port: Int = serverConfig.getInt("port")
      val listenAddress = new InetSocketAddress(interface, port)
    }

    object Discovery {
      private val discoveryConfig = networkConfig.getConfig("discovery")

      val bootstrapNodes = discoveryConfig.getStringList("bootstrap-nodes").toSet
      val bootstrapNodesScanInterval = discoveryConfig.getDuration("bootstrap-nodes-scan-interval").toMillis.millis
    }

    object Peer {
      private val peerConfig = networkConfig.getConfig("peer")

      val connectRetryDelay = peerConfig.getDuration("connect-retry-delay").toMillis.millis
      val connectMaxRetries = peerConfig.getInt("connect-max-retries")
      val disconnectPoisonPillTimeout = peerConfig.getDuration("disconnect-poison-pill-timeout").toMillis.millis
      val waitForStatusTimeout = peerConfig.getDuration("wait-for-status-timeout").toMillis.millis
      val waitForChainCheckTimeout = peerConfig.getDuration("wait-for-chain-check-timeout").toMillis.millis
    }

  }

  object Blockchain {
    private val blockchainConfig = config.getConfig("blockchain")

    val genesisDifficulty: Long = blockchainConfig.getLong("genesis-difficulty")
    val genesisHash = ByteString(Hex.decode(blockchainConfig.getString("genesis-hash")))

    val daoForkBlockNumber = BigInt(blockchainConfig.getString("dao-fork-block-number"))
    val daoForkBlockTotalDifficulty = BigInt(blockchainConfig.getString("dao-fork-block-total-difficulty"))
    val daoForkBlockHash = ByteString(Hex.decode(blockchainConfig.getString("dao-fork-block-hash")))
  }

  object FastSync {
    private val fastSyncConfig = config.getConfig("fast-sync")

    val BlocksPerMessage: Int = fastSyncConfig.getInt("blocks-per-message")
    val NodesPerRequest: Int = fastSyncConfig.getInt("nodes-per-request")
    val NodeRequestsInterval: FiniteDuration = fastSyncConfig.getDuration("node-requests-interval").toMillis.millis

    val maxConcurrentRequests: Int = 50
    val peersScanInterval: FiniteDuration = 30.seconds
    val blacklistDuration: FiniteDuration = 30.seconds
    val startRetryInterval: FiniteDuration = 5.seconds
    val downloadRetryInterval: FiniteDuration = 5.seconds
    val peerResponseTimeout: FiniteDuration = 10.seconds
    val printStatusInterval: FiniteDuration = 2.seconds

    val blockHeadersPerRequest: Int = 2048
    val blockBodiesPerRequest: Int = 128
    val receiptsPerRequest: Int = 128
    val nodesPerRequest: Int = 384
  }

  object Db {

    private val dbConfig = config.getConfig("db")

    object LevelDb extends LevelDbConfig {
      override val createIfMissing: Boolean = dbConfig.getBoolean("create-if-missing")
      override val paranoidChecks: Boolean = dbConfig.getBoolean("paranoid-checks")
      override val verifyChecksums: Boolean = dbConfig.getBoolean("verify-checksums")
      override val cacheSize: Int = dbConfig.getInt("cache-size")
    }
  }
}
