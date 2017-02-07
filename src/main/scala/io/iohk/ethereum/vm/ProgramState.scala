package io.iohk.ethereum.vm

import akka.util.ByteString

object ProgramState {
  def apply(context: ProgramContext): ProgramState =
    ProgramState(context = context, gas = context.gas, storage = context.storage)
}

/**
  * Intermediate state updated with execution of each opcode in the program
  *
  * @param context the context which initiates the program
  * @param stack current stack
  * @param memory current memory
  * @param storage current storage
  * @param pc program counter - an index of the opcode in the program to be executed
  * @param returnData data to be returned from the program execution
  * @param halted a flag to indicate program termination
  * @param error indicates whether the program terminated abnormally
  */
case class ProgramState(
  context: ProgramContext,
  gas: BigInt,
  storage: Storage,
  stack: Stack = Stack.empty(),
  memory: Memory = Memory.empty,
  pc: Int = 0,
  returnData: ByteString = ByteString.empty,
  gasRefund: BigInt = 0,
  halted: Boolean = false,
  error: Option[ProgramError] = None
) {

  def program: Program = context.program

  def spendGas(gasUsed: BigInt): ProgramState =
    copy(gas = gas - gasUsed)

  def step(i: Int = 1): ProgramState =
    copy(pc = pc + i)

  def goto(i: Int): ProgramState =
    copy(pc = i)

  def withStack(stack: Stack): ProgramState =
    copy(stack = stack)

  def withMemory(memory: Memory): ProgramState =
    copy(memory = memory)

  def withStorage(storage: Storage): ProgramState =
    copy(storage = storage)

  def withError(error: ProgramError): ProgramState =
    copy(error = Some(error), halted = true)

  def withReturnData(data: ByteString): ProgramState =
    copy(returnData = data)

  def addGasRefund(amount: BigInt): ProgramState =
    copy(gasRefund = gasRefund + amount)

  def halt: ProgramState =
    copy(halted = true)
}
