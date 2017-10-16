package io.iohk.ethereum.vm

import io.iohk.ethereum.domain.UInt256._
import io.iohk.ethereum.domain.{Account, Address}
import io.iohk.ethereum.vm.Generators._
import org.scalatest.prop.PropertyChecks
import org.scalatest.{FunSuite, Matchers}

class OpCodeGasSpecPostEip161 extends FunSuite with OpCodeTesting with Matchers with PropertyChecks {

  override val config = EvmConfig.PostEIP161ConfigBuilder(None)

  import config.feeSchedule._

  test(SELFDESTRUCT) { op =>
    val stateGen = getProgramStateGen(
      stackGen = getStackGen(elems = 1),
      evmConfig = config
    )

    // Sending refund to a non-existent account
    forAll(stateGen) { stateIn =>
      val (refund, _) = stateIn.stack.pop
      whenever(stateIn.world.getAccount(Address(refund)).isEmpty && stateIn.ownBalance > 0) {
        val stateOut = op.execute(stateIn)
        stateOut.gasRefund shouldEqual R_selfdestruct
        verifyGas(G_selfdestruct + G_newaccount, stateIn, stateOut)
      }
    }

    // Sending refund to an already existing account not dead account
    forAll(stateGen) { (stateIn) =>
      val (refund, _) = stateIn.stack.pop
      val world = stateIn.world.saveAccount(
        Address(refund),
        Account.empty().increaseNonce())
      val updatedStateIn = stateIn.withWorld(world)
      val stateOut = op.execute(updatedStateIn)
      verifyGas(G_selfdestruct, updatedStateIn, stateOut)
      stateOut.gasRefund shouldEqual R_selfdestruct
    }

    // Owner account was already selfdestructed
    forAll(stateGen) { stateIn =>
      val (refund, _) = stateIn.stack.pop
      whenever(stateIn.world.getAccount(Address(refund)).isEmpty && stateIn.ownBalance > 0) {
        val updatedStateIn = stateIn.withAddressToDelete(stateIn.context.env.ownerAddr)
        val stateOut = op.execute(updatedStateIn)
        verifyGas(G_selfdestruct + G_newaccount, updatedStateIn, stateOut)
        stateOut.gasRefund shouldEqual 0
      }
    }
  }
}
