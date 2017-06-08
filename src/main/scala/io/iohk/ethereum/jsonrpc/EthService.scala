package io.iohk.ethereum.jsonrpc

import java.time.Duration
import java.util.Date
import java.util.concurrent.atomic.AtomicReference
import java.util.function.UnaryOperator

import java.util.Date
import java.util.concurrent.atomic.AtomicReference

import akka.pattern.ask
import akka.util.Timeout
import io.iohk.ethereum.domain._
import akka.actor.ActorRef
import io.iohk.ethereum.domain.{BlockHeader, SignedTransaction}
import io.iohk.ethereum.db.storage.AppStateStorage
import akka.util.ByteString
import io.iohk.ethereum.blockchain.sync.SyncController.MinedBlock
import io.iohk.ethereum.crypto._
import io.iohk.ethereum.keystore.KeyStore
import io.iohk.ethereum.ledger.{InMemoryWorldStateProxy, Ledger}
import io.iohk.ethereum.mining.BlockGenerator
import io.iohk.ethereum.utils.MiningConfig
import io.iohk.ethereum.utils.{BlockchainConfig, Logger}
import io.iohk.ethereum.transactions.PendingTransactionsManager
import io.iohk.ethereum.transactions.PendingTransactionsManager.PendingTransactions
import io.iohk.ethereum.ommers.OmmersPool
import org.spongycastle.util.encoders.Hex

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._
// scalastyle:off number.of.methods number.of.types
object EthService {

  val CurrentProtocolVersion = 63

  case class ProtocolVersionRequest()
  case class ProtocolVersionResponse(value: String)

  case class BestBlockNumberRequest()
  case class BestBlockNumberResponse(bestBlockNumber: BigInt)

  case class TxCountByBlockHashRequest(blockHash: ByteString)
  case class TxCountByBlockHashResponse(txsQuantity: Option[Int])

  case class BlockByBlockHashRequest(blockHash: ByteString, fullTxs: Boolean)
  case class BlockByBlockHashResponse(blockResponse: Option[BlockResponse])

  case class GetTransactionByBlockHashAndIndexRequest(blockHash: ByteString, transactionIndex: BigInt)
  case class GetTransactionByBlockHashAndIndexResponse(transactionResponse: Option[TransactionResponse])

  case class UncleByBlockHashAndIndexRequest(blockHash: ByteString, uncleIndex: BigInt)
  case class UncleByBlockHashAndIndexResponse(uncleBlockResponse: Option[BlockResponse])

  case class SubmitHashRateRequest(hashRate: BigInt, id: ByteString)
  case class SubmitHashRateResponse(success: Boolean)

  case class GetMiningRequest()
  case class GetMiningResponse(isMining: Boolean)

  case class GetTransactionByBlockNumberAndIndexRequest(block: BlockParam, transactionIndex: BigInt)
  case class GetTransactionByBlockNumberAndIndexResponse(transactionResponse: Option[TransactionResponse])

  case class GetHashRateRequest()
  case class GetHashRateResponse(hashRate: BigInt)

  case class GetWorkRequest()
  case class GetWorkResponse(powHeaderHash: ByteString, dagSeed: ByteString, target: ByteString)

  case class SubmitWorkRequest(nonce: ByteString, powHeaderHash: ByteString, mixHash: ByteString)
  case class SubmitWorkResponse(success: Boolean)

  case class SyncingRequest()
  case class SyncingResponse(startingBlock: BigInt, currentBlock: BigInt, highestBlock: BigInt)

  case class SendRawTransactionRequest(data: ByteString)
  case class SendRawTransactionResponse(transactionHash: ByteString)

  sealed trait BlockParam

  object BlockParam {
    case class WithNumber(n: BigInt) extends BlockParam
    case object Latest extends BlockParam
    case object Pending extends BlockParam
    case object Earliest extends BlockParam
  }

  case class CallTx(
    from: Option[ByteString],
    to: Option[ByteString],
    gas: BigInt,
    gasPrice: BigInt,
    value: BigInt,
    data: ByteString)
  case class CallRequest(tx: CallTx, block: BlockParam)
  case class CallResponse(returnData: ByteString)

  case class GetCodeRequest(address: Address, block: BlockParam)
  case class GetCodeResponse(result: ByteString)

  case class GetUncleCountByBlockNumberRequest(block: BlockParam)
  case class GetUncleCountByBlockNumberResponse(result: BigInt)

  case class GetUncleCountByBlockHashRequest(blockHash: ByteString)
  case class GetUncleCountByBlockHashResponse(result: BigInt)

  case class GetCoinbaseRequest()
  case class GetCoinbaseResponse(address: Address)

  case class GetBlockTransactionCountByNumberRequest(block: BlockParam)
  case class GetBlockTransactionCountByNumberResponse(result: BigInt)

  case class GetBalanceRequest(address: Address, block: BlockParam)
  case class GetBalanceResponse(value: BigInt)

  case class GetStorageAtRequest(address: Address, position: BigInt, block: BlockParam)
  case class GetStorageAtResponse(value: ByteString)

  case class GetTransactionCountRequest(address: Address, block: BlockParam)
  case class GetTransactionCountResponse(value: BigInt)
}

class EthService(
    blockchainStorages: BlockchainStorages,
    blockGenerator: BlockGenerator,
    appStateStorage: AppStateStorage,
    miningConfig: MiningConfig,
    ledger: Ledger,
    blockchainConfig: BlockchainConfig,
    keyStore: KeyStore,
    pendingTransactionsManager: ActorRef,
    syncingController: ActorRef,
    ommersPool: ActorRef) extends Logger {

  import EthService._

  lazy val blockchain = BlockchainImpl(blockchainStorages)

  val minerTimeOut: Long = 5.seconds.toMillis
  val hashRate: AtomicReference[Map[ByteString, (BigInt, Date)]] = new AtomicReference[Map[ByteString, (BigInt, Date)]](Map())
  val lastActive = new AtomicReference[Option[Date]](None)

  def protocolVersion(req: ProtocolVersionRequest): ServiceResponse[ProtocolVersionResponse] =
    Future.successful(Right(ProtocolVersionResponse(f"0x$CurrentProtocolVersion%x")))

  /**
    * eth_blockNumber that returns the number of most recent block.
    *
    * @return Current block number the client is on.
    */
  def bestBlockNumber(req: BestBlockNumberRequest): ServiceResponse[BestBlockNumberResponse] = Future {
    Right(BestBlockNumberResponse(appStateStorage.getBestBlockNumber()))
  }

  /**
    * Implements the eth_getBlockTransactionCountByHash method that fetches the number of txs that a certain block has.
    *
    * @param request with the hash of the block requested
    * @return the number of txs that the block has or None if the client doesn't have the block requested
    */
  def getBlockTransactionCountByHash(request: TxCountByBlockHashRequest): ServiceResponse[TxCountByBlockHashResponse] = Future {
    val txsCount = blockchain.getBlockBodyByHash(request.blockHash).map(_.transactionList.size)
    Right(TxCountByBlockHashResponse(txsCount))
  }

  /**
    * Implements the eth_getBlockByHash method that fetches a requested block.
    *
    * @param request with the hash of the block requested
    * @return the block requested or None if the client doesn't have the block
    */
  def getByBlockHash(request: BlockByBlockHashRequest): ServiceResponse[BlockByBlockHashResponse] = Future {
    val BlockByBlockHashRequest(blockHash, fullTxs) = request
    val blockOpt = blockchain.getBlockByHash(blockHash)
    val totalDifficulty = blockchain.getTotalDifficultyByHash(blockHash)

    val blockResponseOpt = blockOpt.map(block => BlockResponse(block, fullTxs, totalDifficulty))
    Right(BlockByBlockHashResponse(blockResponseOpt))
  }

  /**
    * eth_getTransactionByBlockHashAndIndex that returns information about a transaction by block hash and
    * transaction index position.
    *
    * @return the tx requested or None if the client doesn't have the block or if there's no tx in the that index
    */
  def getTransactionByBlockHashAndIndexRequest(req: GetTransactionByBlockHashAndIndexRequest)
  : ServiceResponse[GetTransactionByBlockHashAndIndexResponse] = Future {
    import req._
    val maybeTransactionResponse = blockchain.getBlockByHash(blockHash).flatMap{
      blockWithTx =>
        val blockTxs = blockWithTx.body.transactionList
        if (transactionIndex >= 0 && transactionIndex < blockTxs.size)
          Some(TransactionResponse(blockTxs(transactionIndex.toInt), Some(blockWithTx.header), Some(transactionIndex.toInt)))
        else None
    }
    Right(GetTransactionByBlockHashAndIndexResponse(maybeTransactionResponse))
  }

  /**
    * Implements the eth_getUncleByBlockHashAndIndex method that fetches an uncle from a certain index in a requested block.
    *
    * @param request with the hash of the block and the index of the uncle requested
    * @return the uncle that the block has at the given index or None if the client doesn't have the block or if there's no uncle in that index
    */
  def getUncleByBlockHashAndIndex(request: UncleByBlockHashAndIndexRequest): ServiceResponse[UncleByBlockHashAndIndexResponse] = Future {
    val UncleByBlockHashAndIndexRequest(blockHash, uncleIndex) = request
    val uncleHeaderOpt = blockchain.getBlockBodyByHash(blockHash)
      .flatMap { body =>
        if (uncleIndex >= 0 && uncleIndex < body.uncleNodesList.size)
          Some(body.uncleNodesList.apply(uncleIndex.toInt))
        else
          None
      }
    val totalDifficulty = uncleHeaderOpt.flatMap(uncleHeader => blockchain.getTotalDifficultyByHash(uncleHeader.hash))

    //The block in the response will not have any txs or uncles
    val uncleBlockResponseOpt = uncleHeaderOpt.map { uncleHeader => BlockResponse(blockHeader = uncleHeader, totalDifficulty = totalDifficulty) }
    Right(UncleByBlockHashAndIndexResponse(uncleBlockResponseOpt))
  }

  def submitHashRate(req: SubmitHashRateRequest): ServiceResponse[SubmitHashRateResponse] = {
    reportActive()
    hashRate.updateAndGet(new UnaryOperator[Map[ByteString, (BigInt, Date)]] {
      override def apply(t: Map[ByteString, (BigInt, Date)]): Map[ByteString, (BigInt, Date)] = {
        val now = new Date
        removeObsoleteHashrates(now, t + (req.id -> (req.hashRate, now)))
      }
    })

    Future.successful(Right(SubmitHashRateResponse(true)))
  }

  def getMining(req: GetMiningRequest): ServiceResponse[GetMiningResponse] = {
    val isMining = lastActive.updateAndGet(new UnaryOperator[Option[Date]] {
      override def apply(e: Option[Date]): Option[Date] = {
        e.filter { time => Duration.between(time.toInstant, (new Date).toInstant).toMillis < minerTimeOut }
      }
    }).isDefined
    Future.successful(Right(GetMiningResponse(isMining)))
  }

  private def reportActive() = {
    val now = new Date()
    lastActive.updateAndGet(new UnaryOperator[Option[Date]] {
      override def apply(e: Option[Date]): Option[Date] = {
        Some(now)
      }
    })
  }

  def getHashRate(req: GetHashRateRequest): ServiceResponse[GetHashRateResponse] = {
    val hashRates: Map[ByteString, (BigInt, Date)] = hashRate.updateAndGet(new UnaryOperator[Map[ByteString, (BigInt, Date)]] {
      override def apply(t: Map[ByteString, (BigInt, Date)]): Map[ByteString, (BigInt, Date)] = {
        removeObsoleteHashrates(new Date, t)
      }
    })

    //sum all reported hashRates
    Future.successful(Right(GetHashRateResponse(hashRates.mapValues { case (hr, _) => hr }.values.sum)))
  }

  private def removeObsoleteHashrates(now: Date, rates: Map[ByteString, (BigInt, Date)]):Map[ByteString, (BigInt, Date)]={
    rates.filter { case (_, (_, reported)) =>
      Duration.between(reported.toInstant, now.toInstant).toMillis < minerTimeOut
    }
  }

  def getWork(req: GetWorkRequest): ServiceResponse[GetWorkResponse] = {
    reportActive()
    import io.iohk.ethereum.mining.pow.PowCache._

    val blockNumber = appStateStorage.getBestBlockNumber() + 1

    getOmmersFromPool(blockNumber).zip(getTransactionsFromPool).map {
      case (ommers, pendingTxs) =>
        blockGenerator.generateBlockForMining(blockNumber, pendingTxs.signedTransactions, ommers.headers, miningConfig.coinbase) match {
          case Right(b) =>
            Right(GetWorkResponse(
              powHeaderHash = ByteString(kec256(BlockHeader.getEncodedWithoutNonce(b.header))),
              dagSeed = seedForBlock(b.header.number),
              target = ByteString((BigInt(2).pow(256) / b.header.difficulty).toByteArray)
            ))
          case Left(err) =>
            log.error(s"unable to prepare block because of $err")
            Left(JsonRpcErrors.InternalError)
        }
      }
  }

  private def getOmmersFromPool(blockNumber: BigInt) = {
    implicit val timeout = Timeout(miningConfig.poolingServicesTimeout)

    (ommersPool ? OmmersPool.GetOmmers(blockNumber)).mapTo[OmmersPool.Ommers]
      .recover { case ex =>
        log.error("failed to get ommer, mining block with empty ommers list", ex)
        OmmersPool.Ommers(Nil)
      }
  }

  private def getTransactionsFromPool = {
    implicit val timeout = Timeout(miningConfig.poolingServicesTimeout)

    (pendingTransactionsManager ? PendingTransactionsManager.GetPendingTransactions).mapTo[PendingTransactions]
      .recover { case ex =>
        log.error("failed to get transactions, mining block with empty transactions list", ex)
        PendingTransactions(Nil)
      }
  }

  def getCoinbase(req: GetCoinbaseRequest): ServiceResponse[GetCoinbaseResponse] =
    Future.successful(Right(GetCoinbaseResponse(miningConfig.coinbase)))

  def submitWork(req: SubmitWorkRequest): ServiceResponse[SubmitWorkResponse] = {
    reportActive()
    blockGenerator.getPrepared(req.powHeaderHash) match {
      case Some(block) if appStateStorage.getBestBlockNumber() <= block.header.number =>
        syncingController ! MinedBlock(block.copy(header = block.header.copy(nonce = req.nonce, mixHash = req.mixHash)))
        Future.successful(Right(SubmitWorkResponse(true)))
      case _ =>
        Future.successful(Right(SubmitWorkResponse(false)))
    }
  }

 def syncing(req: SyncingRequest): ServiceResponse[SyncingResponse] = {
    Future(Right(SyncingResponse(
      startingBlock = appStateStorage.getSyncStartingBlock(),
      currentBlock = appStateStorage.getBestBlockNumber(),
      highestBlock = appStateStorage.getEstimatedHighestBlock())))
  }

  def sendRawTransaction(req: SendRawTransactionRequest): ServiceResponse[SendRawTransactionResponse] = {
    import io.iohk.ethereum.network.p2p.messages.CommonMessages.SignedTransactions.SignedTransactionDec

    Try(req.data.toArray.toSignedTransaction) match {
      case Success(signedTransaction) =>
        pendingTransactionsManager ! PendingTransactionsManager.AddTransactions(signedTransaction)
        Future.successful(Right(SendRawTransactionResponse(signedTransaction.hash)))
      case Failure(ex) =>
        Future.successful(Left(JsonRpcErrors.InvalidRequest))
    }
  }

  def call(req: CallRequest): ServiceResponse[CallResponse] = {
    val fromAddress = req.tx.from
      .map(Address.apply) // `from` param, if specified
      .getOrElse(
        keyStore
          .listAccounts().getOrElse(Nil).headOption // first account, if exists and `from` param not specified
          .getOrElse(Address(0))) // 0x0 default

    val toAddress = req.tx.to.map(Address.apply)
    val tx = Transaction(0, req.tx.gasPrice, req.tx.gas, toAddress, req.tx.value, req.tx.data)
    val stx = SignedTransaction(tx, ECDSASignature(0, 0, 0.toByte), fromAddress)

    Future {
      resolveBlock(req.block).map { block =>
        val txResult = ledger.simulateTransaction(stx, block.header, blockchainStorages)
        CallResponse(txResult.vmReturnData)
      }
    }
  }

  def getCode(req: GetCodeRequest): ServiceResponse[GetCodeResponse] = {
    Future {
      resolveBlock(req.block).map { block =>
        val world = InMemoryWorldStateProxy(blockchainStorages, Some(block.header.stateRoot))
        GetCodeResponse(world.getCode(req.address))
      }
    }
  }

  def getUncleCountByBlockNumber(req: GetUncleCountByBlockNumberRequest): ServiceResponse[GetUncleCountByBlockNumberResponse] = {
    Future {
      resolveBlock(req.block).map { block =>
        GetUncleCountByBlockNumberResponse(block.body.uncleNodesList.size)
      }
    }
  }

  def getUncleCountByBlockHash(req: GetUncleCountByBlockHashRequest): ServiceResponse[GetUncleCountByBlockHashResponse] = {
    Future {
      blockchain.getBlockBodyByHash(req.blockHash) match {
        case Some(blockBody) =>
          Right(GetUncleCountByBlockHashResponse(blockBody.uncleNodesList.size))
        case None =>
          Left(JsonRpcErrors.InvalidParams(s"Block with hash ${Hex.toHexString(req.blockHash.toArray[Byte])} not found"))
      }
    }
  }

  def getBlockTransactionCountByNumber(req: GetBlockTransactionCountByNumberRequest): ServiceResponse[GetBlockTransactionCountByNumberResponse] = {
    Future {
      resolveBlock(req.block).map { block =>
        GetBlockTransactionCountByNumberResponse(block.body.transactionList.size)
      }
    }
  }

  def getTransactionByBlockNumberAndIndexRequest(req: GetTransactionByBlockNumberAndIndexRequest):
  ServiceResponse[GetTransactionByBlockNumberAndIndexResponse] = Future {
    import req._
    resolveBlock(block).map{
      blockWithTx =>
        val blockTxs = blockWithTx.body.transactionList
        if (transactionIndex >= 0 && transactionIndex < blockTxs.size)
          GetTransactionByBlockNumberAndIndexResponse(
            Some(TransactionResponse(blockTxs(transactionIndex.toInt),
              Some(blockWithTx.header),
              Some(transactionIndex.toInt))))
        else
          GetTransactionByBlockNumberAndIndexResponse(None)
    }.left.flatMap(_ => Right(GetTransactionByBlockNumberAndIndexResponse(None)))
  }

  def getBalance(req: GetBalanceRequest): ServiceResponse[GetBalanceResponse] = {
    Future {
      withAccount(req.address, req.block) { account =>
        GetBalanceResponse(account.balance)
      }
    }
  }

  def getStorageAt(req: GetStorageAtRequest): ServiceResponse[GetStorageAtResponse] = {
    Future {
      withAccount(req.address, req.block) { account =>
        GetStorageAtResponse(blockchain.getAccountStorageAt(account.storageRoot, req.position))
      }
    }
  }

  def getTransactionCount(req: GetTransactionCountRequest): ServiceResponse[GetTransactionCountResponse] = {
    Future {
      withAccount(req.address, req.block) { account =>
        GetTransactionCountResponse(account.nonce)
      }
    }
  }

  private def withAccount[T](address: Address, blockParam: BlockParam)(f: Account => T): Either[JsonRpcError, T] = {
    resolveBlock(blockParam).map { block =>
      f(blockchain.getAccount(address, block.header.number).getOrElse(Account.Empty))
    }
  }

  private def resolveBlock(blockParam: BlockParam): Either[JsonRpcError, Block] = {
    def getBlock(number: BigInt): Either[JsonRpcError, Block] = {
      blockchain.getBlockByNumber(number)
        .map(Right.apply)
        .getOrElse(Left(JsonRpcErrors.InvalidParams(s"Block $number not found")))
    }

    blockParam match {
      case BlockParam.WithNumber(blockNumber) => getBlock(blockNumber)
      case BlockParam.Earliest => getBlock(0)
      case BlockParam.Latest => getBlock(appStateStorage.getBestBlockNumber())
      case BlockParam.Pending => getBlock(appStateStorage.getBestBlockNumber())
    }
  }

}
