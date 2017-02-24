package io.iohk.ethereum.network.p2p.validators

import akka.util.ByteString
import io.iohk.ethereum.domain.BlockHeader
import io.iohk.ethereum.crypto.{kec256, kec512}

object BlockHeaderValidator {

  val MaxExtraDataSize: Int = 32
  val GasLimitBoundDivisor: Int = 1024
  val MinGasLimit: BigInt = 125000
  val DifficultyBoundDivision: Int = 2048
  val HomesteadBlock: BigInt = 1150000
  val FrontierTimestampDiffLimit: Int = -99
  val ExpDifficultyPeriod: Int = 100000
  val MinimumDifficulty: BigInt = 131072

  import BlockHeaderError._

  /** This method allows validate a BlockHeader (stated on
    * section 4.4.4 of http://paper.gavwood.com/).
    *
    * @param blockHeader BlockHeader to validate.
    * @param parentHeader BlockHeader of the parent of the block to validate.
    */
  def validate(blockHeader: BlockHeader, parentHeader: BlockHeader): Either[BlockHeaderError, BlockHeader] = {
    for {
      _ <- validateExtraData(blockHeader)
      _ <- validateTimestamp(blockHeader, parentHeader)
      _ <- validateDifficulty(blockHeader, parentHeader)
      _ <- validateGasUsed(blockHeader)
      _ <- validateGasLimit(blockHeader, parentHeader)
      _ <- validateNumber(blockHeader, parentHeader)
      _ <- validatePoW(blockHeader)
    } yield blockHeader
  }

  /**
    * Validates [[io.iohk.ethereum.domain.BlockHeader.extraData]] length
    * based on validations stated in section 4.4.4 of http://paper.gavwood.com/
    *
    * @param blockHeader BlockHeader to validate.
    * @return BlockHeader if valid, an [[HeaderExtraDataError]] otherwise
    */
  private def validateExtraData(blockHeader: BlockHeader): Either[BlockHeaderError, BlockHeader] =
    if(blockHeader.extraData.length <= MaxExtraDataSize) Right(blockHeader)
    else Left(HeaderExtraDataError)

  /**
    * Validates [[io.iohk.ethereum.domain.BlockHeader.unixTimestamp]] is greater than the one of its parent
    * based on validations stated in section 4.4.4 of http://paper.gavwood.com/
    *
    * @param blockHeader BlockHeader to validate.
    * @param parentHeader BlockHeader of the parent of the block to validate.
    * @return BlockHeader if valid, an [[HeaderTimestampError]] otherwise
    */
  private def validateTimestamp(blockHeader: BlockHeader, parentHeader: BlockHeader): Either[BlockHeaderError, BlockHeader] =
    if(blockHeader.unixTimestamp > parentHeader.unixTimestamp) Right(blockHeader)
    else Left(HeaderTimestampError)

  /**
    * Validates [[io.iohk.ethereum.domain.BlockHeader.difficulty]] is correct
    * based on validations stated in section 4.4.4 of http://paper.gavwood.com/
    *
    * @param blockHeader BlockHeader to validate.
    * @param parentHeader BlockHeader of the parent of the block to validate.
    * @return BlockHeader if valid, an [[HeaderDifficultyError]] otherwise
    */
  private def validateDifficulty(blockHeader: BlockHeader, parentHeader: BlockHeader): Either[BlockHeaderError, BlockHeader] =
    if(calculateDifficulty(blockHeader, parentHeader) == blockHeader.difficulty) Right(blockHeader)
    else Left(HeaderDifficultyError)

  /**
    * Validates [[io.iohk.ethereum.domain.BlockHeader.gasUsed]] is not greater than [[io.iohk.ethereum.domain.BlockHeader.gasLimit]]
    * based on validations stated in section 4.4.4 of http://paper.gavwood.com/
    *
    * @param blockHeader BlockHeader to validate.
    * @return BlockHeader if valid, an [[HeaderGasUsedError]] otherwise
    */
  private def validateGasUsed(blockHeader: BlockHeader): Either[BlockHeaderError, BlockHeader] =
    if(blockHeader.gasUsed<=blockHeader.gasLimit) Right(blockHeader)
    else Left(HeaderGasUsedError)

  /**
    * Validates [[io.iohk.ethereum.domain.BlockHeader.gasLimit]] follows the restrictions based on its parent gasLimit
    * based on validations stated in section 4.4.4 of http://paper.gavwood.com/
    *
    * @param blockHeader BlockHeader to validate.
    * @param parentHeader BlockHeader of the parent of the block to validate.
    * @return BlockHeader if valid, an [[HeaderGasLimitError]] otherwise
    */
  private def validateGasLimit(blockHeader: BlockHeader, parentHeader: BlockHeader): Either[BlockHeaderError, BlockHeader] = {
    val gasLimitDiff = (blockHeader.gasLimit - parentHeader.gasLimit).abs
    val gasLimitDiffLimit = parentHeader.gasLimit / GasLimitBoundDivisor
    if(gasLimitDiff < gasLimitDiffLimit && blockHeader.gasLimit >= MinGasLimit) Right(blockHeader)
    else Left(HeaderGasLimitError)
  }

  /**
    * Validates [[io.iohk.ethereum.domain.BlockHeader.number]] is the next one after its parents number
    * based on validations stated in section 4.4.4 of http://paper.gavwood.com/
    *
    * @param blockHeader BlockHeader to validate.
    * @param parentHeader BlockHeader of the parent of the block to validate.
    * @return BlockHeader if valid, an [[HeaderNumberError]] otherwise
    */
  private def validateNumber(blockHeader: BlockHeader, parentHeader: BlockHeader): Either[BlockHeaderError, BlockHeader] =
    if(blockHeader.number == parentHeader.number + 1) Right(blockHeader)
    else Left(HeaderNumberError)

  /**
    * Validates [[io.iohk.ethereum.domain.BlockHeader.nonce]] and [[io.iohk.ethereum.domain.BlockHeader.mixHash]] are correct
    * based on validations stated in section 4.4.4 of http://paper.gavwood.com/
    *
    * @param blockHeader BlockHeader to validate.
    * @return BlockHeader if valid, an [[HeaderPoWError]] otherwise
    */
  //FIXME: Simple PoW validation without using DAG
  private def validatePoW(blockHeader: BlockHeader): Either[BlockHeaderError, BlockHeader] = {
    val powBoundary = BigInt(2).pow(256) / blockHeader.difficulty
    val powValue = BigInt(1, calculatePoWValue(blockHeader).toArray)
    if(powValue <= powBoundary) Right(blockHeader)
    else Left(HeaderPoWError)
  }

  private def calculateDifficulty(blockHeader: BlockHeader, parentHeader: BlockHeader): BigInt = {
    val x: BigInt = parentHeader.difficulty / DifficultyBoundDivision
    val c: BigInt =
      if(blockHeader.number < HomesteadBlock){
        if(blockHeader.unixTimestamp < parentHeader.unixTimestamp + 13) 1 else -1
      }else{
        val timestampDiff = blockHeader.unixTimestamp - parentHeader.unixTimestamp
        math.max(1 - timestampDiff / 10, FrontierTimestampDiffLimit)
      }
    val difficultyBombExponent: Int = (blockHeader.number / ExpDifficultyPeriod - 2).toInt
    val difficultyBomb: BigInt =
      if(difficultyBombExponent >= 0)
        BigInt(2).pow(difficultyBombExponent)
      else 0
    val difficultyWithoutBomb = MinimumDifficulty.max(parentHeader.difficulty + x * c)
    difficultyWithoutBomb + difficultyBomb
  }

  private def calculatePoWValue(blockHeader: BlockHeader): ByteString = {
    val nonceReverted = blockHeader.nonce.reverse
    val hashBlockWithoutNonce = kec256(BlockHeader.getEncodedWithoutNonce(blockHeader))
    val seedHash = kec512(hashBlockWithoutNonce ++ nonceReverted)

    ByteString(kec256(seedHash ++ blockHeader.mixHash))
  }
}

sealed trait BlockHeaderError

object BlockHeaderError {
  case object HeaderExtraDataError extends BlockHeaderError
  case object HeaderTimestampError extends BlockHeaderError
  case object HeaderDifficultyError extends BlockHeaderError
  case object HeaderGasUsedError extends BlockHeaderError
  case object HeaderGasLimitError extends BlockHeaderError
  case object HeaderNumberError extends BlockHeaderError
  case object HeaderPoWError extends BlockHeaderError
}
