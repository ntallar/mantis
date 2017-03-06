package io.iohk.ethereum.vm

import akka.util.ByteString
import io.iohk.ethereum.domain.{Account, Address}

object MockWorldState {
  type PS = ProgramState[MockWorldState, MockStorage]
  type PC = ProgramContext[MockWorldState, MockStorage]
  type PR = ProgramResult[MockWorldState, MockStorage]
}

case class MockWorldState(
  accounts: Map[Address, Account] = Map(),
  codeRepo: Map[Address, ByteString] = Map(),
  storages: Map[Address, MockStorage] = Map()
) extends WorldStateProxy[MockWorldState, MockStorage] {

  def getAccount(address: Address): Option[Account] =
    accounts.get(address)

  def saveAccount(address: Address, account: Account): MockWorldState =
    copy(accounts = accounts + (address -> account))

  def getCode(address: Address): ByteString =
    codeRepo.getOrElse(address, ByteString.empty)

  def getStorage(address: Address): MockStorage =
    storages.getOrElse(address, MockStorage.Empty)

  def saveCode(address: Address, code: ByteString): MockWorldState =
    copy(codeRepo = codeRepo + (address -> code))

  def saveStorage(address: Address, storage: MockStorage): MockWorldState =
    copy(storages = storages + (address -> storage))
}
