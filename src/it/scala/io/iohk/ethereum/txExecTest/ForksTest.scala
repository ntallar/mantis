package io.iohk.ethereum.txExecTest

import akka.util.ByteString
import io.iohk.ethereum.daoFork.DaoForkConfig
import io.iohk.ethereum.domain.{Address, BlockchainImpl, Receipt, UInt256}
import io.iohk.ethereum.ledger.LedgerImpl
import io.iohk.ethereum.txExecTest.util.FixtureProvider
import io.iohk.ethereum.utils.{BlockchainConfig, MonetaryPolicyConfig}
import io.iohk.ethereum.validators._
import io.iohk.ethereum.vm.VM
import org.scalatest.{FlatSpec, Matchers}

class ForksTest extends FlatSpec with Matchers {

  val blockchainConfig = new BlockchainConfig {
    override val frontierBlockNumber: BigInt = 0
    override val homesteadBlockNumber: BigInt = 3
    override val eip150BlockNumber: BigInt = 5
    override val eip160BlockNumber: BigInt = 7
    override val eip155BlockNumber: BigInt = 0

    override val chainId: Byte = 0x3d
    override val monetaryPolicyConfig: MonetaryPolicyConfig = MonetaryPolicyConfig(5000000, 0.2, 5000000000000000000L)

    // unused
    override val customGenesisFileOpt: Option[String] = None
    override val difficultyBombPauseBlockNumber: BigInt = Long.MaxValue
    override val difficultyBombContinueBlockNumber: BigInt = Long.MaxValue
    override val accountStartNonce: UInt256 = UInt256.Zero
    override val daoForkConfig: Option[DaoForkConfig] = None
  }

  val noErrors = a[Right[_, Seq[Receipt]]]

  val validators = new Validators {
    val blockValidator: BlockValidator = BlockValidator
    val blockHeaderValidator: BlockHeaderValidator = new BlockHeaderValidatorImpl(blockchainConfig)
    val ommersValidator: OmmersValidator = new OmmersValidatorImpl(blockchainConfig)
    val signedTransactionValidator: SignedTransactionValidator = new SignedTransactionValidatorImpl(blockchainConfig)
  }

  "Ledger" should "execute blocks with respect to forks" in {
    val fixtures: FixtureProvider.Fixture = FixtureProvider.loadFixtures("/txExecTest/forksTest")

    val startBlock = 1
    val endBlock = 11

    (startBlock to endBlock) foreach { blockToExecute =>
      val storages = FixtureProvider.prepareStorages(blockToExecute - 1, fixtures)
      val blockchain = BlockchainImpl(storages)
      val ledger = new LedgerImpl(VM, blockchain, blockchainConfig)

      ledger.executeBlock(fixtures.blockByNumber(blockToExecute), validators) shouldBe noErrors
    }
  }

}
