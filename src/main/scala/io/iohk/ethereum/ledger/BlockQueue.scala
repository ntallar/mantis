package io.iohk.ethereum.ledger

import akka.util.ByteString
import io.iohk.ethereum.domain.{Block, Blockchain}
import io.iohk.ethereum.ledger.BlockQueue.{Leaf, QueuedBlock}
import io.iohk.ethereum.utils.Config.SyncConfig
import io.iohk.ethereum.utils.Logger

import scala.annotation.tailrec
import scala.collection.mutable


object BlockQueue {
  case class QueuedBlock(block: Block, totalDifficulty: Option[BigInt])
  case class Leaf(hash: ByteString, totalDifficulty: BigInt)

  def apply(blockchain: Blockchain, syncConfig: SyncConfig): BlockQueue =
    new BlockQueue(blockchain, syncConfig.maxQueuedBlockNumberAhead, syncConfig.maxQueuedBlockNumberBehind)
}

class BlockQueue(blockchain: Blockchain, val maxQueuedBlockNumberAhead: Int, val maxQueuedBlockNumberBehind: Int) extends Logger {

  // note these two maps make this class thread-unsafe
  private val blocks = mutable.Map[ByteString, QueuedBlock]()
  private val parentToChildren = mutable.Map[ByteString, Set[ByteString]]()

  /**
    * Enqueue a block for optional later inclusion into the blockchain.
    * Queued blocks are stored as trees with bi-directional relations. Therefore when a younger blocks arrives,
    * for which the total difficulty is known, we can update total difficulties of all its descendants.
    *
    * The queue is bounded by configured limits in relation to current best block number - i.e. if the block to be
    * enqueued is too far behind or too far ahead the current best block number it will not be added. Also other such
    * blocks, that are already enqueued, will be removed.
    *
    * @param block the block to be enqueued
    * @return if the newly enqueued block is part of a known branch (rooted somewhere on the main chain), return
    *         the leaf hash and its total difficulty, otherwise None
    */
  def enqueueBlock(block: Block, bestBlockNumber: BigInt = blockchain.getBestBlockNumber()): Option[Leaf] = {
    import block.signedHeader.header._
    val blockHash = block.signedHeader.hash
    cleanUp(bestBlockNumber)

    blocks.get(blockHash) match {

      case Some(_) =>
        log.debug(s"Block (${block.idTag}) already in queue. ")
        None

      case None if isNumberOutOfRange(number, bestBlockNumber) =>
        log.debug(s"Block (${block.idTag} is outside accepted range. Current best block number is: $bestBlockNumber")
        None

      case None =>
        val parentTd = blockchain.getTotalDifficultyByHash(parentHash)

        parentTd match {

          case Some(_) =>
            addBlock(block, parentTd)
            log.debug(s"Enqueued new block (${block.idTag}) with parent on the main chain")
            updateTotalDifficulties(blockHash)

          case None =>
            addBlock(block, parentTd)
            findClosestChainedAncestor(block) match {
              case Some(ancestor) =>
                log.debug(s"Enqueued new block (${block.idTag}) to a rooted sidechain")
                updateTotalDifficulties(ancestor)

              case None =>
                log.debug(s"Enqueued new block (${block.idTag}) with unknown relation to the main chain")
                None
            }
        }
    }
  }

  def getBlockByHash(hash: ByteString): Option[Block] =
    blocks.get(hash).map(_.block)

  def isQueued(hash: ByteString): Boolean =
    blocks.get(hash).isDefined


  /**
    * Takes a branch going from descendant block upwards to the oldest ancestor
    * @param descendant the youngest block to be removed
    * @param dequeue should the branch be removed from the queue. Shared part of branch won't be removed
    * @return full branch from oldest ancestor to descendant, even if not all of it is removed
    */
  def getBranch(descendant: ByteString, dequeue: Boolean): List[Block] = {

    def recur(hash: ByteString, childShared: Boolean): List[Block] = {
      blocks.get(hash) match {
        case Some(QueuedBlock(block, _)) =>
          import block.signedHeader.header.parentHash

          val isShared = childShared || parentToChildren.get(hash).exists(_.nonEmpty)
          if (!isShared && dequeue) {
            val siblings = parentToChildren.get(parentHash)
            siblings.foreach(sbls => parentToChildren += parentHash -> (sbls - hash))
            blocks -= hash
          }

          block :: recur(parentHash, isShared)

        case None =>
          Nil
      }
    }

    recur(descendant, false).reverse
  }

  /**
    * Removes a whole subtree begining with the ancestor. To be used when ancestor fails to execute
    * @param ancestor hash of the ancestor block
    */
  def removeSubtree(ancestor: ByteString): Unit =
    blocks.get(ancestor).foreach { case QueuedBlock(block, _) =>
      val children = parentToChildren.getOrElse(ancestor, Set.empty)
      children.foreach(removeSubtree)
      blocks -= block.signedHeader.hash
      parentToChildren -= block.signedHeader.hash
    }

  /**
    * Removes stale blocks - too old or too young in relation the current best block number
    * @param bestBlockNumber - best block number of the main chain
    */
  private def cleanUp(bestBlockNumber: BigInt): Unit = {
    val staleHashes = blocks.values.collect {
      case QueuedBlock(b, _) if isNumberOutOfRange(b.signedHeader.header.number, bestBlockNumber) =>
        b.signedHeader.hash
    }

    blocks --= staleHashes
    parentToChildren --= staleHashes
  }

  /**
    * Updated total difficulties for a subtree.
    * @param ancestor An ancestor's hash that determines the subtree
    * @return Best leaf from the affected subtree
    */
  private def updateTotalDifficulties(ancestor: ByteString): Option[Leaf] = {
    blocks.get(ancestor).flatMap(_.totalDifficulty).map { td =>
      parentToChildren.get(ancestor) match {

        case Some(children) if children.nonEmpty =>
          val updatedChildren = children.flatMap(blocks.get)
            .map(qb => qb.copy(totalDifficulty = Some(td + qb.block.signedHeader.header.difficulty)))
          updatedChildren.foreach(qb => blocks += qb.block.signedHeader.hash -> qb)
          updatedChildren.flatMap(qb => updateTotalDifficulties(qb.block.signedHeader.hash)).maxBy(_.totalDifficulty)

        case _ =>
          Leaf(ancestor, td)
      }
    }
  }

  /**
    * Find a closest (youngest) chained ancestor. Chained means being part of a known chain, thus having total
    * difficulty defined
    *
    * @param descendant the block we start the search from
    * @return hash of the ancestor, if found
    */
  @tailrec
  private def findClosestChainedAncestor(descendant: Block): Option[ByteString] =
    blocks.get(descendant.signedHeader.header.parentHash) match {
      case Some(QueuedBlock(block, Some(_))) =>
        Some(block.signedHeader.hash)

      case Some(QueuedBlock(block, None)) =>
        findClosestChainedAncestor(block)

      case None =>
        None
    }

  private def addBlock(block: Block, parentTd: Option[BigInt]): Unit = {
    import block.signedHeader.header._
    val blockHash = block.signedHeader.hash
    val td = parentTd.map(_ + difficulty)
    blocks += blockHash -> QueuedBlock(block, td)

    val siblings = parentToChildren.getOrElse(parentHash, Set.empty)
    parentToChildren += parentHash -> (siblings + blockHash)
  }

  private def isNumberOutOfRange(blockNumber: BigInt, bestBlockNumber: BigInt): Boolean =
    blockNumber - bestBlockNumber > maxQueuedBlockNumberAhead ||
    bestBlockNumber - blockNumber > maxQueuedBlockNumberBehind
}
