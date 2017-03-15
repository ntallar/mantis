package io.iohk.ethereum.domain

import akka.util.ByteString
import io.iohk.ethereum.db.storage._
import io.iohk.ethereum.network.p2p.messages.PV62.BlockBody
import io.iohk.ethereum.network.p2p.messages.PV63.{MptNode, Receipt}
import io.iohk.ethereum.utils.Config

/**
  * Entity to be used to persist and query  Blockchain related objects (blocks, transactions, ommers)
  */
trait Blockchain {

  /**
    * Allows to query a blockHeader by block hash
    *
    * @param hash of the block that's being searched
    * @return [[BlockHeader]] if found
    */
  def getBlockHeaderByHash(hash: ByteString): Option[BlockHeader]

  def getBlockHeaderByNumber(number: BigInt): Option[BlockHeader] = {
    for {
      hash <- getHashByBlockNumber(number)
      header <- getBlockHeaderByHash(hash)
    } yield header
  }

  /**
    * Allows to query a blockBody by block hash
    *
    * @param hash of the block that's being searched
    * @return [[io.iohk.ethereum.network.p2p.messages.PV62.BlockBody]] if found
    */
  def getBlockBodyByHash(hash: ByteString): Option[BlockBody]

  /**
    * Allows to query for a block based on it's hash
    *
    * @param hash of the block that's being searched
    * @return Block if found
    */
  def getBlockByHash(hash: ByteString): Option[Block] =
    for {
      header <- getBlockHeaderByHash(hash)
      body <- getBlockBodyByHash(hash)
    } yield Block(header, body)

  /**
    * Allows to query for a block based on it's number
    *
    * @param number Block number
    * @return Block if it exists
    */
  def getBlockByNumber(number: BigInt): Option[Block] =
    for {
      hash <- getHashByBlockNumber(number)
      block <- getBlockByHash(hash)
    } yield block

  /**
    * Returns the receipts based on a block hash
    * @param blockhash
    * @return Receipts if found
    */
  def getReceiptsByHash(blockhash: ByteString): Option[Seq[Receipt]]

  /**
    * Returns EVM code searched by it's hash
    * @param hash Code Hash
    * @return EVM code if found
    */
  def getEvmCodeByHash(hash: ByteString): Option[ByteString]

  /**
    * Returns MPT node searched by it's hash
    * @param hash Node Hash
    * @return MPT node
    */
  def getMptNodeByHash(hash: ByteString): Option[MptNode]


  /**
    * Returns the total difficulty based on a block hash
    * @param blockhash
    * @return total difficulty if found
    */
  def getTotalDifficultyByHash(blockhash: ByteString): Option[BigInt]

  /**
    * Persists a block in the underlying Blockchain Database
    *
    * @param block Block to be saved
    */
  def save(block: Block): Unit = {
    save(block.header)
    save(block.header.hash, block.body)
  }

  def removeBlock(hash: ByteString): Unit

  /**
    * Persists a block header in the underlying Blockchain Database
    *
    * @param blockHeader Block to be saved
    */
  def save(blockHeader: BlockHeader): Unit

  def save(blockHash: ByteString, blockBody: BlockBody): Unit

  def save(blockHash: ByteString, receipts: Seq[Receipt]): Unit

  def save(hash: ByteString, evmCode: ByteString): Unit

  def save(node: MptNode): Unit

  def save(blockhash: ByteString, totalDifficulty: BigInt): Unit

  /**
    * Returns a block hash given a block number
    *
    * @param number Number of the searchead block
    * @return Block hash if found
    */
  protected def getHashByBlockNumber(number: BigInt): Option[ByteString]

}

class BlockchainImpl(
                      protected val blockHeadersStorage: BlockHeadersStorage,
                      protected val blockBodiesStorage: BlockBodiesStorage,
                      protected val blockNumberMappingStorage: BlockNumberMappingStorage,
                      protected val receiptStorage: ReceiptStorage,
                      protected val evmCodeStorage: EvmCodeStorage,
                      protected val mptNodeStorage: MptNodeStorage,
                      protected val totalDifficultyStorage: TotalDifficultyStorage
                    ) extends Blockchain {

  override def getBlockHeaderByHash(hash: ByteString): Option[BlockHeader] = if (hash == Config.Blockchain.genesisHash) {
    Some(Config.Blockchain.genesisBlockHeader)
  } else {
    blockHeadersStorage.get(hash)
  }

  override def getBlockBodyByHash(hash: ByteString): Option[BlockBody] = if (hash == Config.Blockchain.genesisHash) {
    Some(Config.Blockchain.genesisBlockBody)
  } else {
    blockBodiesStorage.get(hash)
  }

  override def getReceiptsByHash(blockhash: ByteString): Option[Seq[Receipt]] = receiptStorage.get(blockhash)

  override def getEvmCodeByHash(hash: ByteString): Option[ByteString] = evmCodeStorage.get(hash)

  override def getTotalDifficultyByHash(blockhash: ByteString): Option[BigInt] = totalDifficultyStorage.get(blockhash)

  override def save(blockHeader: BlockHeader): Unit = {
    val hash = blockHeader.hash
    blockHeadersStorage.put(hash, blockHeader)
    saveBlockNumberMapping(blockHeader.number, hash)
  }

  override def getMptNodeByHash(hash: ByteString): Option[MptNode] = mptNodeStorage.get(hash)

  override def save(blockHash: ByteString, blockBody: BlockBody): Unit = blockBodiesStorage.put(blockHash, blockBody)

  override def save(blockHash: ByteString, receipts: Seq[Receipt]): Unit = receiptStorage.put(blockHash, receipts)

  override def save(hash: ByteString, evmCode: ByteString): Unit = evmCodeStorage.put(hash, evmCode)

  def save(blockhash: ByteString, td: BigInt): Unit = totalDifficultyStorage.put(blockhash, td)

  override def save(node: MptNode): Unit = mptNodeStorage.put(node)

  override protected def getHashByBlockNumber(number: BigInt): Option[ByteString] = if (number == 0) {
    Some(Config.Blockchain.genesisHash)
  } else {
    blockNumberMappingStorage.get(number)
  }

  private def saveBlockNumberMapping(number: BigInt, hash: ByteString): Unit = blockNumberMappingStorage.put(number, hash)

  override def removeBlock(blockHash: ByteString): Unit = {
    blockHeadersStorage.remove(blockHash)
    blockBodiesStorage.remove(blockHash)
    totalDifficultyStorage.remove(blockHash)
    receiptStorage.remove(blockHash)
  }
}

trait BlockchainStorages {
  val blockHeadersStorage: BlockHeadersStorage
  val blockBodiesStorage: BlockBodiesStorage
  val blockNumberMappingStorage: BlockNumberMappingStorage
  val receiptStorage: ReceiptStorage
  val evmCodeStorage: EvmCodeStorage
  val mptNodeStorage: MptNodeStorage
  val totalDifficultyStorage: TotalDifficultyStorage
}

object BlockchainImpl {
  def apply(storages: BlockchainStorages): BlockchainImpl =
    new BlockchainImpl(
      blockHeadersStorage = storages.blockHeadersStorage,
      blockBodiesStorage = storages.blockBodiesStorage,
      blockNumberMappingStorage = storages.blockNumberMappingStorage,
      receiptStorage = storages.receiptStorage,
      evmCodeStorage = storages.evmCodeStorage,
      mptNodeStorage = storages.mptNodeStorage,
      totalDifficultyStorage = storages.totalDifficultyStorage
    )
}
