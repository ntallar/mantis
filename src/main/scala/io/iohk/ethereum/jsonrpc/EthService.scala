package io.iohk.ethereum.jsonrpc

import akka.util.ByteString
import io.iohk.ethereum.domain.Blockchain

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Try

//FIXME: Add explanation
object EthService {

  case class TxCountByBlockHashRequest(blockHash: ByteString)
  case class TxCountByBlockHashResponse(txsQuantity: Option[Int])

  case class BlockByBlockHashRequest(blockHash: ByteString, txHashed: Boolean)
  case class BlockByBlockHashResponse(blockView: Option[BlockView])

  case class UncleByBlockHashAndIndexRequest(blockHash: ByteString, uncleIndex: Int)
  case class UncleByBlockHashAndIndexResponse(uncleBlockView: Option[BlockView])
}

class EthService(blockchain: Blockchain) {

  import EthService._

  /**
    * Implements the eth_getBlockTransactionCountByHash method that fetches the number of txs that a certain block has.
    *
    * @param request with the hash of the block requested
    * @return the number of txs that the block has or None if the client doesn't have the block requested
    */
  def getBlockTransactionCountByHash(request: TxCountByBlockHashRequest): Future[TxCountByBlockHashResponse] = Future {
    val txsCount = blockchain.getBlockBodyByHash(request.blockHash).map(_.transactionList.size)
    TxCountByBlockHashResponse(txsCount)
  }

  /**
    * Implements the eth_getBlockByHash method that fetches a requested block.
    *
    * @param request with the hash of the block requested
    * @return the block requested or None if the client doesn't have the block
    */
  def getByBlockHash(request: BlockByBlockHashRequest): Future[BlockByBlockHashResponse] = Future {
    val BlockByBlockHashRequest(blockHash, txHashed) = request
    val blockOpt = blockchain.getBlockByHash(blockHash)
    val totalDifficulty = blockchain.getTotalDifficultyByHash(blockHash)

    val blockViewOpt = blockOpt.map(block => BlockView(block, txHashed, totalDifficulty))
    BlockByBlockHashResponse(blockViewOpt)
  }

  /**
    * Implements the eth_getUncleByBlockHashAndIndex method that fetches an uncle from a certain index in a requested block.
    *
    * @param request with the hash of the block and the index of the uncle requested
    * @return the uncle that the block has at the given index or None if the client doesn't have the block or if there's no uncle in that index
    */
  def getUncleByBlockHashAndIndex(request: UncleByBlockHashAndIndexRequest): Future[UncleByBlockHashAndIndexResponse] = Future {
    val UncleByBlockHashAndIndexRequest(blockHash, uncleIndex) = request
    val uncleHeaderOpt = blockchain.getBlockBodyByHash(blockHash)
      .flatMap(body => Try{body.uncleNodesList.apply(uncleIndex)}.toOption)
    val totalDifficulty = uncleHeaderOpt.flatMap(uncleHeader => blockchain.getTotalDifficultyByHash(uncleHeader.hash))

    //The block in the response will not have any txs or uncles
    val uncleBlockViewOpt = uncleHeaderOpt.map { uncleHeader => BlockView(blockHeader = uncleHeader, totalDifficulty = totalDifficulty) }
    UncleByBlockHashAndIndexResponse(uncleBlockViewOpt)
  }
}
