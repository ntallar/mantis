package io.iohk.ethereum.vm

import io.iohk.ethereum.crypto.kec256
import akka.util.ByteString
import org.scalatest.{Matchers, WordSpec}
import Assembly._
import io.iohk.ethereum.crypto._
import io.iohk.ethereum.domain.{Account, Address, UInt256}
import io.iohk.ethereum.utils.ByteUtils
import io.iohk.ethereum.vm.MockWorldState._
import org.scalatest.prop.PropertyChecks

// scalastyle:off object.name
// scalastyle:off file.size.limit
class CallOpcodesSpec extends WordSpec with Matchers with PropertyChecks {

  val config = EvmConfig.PostEIP161Config

  import config.feeSchedule._

  object fxt {

    val ownerAddr = Address(0xcafebabe)
    val extAddr = Address(0xfacefeed)
    val callerAddr = Address(0xdeadbeef)

    val ownerOffset = UInt256(0)
    val callerOffset = UInt256(1)
    val valueOffset = UInt256(2)

    val extCode = Assembly(
      //store owner address
      ADDRESS,
      PUSH1, ownerOffset.toInt,
      SSTORE,

      //store caller address
      CALLER,
      PUSH1, callerOffset.toInt,
      SSTORE,

      //store call value
      CALLVALUE,
      PUSH1, valueOffset.toInt,
      SSTORE,

      // return first half of unmodified input data
      PUSH1, 2,
      CALLDATASIZE,
      DIV,
      PUSH1, 0,
      DUP2,
      DUP2,
      DUP1,
      CALLDATACOPY,
      RETURN
    )

    val selfDestructCode = Assembly(
      PUSH20, callerAddr.bytes,
      SELFDESTRUCT
    )

    val selfDestructTransferringToSelfCode = Assembly(
      PUSH20, extAddr.bytes,
      SELFDESTRUCT
    )

    val sstoreWithClearCode = Assembly(
      //Save a value to the storage
      PUSH1, 10,
      PUSH1, 0,
      SSTORE,

      //Clear the store
      PUSH1, 0,
      PUSH1, 0,
      SSTORE
    )

    val valueToReturn = 23
    val returnSingleByteProgram = Assembly(
      PUSH1, valueToReturn,
      PUSH1, 0,
      MSTORE,
      PUSH1, 1,
      PUSH1, 31,
      RETURN
    )

    val inputData = Generators.getUInt256Gen().sample.get.bytes
    val expectedMemCost = config.calcMemCost(inputData.size, inputData.size, inputData.size / 2)

    val initialBalance = UInt256(1000)

    val requiredGas = {
      val storageCost = 3 * G_sset
      val memCost = config.calcMemCost(0, 0, 32)
      val copyCost = G_copy * wordsForBytes(32)

      extCode.linearConstGas(config) + storageCost + memCost + copyCost
    }

    val gasMargin = 13

    val initialOwnerAccount = Account(balance = initialBalance)

    val extProgram = extCode.program
    val invalidProgram = Program(extProgram.code.init :+ INVALID.code)
    val selfDestructProgram = selfDestructCode.program
    val sstoreWithClearProgram = sstoreWithClearCode.program
    val accountWithCode: ByteString => Account = code => Account.empty().withCode(kec256(code))

    val worldWithoutExtAccount = MockWorldState(touchedAccounts = Some(Set.empty)).saveAccount(ownerAddr, initialOwnerAccount)

    val worldWithExtAccount = worldWithoutExtAccount.saveAccount(extAddr, accountWithCode(extProgram.code))
      .saveCode(extAddr, extProgram.code)

    val worldWithExtEmptyAccount = worldWithoutExtAccount.saveAccount(extAddr, Account.empty())

    val worldWithInvalidProgram = worldWithoutExtAccount.saveAccount(extAddr, accountWithCode(invalidProgram.code))
      .saveCode(extAddr, invalidProgram.code)

    val worldWithSelfDestructProgram = worldWithoutExtAccount.saveAccount(extAddr, accountWithCode(selfDestructProgram.code))
      .saveCode(extAddr, selfDestructCode.code)

    val worldWithSelfDestructSelfProgram = worldWithoutExtAccount.saveAccount(extAddr, Account.empty())
      .saveCode(extAddr, selfDestructTransferringToSelfCode.code)

    val worldWithSstoreWithClearProgram = worldWithoutExtAccount.saveAccount(extAddr, accountWithCode(sstoreWithClearProgram.code))
      .saveCode(extAddr, sstoreWithClearCode.code)

    val worldWithReturnSingleByteCode = worldWithoutExtAccount.saveAccount(extAddr, accountWithCode(returnSingleByteProgram.code))
      .saveCode(extAddr, returnSingleByteProgram.code)

    val env = ExecEnv(ownerAddr, callerAddr, callerAddr, 1, ByteString.empty, 123, Program(ByteString.empty), null, 0)
    val context: PC = ProgramContext(env, ownerAddr, 2 * requiredGas, worldWithExtAccount, config)
  }

  case class CallResult(
    op: CallOp,
    context: ProgramContext[MockWorldState, MockStorage] = fxt.context,
    inputData: ByteString = fxt.inputData,
    gas: BigInt = fxt.requiredGas + fxt.gasMargin,
    to: Address = fxt.extAddr,
    value: UInt256 = fxt.initialBalance / 2,
    inOffset: UInt256 = UInt256.Zero,
    inSize: UInt256 = fxt.inputData.size,
    outOffset: UInt256 = fxt.inputData.size,
    outSize: UInt256 = fxt.inputData.size / 2
  ) {
    private val params = Seq(UInt256(gas), to.toUInt256, value, inOffset, inSize, outOffset, outSize).reverse

    private val paramsForDelegate = params.take(4) ++ params.drop(5)

    private val stack = Stack.empty().push(if (op == DELEGATECALL) paramsForDelegate else params)
    private val mem = Memory.empty.store(UInt256.Zero, inputData)

    val stateIn: PS = ProgramState(context).withStack(stack).withMemory(mem)
    val stateOut: PS = op.execute(stateIn)
    val world: MockWorldState = stateOut.world

    val ownBalance: UInt256 = world.getBalance(context.env.ownerAddr)
    val extBalance: UInt256 = world.getBalance(to)

    val ownStorage: MockStorage = world.getStorage(context.env.ownerAddr)
    val extStorage: MockStorage = world.getStorage(to)
  }

  "CALL" when {

    "external contract terminates normally" should {

      val call = CallResult(op = CALL)

      "update external account's storage" in {
        call.ownStorage shouldEqual MockStorage.Empty
        call.extStorage.data.size shouldEqual 3
      }

      "update external account's balance" in {
        call.extBalance shouldEqual call.value
        call.ownBalance shouldEqual fxt.initialBalance - call.value
      }

      "pass correct addresses and value" in {
        Address(call.extStorage.load(fxt.ownerOffset)) shouldEqual fxt.extAddr
        Address(call.extStorage.load(fxt.callerOffset)) shouldEqual fxt.ownerAddr
        call.extStorage.load(fxt.valueOffset) shouldEqual call.value
      }

      "return 1" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256.One
      }

      "should store contract's return data in memory" in {
        //here the passed data size is equal to the contract's return data size (half of the input data)

        val expectedData = fxt.inputData.take(fxt.inputData.size / 2)
        val actualData = call.stateOut.memory.load(call.outOffset, call.outSize)._1
        actualData shouldEqual expectedData

        val expectedSize = (call.outOffset + call.outSize).toInt
        val actualSize = call.stateOut.memory.size
        expectedSize shouldEqual actualSize
      }

      "consume correct gas (refund unused gas)" in {
        val expectedGas = fxt.requiredGas - G_callstipend + G_call + G_callvalue + fxt.expectedMemCost
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "call depth limit is reached" should {

      val context: PC = fxt.context.copy(env = fxt.env.copy(callDepth = EvmConfig.MaxCallDepth))
      val call = CallResult(op = CALL, context = context)

      "not modify world state" in {
        call.world shouldEqual fxt.worldWithExtAccount
      }

      "return 0" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256.Zero
      }

      "consume correct gas (refund call gas)" in {
        val expectedGas = G_call + G_callvalue - G_callstipend + config.calcMemCost(32, 32, 16)
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "call value is greater than balance" should {

      val call = CallResult(op = CALL, value = fxt.initialBalance + 1)

      "not modify world state" in {
        call.world shouldEqual fxt.worldWithExtAccount
      }

      "return 0" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256.Zero
      }

      "consume correct gas (refund call gas)" in {
        val expectedGas = G_call + G_callvalue - G_callstipend + config.calcMemCost(32, 32, 16)
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "call value is zero" should {
      val call = CallResult(op = CALL, value = 0)

      "adjust gas cost" in {
        val expectedGas = fxt.requiredGas + G_call + fxt.expectedMemCost - (G_sset - G_sreset)
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "external contract terminates abnormally" should {

      val context: PC = fxt.context.copy(world = fxt.worldWithInvalidProgram)
      val call = CallResult(op = CALL, context)

      "not modify world state" in {
        call.world shouldEqual fxt.worldWithInvalidProgram
      }

      "return 0" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256.Zero
      }

      "consume all call gas" in {
        val expectedGas = fxt.requiredGas + fxt.gasMargin + G_call + G_callvalue + fxt.expectedMemCost
        call.stateOut.gasUsed shouldEqual expectedGas
      }

      "extend memory" in {
        UInt256(call.stateOut.memory.size) shouldEqual call.outOffset + call.outSize
      }
    }

    "calling a non-existent account" should {

      val context: PC = fxt.context.copy(world = fxt.worldWithoutExtAccount)
      val contextEmptyAccount: PC = fxt.context.copy(world = fxt.worldWithExtEmptyAccount)

      val call = CallResult(op = CALL, context)
      val callEmptyAccount = CallResult(op = CALL, contextEmptyAccount)
      val callZeroTransfer = CallResult(op = CALL, contextEmptyAccount, value = UInt256.Zero)

      "create new account and add to its balance" in {
        call.extBalance shouldEqual call.value
        call.ownBalance shouldEqual fxt.initialBalance - call.value
      }

      "return 1" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256.One
      }

      "consume correct gas (refund call gas, add new account modifier)" in {
        val expectedGas = G_call + G_callvalue + G_newaccount - G_callstipend + fxt.expectedMemCost
        call.stateOut.gasUsed shouldEqual expectedGas
      }

      "consume correct gas (refund call gas, add new account modifier) when transferring value to Empty Account" in {
        val expectedGas = G_call + G_callvalue + G_newaccount - G_callstipend + fxt.expectedMemCost
        callEmptyAccount.stateOut.gasUsed shouldEqual expectedGas
      }

      "consume correct gas when transferring no value to Empty Account" in {
        val expectedGas = G_call + fxt.expectedMemCost
        callZeroTransfer.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "calling a precompiled contract" should {
      val contractAddress = Address(1) // ECDSA recovery
      val invalidSignature = ByteString(Array.fill(128)(0.toByte))
      val world = fxt.worldWithoutExtAccount.saveAccount(contractAddress, Account(balance = 1))
      val context: PC = fxt.context.copy(world = world)
      val call = CallResult(op = CALL, context = context, to = contractAddress, inputData = invalidSignature,
        inOffset = 0, inSize = 128, outOffset = 0, outSize = 128
      )

      "compute a correct result" in {
        // For invalid signature the return data should be empty, so the memory should not be modified.
        // This is more interesting than checking valid signatures which are tested elsewhere
        val (result, _) = call.stateOut.memory.load(call.outOffset, call.outSize)
        val expected = invalidSignature

        result shouldEqual expected
      }

      "return 1" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256.One
      }

      "update precompiled contract's balance" in {
        call.extBalance shouldEqual call.value + 1
        call.ownBalance shouldEqual fxt.initialBalance - call.value
      }

      "consume correct gas" in {
        val contractCost = UInt256(3000)
        val expectedGas = contractCost - G_callstipend + G_call + G_callvalue // memory not increased
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "calling a program that executes a SELFDESTRUCT" should {

      "refund the correct amount of gas" in {
        val context: PC = fxt.context.copy(world = fxt.worldWithSelfDestructProgram)
        val call = CallResult(op = CALL, context)
        call.stateOut.gasRefund shouldBe call.stateOut.config.feeSchedule.R_selfdestruct
        call.world.touchedAccounts.get.size shouldEqual 3
      }

      "not refund gas if account was already self destructed" in {
        val context: PC = fxt.context.copy(
          world = fxt.worldWithSelfDestructProgram,
          initialAddressesToDelete = Set(fxt.extAddr))
        val call = CallResult(op = CALL, context)
        call.stateOut.gasRefund shouldBe 0
        call.world.touchedAccounts.get.size shouldEqual 3
      }

      "destruct ether if own address equals refund address" in {
        val context: PC = fxt.context.copy(world = fxt.worldWithSelfDestructSelfProgram)
        val call = CallResult(op = CALL, context)
        call.stateOut.world.getGuaranteedAccount(fxt.extAddr).balance shouldEqual UInt256.Zero
        call.stateOut.addressesToDelete.contains(fxt.extAddr) shouldBe true
        call.world.touchedAccounts.get.size shouldEqual 2
      }
    }

    "calling a program that executes a SSTORE that clears the storage" should {

      val context: PC = fxt.context.copy(world = fxt.worldWithSstoreWithClearProgram)
      val call = CallResult(op = CALL, context)

      "refund the correct amount of gas" in {
        call.stateOut.gasRefund shouldBe call.stateOut.config.feeSchedule.R_sclear
      }

    }

    "more gas than available is provided" should {
      def call(config: EvmConfig): CallResult = {
        val context: PC = fxt.context.copy(config = config)
        CallResult(op = CALL, context = context, gas = UInt256.MaxValue / 2)
      }

      def callVarMemCost(config: EvmConfig): CallResult = {

        /**
          * Amount of memory which causes the improper OOG exception, if we don take memcost into account
          * during calculation of post EIP150 CALLOp gasCap: gasCap(state, gas, gExtra + memCost)
          */
        val gasFailingBeforeEIP150Fix = 141072

        val context: PC = fxt.context.copy(config = config)
        CallResult(
          op = CALL,
          context = context,
          inOffset = UInt256.Zero,
          inSize = fxt.inputData.size,
          outOffset = fxt.inputData.size,
          outSize = gasFailingBeforeEIP150Fix
        )
      }

      "go OOG before EIP-150" in {
        call(EvmConfig.HomesteadConfig).stateOut.error shouldEqual Some(OutOfGas)
      }

      "cap the provided gas after EIP-150" in {
        call(EvmConfig.PostEIP150Config).stateOut.stack.pop._1 shouldEqual UInt256.One
      }

      "go OOG before EIP-150 becaouse of extensive memory cost" in {
        callVarMemCost(EvmConfig.HomesteadConfig).stateOut.error shouldEqual Some(OutOfGas)
      }

      "cap memory cost post EIP-150" in {
        val callResult = callVarMemCost(EvmConfig.PostEIP150Config)
        callResult.stateOut.stack.pop._1 shouldEqual UInt256.One
      }
    }
  }

  "CALLCODE" when {
    "external code terminates normally" should {
      val call = CallResult(op = CALLCODE, outSize = fxt.inputData.size * 2)

      "update own account's storage" in {
        call.extStorage shouldEqual MockStorage.Empty
        call.ownStorage.data.size shouldEqual 3
      }

      "not update any account's balance" in {
        call.extBalance shouldEqual UInt256.Zero
        call.ownBalance shouldEqual fxt.initialBalance
      }

      "pass correct addresses and value" in {
        Address(call.ownStorage.load(fxt.ownerOffset)) shouldEqual fxt.ownerAddr
        Address(call.ownStorage.load(fxt.callerOffset)) shouldEqual fxt.ownerAddr
        call.ownStorage.load(fxt.valueOffset) shouldEqual call.value
      }

      "return 1" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256(1)
      }

      "should store contract's return data in memory" in {
        //here the passed data size is greater than the contract's return data size

        val expectedData = fxt.inputData.take(fxt.inputData.size / 2).padTo(call.outSize.toInt, 0)
        val actualData = call.stateOut.memory.load(call.outOffset, call.outSize)._1
        actualData shouldEqual expectedData

        val expectedSize = (call.outOffset + call.outSize).toInt
        val actualSize = call.stateOut.memory.size
        expectedSize shouldEqual actualSize
      }

      "consume correct gas (refund unused gas)" in {
        val expectedMemCost = config.calcMemCost(fxt.inputData.size, fxt.inputData.size, call.outSize)
        val expectedGas = fxt.requiredGas - G_callstipend + G_call + G_callvalue + expectedMemCost
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "call depth limit is reached" should {

      val context: PC = fxt.context.copy(env = fxt.env.copy(callDepth = EvmConfig.MaxCallDepth))
      val call = CallResult(op = CALLCODE, context = context)

      "not modify world state" in {
        call.world shouldEqual fxt.worldWithExtAccount
      }

      "return 0" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256.Zero
      }

      "consume correct gas (refund call gas)" in {
        val expectedGas = G_call + G_callvalue - G_callstipend + fxt.expectedMemCost
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "call value is greater than balance" should {

      val call = CallResult(op = CALLCODE, value = fxt.initialBalance + 1)

      "not modify world state" in {
        call.world shouldEqual fxt.worldWithExtAccount
      }

      "return 0" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256.Zero
      }

      "consume correct gas (refund call gas)" in {
        val expectedGas = G_call + G_callvalue - G_callstipend + fxt.expectedMemCost
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "call value is zero" should {
      val call = CallResult(op = CALL, value = 0)

      "adjust gas cost" in {
        val expectedGas = fxt.requiredGas + G_call + fxt.expectedMemCost - (G_sset - G_sreset)
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "external code terminates abnormally" should {
      val context: PC = fxt.context.copy(world = fxt.worldWithInvalidProgram)
      val call = CallResult(op = CALLCODE, context)

      "not modify world state" in {
        call.world shouldEqual fxt.worldWithInvalidProgram
      }

      "return 0" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256.Zero
      }

      "consume all call gas" in {
        val expectedGas = fxt.requiredGas + fxt.gasMargin + G_call + G_callvalue + fxt.expectedMemCost
        call.stateOut.gasUsed shouldEqual expectedGas
      }

      "extend memory" in {
        UInt256(call.stateOut.memory.size) shouldEqual call.outOffset + call.outSize
      }
    }

    "external account does not exist" should {
      val context: PC = fxt.context.copy(world = fxt.worldWithoutExtAccount)
      val call = CallResult(op = CALLCODE, context)

      "not modify world state" in {
        call.world shouldEqual fxt.worldWithoutExtAccount
      }

      "return 1" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256(1)
      }

      "consume correct gas (refund call gas)" in {
        val expectedGas = G_call + G_callvalue - G_callstipend + fxt.expectedMemCost
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "calling a precompiled contract" should {
      val contractAddress = Address(2) // SHA256
      val inputData = ByteString(Array.fill(128)(1.toByte))
      val world = fxt.worldWithoutExtAccount.saveAccount(contractAddress, Account(balance = 1))
      val context: PC = fxt.context.copy(world = world)
      val call = CallResult(op = CALLCODE, context = context, to = contractAddress, inputData = inputData,
        inOffset = 0, inSize = 128, outOffset = 128, outSize = 32
      )

      "compute a correct result" in {
        val (result, _) = call.stateOut.memory.load(call.outOffset, call.outSize)
        val expected = sha256(inputData)

        result shouldEqual expected
      }

      "return 1" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256.One
      }

      "not update precompiled contract's balance" in {
        call.extBalance shouldEqual 1
        call.ownBalance shouldEqual fxt.initialBalance
      }

      "consume correct gas" in {
        val contractCost = 60 + 12 * wordsForBytes(inputData.size)
        val expectedGas = contractCost - G_callstipend + G_call + G_callvalue + config.calcMemCost(128, 128, 32)
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "calling a program that executes a SELFDESTRUCT" should {

      val context: PC = fxt.context.copy(world = fxt.worldWithSelfDestructProgram)
      val call = CallResult(op = CALL, context)

      "refund the correct amount of gas" in {
        call.stateOut.gasRefund shouldBe call.stateOut.config.feeSchedule.R_selfdestruct
      }

    }

    "calling a program that executes a SSTORE that clears the storage" should {

      val context: PC = fxt.context.copy(world = fxt.worldWithSstoreWithClearProgram)
      val call = CallResult(op = CALL, context)

      "refund the correct amount of gas" in {
        call.stateOut.gasRefund shouldBe call.stateOut.config.feeSchedule.R_sclear
      }
    }

    "more gas than available is provided" should {
      def call(config: EvmConfig): CallResult = {
        val context: PC = fxt.context.copy(config = config)
        CallResult(op = CALLCODE, context = context, gas = UInt256.MaxValue / 2)
      }

      "go OOG before EIP-150" in {
        call(EvmConfig.HomesteadConfig).stateOut.error shouldEqual Some(OutOfGas)
      }

      "cap the provided gas after EIP-150" in {
        call(EvmConfig.PostEIP150Config).stateOut.stack.pop._1 shouldEqual UInt256.One
      }
    }
  }

  "DELEGATECALL" when {
    "external code terminates normally" should {
      val call = CallResult(op = DELEGATECALL, outSize = fxt.inputData.size / 4)

      "update own account's storage" in {
        call.extStorage shouldEqual MockStorage.Empty
        call.ownStorage.data.size shouldEqual 3
      }

      "not update any account's balance" in {
        call.extBalance shouldEqual UInt256.Zero
        call.ownBalance shouldEqual fxt.initialBalance
      }

      "pass correct addresses and value" in {
        Address(call.ownStorage.load(fxt.ownerOffset)) shouldEqual fxt.ownerAddr
        Address(call.ownStorage.load(fxt.callerOffset)) shouldEqual fxt.env.callerAddr
        call.ownStorage.load(fxt.valueOffset) shouldEqual fxt.env.value
      }

      "return 1" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256(1)
      }

      "should store contract's return data in memory" in {
        //here the passed data size is less than the contract's return data size

        val expectedData = fxt.inputData.take(call.outSize.toInt)
        val actualData = call.stateOut.memory.load(call.outOffset, call.outSize)._1
        actualData shouldEqual expectedData

        val expectedSize = (call.outOffset + call.outSize).toInt
        val actualSize = call.stateOut.memory.size
        expectedSize shouldEqual actualSize
      }

      "consume correct gas (refund unused gas)" in {
        val expectedMemCost = config.calcMemCost(fxt.inputData.size, fxt.inputData.size, call.outSize)
        val expectedGas = fxt.requiredGas + G_call + expectedMemCost
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "call depth limit is reached" should {

      val context: PC = fxt.context.copy(env = fxt.env.copy(callDepth = EvmConfig.MaxCallDepth))
      val call = CallResult(op = DELEGATECALL, context = context)

      "not modify world state" in {
        call.world shouldEqual fxt.worldWithExtAccount
      }

      "return 0" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256.Zero
      }

      "consume correct gas (refund call gas)" in {
        val expectedGas = G_call + fxt.expectedMemCost
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "external code terminates abnormally" should {
      val context: PC = fxt.context.copy(world = fxt.worldWithInvalidProgram)
      val call = CallResult(op = DELEGATECALL, context)

      "not modify world state" in {
        call.world shouldEqual fxt.worldWithInvalidProgram
      }

      "return 0" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256.Zero
      }

      "consume all call gas" in {
        val expectedGas = fxt.requiredGas + fxt.gasMargin + G_call + fxt.expectedMemCost
        call.stateOut.gasUsed shouldEqual expectedGas
      }

      "extend memory" in {
        UInt256(call.stateOut.memory.size) shouldEqual call.outOffset + call.outSize
      }
    }

    "external account does not exist" should {
      val context: PC = fxt.context.copy(world = fxt.worldWithoutExtAccount)
      val call = CallResult(op = DELEGATECALL, context)

      "not modify world state" in {
        call.world shouldEqual fxt.worldWithoutExtAccount
      }

      "return 1" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256(1)
      }

      "consume correct gas (refund call gas)" in {
        val expectedGas = G_call + fxt.expectedMemCost
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "calling a precompiled contract" should {
      val contractAddress = Address(3) // RIPEMD160
      val inputData = ByteString(Array.fill(128)(1.toByte))
      val world = fxt.worldWithoutExtAccount.saveAccount(contractAddress, Account(balance = 1))
      val context: PC = fxt.context.copy(world = world)
      val call = CallResult(op = DELEGATECALL, context = context, to = contractAddress, inputData = inputData,
        inOffset = 0, inSize = 128, outOffset = 128, outSize = 32
      )

      "compute a correct result" in {
        val (result, _) = call.stateOut.memory.load(call.outOffset, call.outSize)
        val expected = ByteUtils.padLeft(ripemd160(inputData), 32)

        result shouldEqual expected
      }

      "return 1" in {
        call.stateOut.stack.pop._1 shouldEqual UInt256.One
      }

      "not update precompiled contract's balance" in {
        call.extBalance shouldEqual 1
        call.ownBalance shouldEqual fxt.initialBalance
      }

      "consume correct gas" in {
        val contractCost = 600 + 120 * wordsForBytes(inputData.size)
        val expectedGas = contractCost + G_call + config.calcMemCost(128, 128, 20)
        call.stateOut.gasUsed shouldEqual expectedGas
      }
    }

    "calling a program that executes a SELFDESTRUCT" should {

      val context: PC = fxt.context.copy(world = fxt.worldWithSelfDestructProgram)
      val call = CallResult(op = CALL, context)

      "refund the correct amount of gas" in {
        call.stateOut.gasRefund shouldBe call.stateOut.config.feeSchedule.R_selfdestruct
      }

    }

    "calling a program that executes a SSTORE that clears the storage" should {

      val context: PC = fxt.context.copy(world = fxt.worldWithSstoreWithClearProgram)
      val call = CallResult(op = CALL, context)

      "refund the correct amount of gas" in {
        call.stateOut.gasRefund shouldBe call.stateOut.config.feeSchedule.R_sclear
      }
    }

    "more gas than available is provided" should {
      def call(config: EvmConfig): CallResult = {
        val context: PC = fxt.context.copy(config = config)
        CallResult(op = DELEGATECALL, context = context, gas = UInt256.MaxValue / 2)
      }

      "go OOG before EIP-150" in {
        call(EvmConfig.HomesteadConfig).stateOut.error shouldEqual Some(OutOfGas)
      }

      "cap the provided gas after EIP-150" in {
        call(EvmConfig.PostEIP150Config).stateOut.stack.pop._1 shouldEqual UInt256.One
      }
    }
  }

  /**
    * This test should result in an OutOfGas error as (following the equations. on the DELEGATECALL opcode in the YP):
    * DELEGATECALL cost = memoryCost + C_extra + C_gascap
    * and
    * memoryCost = 0 (result written were input was)
    * C_gascap = u_s[0] = UInt256.MaxValue - C_extra + 1
    * Then
    * CALL cost = UInt256.MaxValue + 1
    * As the starting gas (startGas = C_extra - 1) is much lower than the cost this should result in an OutOfGas exception
    */
  "gas cost bigger than available gas DELEGATECALL" should {

    val memCost = 0
    val c_extra = config.feeSchedule.G_call
    val startGas = c_extra - 1
    val gas = UInt256.MaxValue - c_extra + 1 //u_s[0]
    val context: PC = fxt.context.copy(startGas = startGas)
    val call = CallResult(
      op = DELEGATECALL,
      gas = gas,
      context = context,
      outOffset = UInt256.Zero
    )
    "return an OutOfGas error" in {
      call.stateOut.error shouldBe Some(OutOfGas)
    }
  }

  "CallOpCodes" when {

    Seq(CALL, CALLCODE, DELEGATECALL).foreach { opCode =>

      s"$opCode processes returned data" should {

        "handle memory expansion properly" in {

          val inputData = ByteString(Array[Byte](1).padTo(32, 1.toByte))
          val context: PC = fxt.context.copy(world = fxt.worldWithReturnSingleByteCode)

          val table = Table[Int](
            "Out Offset",
            0,
            inputData.size / 2,
            inputData.size * 2
          )

          forAll(table) { outOffset =>

            val call = CallResult(
              op = opCode,
              outSize = inputData.size,
              outOffset = outOffset,
              context = context,
              inputData = inputData
            )

            val expectedSize = inputData.size + outOffset
            val expectedMemoryBytes = call.stateIn.memory.store(outOffset, fxt.valueToReturn.toByte).load(0, expectedSize)._1
            val resultingMemoryBytes = call.stateOut.memory.load(0, expectedSize)._1

            call.stateOut.memory.size shouldEqual expectedSize
            resultingMemoryBytes shouldEqual expectedMemoryBytes

          }
        }
      }
    }
  }
}
