package io.iohk.ethereum.vm

import io.iohk.ethereum.utils.EitherExtensions._
import scala.annotation.tailrec

object VM {

  def run(invoke: ProgramInvoke): ProgramResult = {
    val finalState = run(ProgramState(invoke))
    ProgramResult(finalState.returnData, finalState.storage, finalState.error)
  }

  @tailrec
  private def run(state: ProgramState): ProgramState = {
    getOpCode(state) match {
      case Right(opcode) =>
        val newState = opcode.execute(state)
        if (newState.halted)
          newState
        else
          run(newState)

      case Left(error) =>
        state.withError(error).halt
    }
  }

  private def getOpCode(state: ProgramState): Either[ProgramError, OpCode] = {
    val byte = state.program.getByte(state.pc)
    OpCode.byteToOpCode.get(byte) match {
      case Some(opcode) => opcode.asRight
      case None => InvalidOpCode(byte).asLeft
    }

  }
}
