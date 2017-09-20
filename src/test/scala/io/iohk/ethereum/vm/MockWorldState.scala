package io.iohk.ethereum.vm

import akka.util.ByteString
import io.iohk.ethereum.crypto.kec256
import io.iohk.ethereum.domain.{Account, Address, UInt256}

object MockWorldState {
  type PS = ProgramState[MockWorldState, MockStorage]
  type PC = ProgramContext[MockWorldState, MockStorage]
  type PR = ProgramResult[MockWorldState, MockStorage]
}

case class MockWorldState(
  accounts: Map[Address, Account] = Map(),
  codeRepo: Map[Address, ByteString] = Map(),
  storages: Map[Address, MockStorage] = Map(),
  numberOfHashes: UInt256 = 0,
  touchedAccounts: Option[Set[Address]] = None
) extends WorldStateProxy[MockWorldState, MockStorage] {

  def getAccount(address: Address): Option[Account] =
    accounts.get(address)

  override def getGuaranteedAccount(address: Address): Account =
    super.getGuaranteedAccount(address)

  def saveAccount(address: Address, account: Account): MockWorldState =
    copy(accounts = accounts + (address -> account))

  def deleteAccount(address: Address): MockWorldState =
    copy(accounts = accounts - address, codeRepo - address, storages - address)

  def getCode(address: Address): ByteString =
    codeRepo.getOrElse(address, ByteString.empty)

  def getStorage(address: Address): MockStorage =
    storages.getOrElse(address, MockStorage.Empty)

  def getBlockHash(number: UInt256): Option[UInt256] =
    if (numberOfHashes >= number && number >= 0)
      Some(UInt256(kec256(number.toString.getBytes)))
    else
      None

  def saveCode(address: Address, code: ByteString): MockWorldState =
    if (code.isEmpty)
      copy(codeRepo = codeRepo - address)
    else
      copy(codeRepo = codeRepo + (address -> code))

  def saveStorage(address: Address, storage: MockStorage): MockWorldState =
    if (storage.isEmpty)
      copy(storages = storages - address)
    else
      copy(storages = storages + (address -> storage))

  def getEmptyAccount: Account = Account.empty()

  def touchAccounts(addresses: Address*): MockWorldState =
    copy(touchedAccounts = touchedAccounts.map(oldAddresses => oldAddresses ++ addresses.toSet))

  def clearTouchedAccounts: MockWorldState =
    copy(touchedAccounts = touchedAccounts.map(_.empty))

  def noEmptyAccounts: Boolean = touchedAccounts.isDefined

  def combineTouchedAccounts(world: MockWorldState): MockWorldState = {
    val accounts = for {
      oldAccounts <- touchedAccounts
      newAccounts <- world.touchedAccounts
    } yield oldAccounts ++ newAccounts

    copy(touchedAccounts = accounts)
  }

  /**
    * Check whether an account at given address is dead,
    * according to the EIP-161 definition of 'dead' (inexistent or empty)
    */
  def isAccountDead(address: Address): Boolean =
    getAccount(address).forall(_ == Account.empty())
}
