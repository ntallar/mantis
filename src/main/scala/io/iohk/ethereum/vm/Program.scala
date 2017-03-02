package io.iohk.ethereum.vm

import akka.util.ByteString

import scala.annotation.tailrec

/**
  * Holds a program's code and provides utilities for accessing it (defaulting to zeroes when out of scope)
  *
  * @param code the EVM bytecode as bytes
  */
case class Program(code: ByteString) {

  def getByte(pc: Int): Byte =
    code.lift(pc).getOrElse(0)

  def getBytes(from: Int, size: Int): ByteString =
    code.slice(from, from + size).padTo(size, 0.toByte)

  lazy val validJumpDestinations: Seq[Int] = validJumpDestinationsAfterPosition(0)

  /**
    * Returns the valid jump destinations of the program after a given position
    * See section 9.4.3 in Yellow Paper for more detail.
    *
    * @param pos from where to start searching for valid jump destinations in the code.
    * @param accum with the previously obtained valid jump destinations.
    */
  @tailrec
  private def validJumpDestinationsAfterPosition(pos: Int, accum: Seq[Int] = Seq()): Seq[Int] = {
    if(pos >= code.length) accum
    else {
      val opCode = VM.getOpCode(this, pos)
      opCode match {
        case Right(pushOp: PushOp) => validJumpDestinationsAfterPosition(pos + pushOp.code - PUSH1.code + 2, accum)
        case Right(JUMPDEST) => validJumpDestinationsAfterPosition(pos + 1, pos +: accum)
        case _ => validJumpDestinationsAfterPosition(pos + 1, accum)
      }
    }
  }
}
