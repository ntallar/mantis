package io.iohk.ethereum.vm

import akka.util.ByteString
import io.iohk.ethereum.domain.{Address, Transaction}

/**
 * Represenation of the result of execution of a contract
 *
 * @param returnData bytes returned by the executed contract (set by [[RETURN]] opcode)
 * @param gasRemaining amount of gas remaining after execution
 * @param gasUsed amount of gas used in execution
 * @param world represents changes to the world state
 * @param addressesToDelete list of addresses of accounts scheduled to be deleted
 * @param error defined when the program terminated abnormally
 */
case class ProgramResult[W <: WorldStateProxy[W, S], S <: Storage[S]](
  returnData: ByteString,
  gasRemaining: BigInt,
  gasUsed: BigInt,
  world: W,
  addressesToDelete: Seq[Address] = Seq(),
  error: Option[ProgramError])
