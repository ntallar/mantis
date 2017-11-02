package io.iohk.ethereum.keystore

import java.io.File

import akka.util.ByteString
import io.iohk.ethereum.domain.Address
import io.iohk.ethereum.keystore.KeyStore.{DecryptionFailed, IOError, KeyNotFound}
import io.iohk.ethereum.nodebuilder.SecureRandomBuilder
import io.iohk.ethereum.utils.Config
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}
import org.spongycastle.util.encoders.Hex
import org.apache.commons.io.FileUtils

class KeyStoreImplSpec extends FlatSpec with Matchers with BeforeAndAfter with SecureRandomBuilder {

  before(clearKeyStore())

  "KeyStoreImpl" should "import and list accounts" in new TestSetup {
    val listBeforeImport = keyStore.listAccounts().right.get
    listBeforeImport shouldEqual Nil

    // We sleep between imports so that dates of keyfiles' names are different
    val res1 = keyStore.importPrivateKey(key1, "aaa").right.get
    Thread.sleep(1005)
    val res2 = keyStore.importPrivateKey(key2, "bbb").right.get
    Thread.sleep(1005)
    val res3 = keyStore.importPrivateKey(key3, "ccc").right.get

    res1 shouldEqual addr1
    res2 shouldEqual addr2
    res3 shouldEqual addr3

    val listAfterImport = keyStore.listAccounts().right.get
    // result should be ordered by creation date
    listAfterImport shouldEqual List(addr1, addr2, addr3)
  }

  it should "fail to import a key twice" in new TestSetup {
    val resAfterFirstImport = keyStore.importPrivateKey(key1, "aaa")
    val resAfterDupImport = keyStore.importPrivateKey(key1, "aaa")

    resAfterFirstImport shouldEqual Right(addr1)
    resAfterDupImport shouldBe Left(KeyStore.DuplicateKeySaved)

    //Only the first import succeeded
    val listAfterImport = keyStore.listAccounts().right.get
    listAfterImport.toSet shouldEqual Set(addr1)
    listAfterImport.length shouldEqual 1
  }

  it should "create new accounts" in new TestSetup {
    val newAddr1 = keyStore.newAccount("aaa").right.get
    val newAddr2 = keyStore.newAccount("bbb").right.get

    val listOfNewAccounts = keyStore.listAccounts().right.get
    listOfNewAccounts.toSet shouldEqual Set(newAddr1, newAddr2)
    listOfNewAccounts.length shouldEqual 2
  }

  it should "return an error when the keystore dir cannot be initialized" in new TestSetup {
    intercept[IllegalArgumentException] {
      new KeyStoreImpl("/root/keystore", secureRandom)
    }
  }

  it should "return an error when the keystore dir cannot be read or written" in new TestSetup {
    clearKeyStore()

    val key = ByteString(Hex.decode("7a44789ed3cd85861c0bbf9693c7e1de1862dd4396c390147ecf1275099c6e6f"))
    val res1 = keyStore.importPrivateKey(key, "aaa")
    res1 should matchPattern { case Left(IOError(_)) => }

    val res2 = keyStore.newAccount("aaa")
    res2 should matchPattern { case Left(IOError(_)) => }

    val res3 = keyStore.listAccounts()
    res3 should matchPattern { case Left(IOError(_)) => }

    val res4 = keyStore.deleteWallet(Address(key))
    res4 should matchPattern { case Left(IOError(_)) => }
  }

  it should "unlock an account provided a correct passphrase" in new TestSetup {
    val passphrase = "aaa"
    keyStore.importPrivateKey(key1, passphrase)
    val wallet = keyStore.unlockAccount(addr1, passphrase).right.get
    wallet shouldEqual Wallet(addr1, key1)
  }

  it should "return an error when unlocking an account with a wrong passphrase" in new TestSetup {
    keyStore.importPrivateKey(key1, "aaa")
    val res = keyStore.unlockAccount(addr1, "bbb")
    res shouldEqual Left(DecryptionFailed)
  }

  it should "return an error when trying to unlock an unknown account" in new TestSetup {
    val res = keyStore.unlockAccount(addr1, "bbb")
    res shouldEqual Left(KeyNotFound)
  }

  it should "return an error deleting not existing wallet" in new TestSetup {
    val res = keyStore.deleteWallet(addr1)
    res shouldEqual Left(KeyNotFound)
  }

  it should "delete existing wallet " in new TestSetup {
    val newAddr1 = keyStore.newAccount("aaa").right.get
    val listOfNewAccounts = keyStore.listAccounts().right.get
    listOfNewAccounts.toSet shouldEqual Set(newAddr1)


    val res = keyStore.deleteWallet(newAddr1).right.get
    res shouldBe true

    val listOfNewAccountsAfterDelete = keyStore.listAccounts().right.get
    listOfNewAccountsAfterDelete.toSet shouldEqual Set.empty
  }

  it should "change passphrase of an existing wallet" in new TestSetup {
    val oldPassphrase = "weakpass"
    val newPassphrase = "very5tr0ng&l0ngp4s5phr4s3"

    keyStore.importPrivateKey(key1, oldPassphrase)
    keyStore.changePassphrase(addr1, oldPassphrase, newPassphrase) shouldEqual Right(())

    keyStore.unlockAccount(addr1, newPassphrase) shouldEqual Right(Wallet(addr1, key1))
  }

  it should "return an error when changing passphrase of an non-existent wallet" in new TestSetup {
    keyStore.changePassphrase(addr1, "oldpass", "newpass") shouldEqual Left(KeyNotFound)
  }

  it should "return an error when changing passphrase and provided with invalid old passphrase" in new TestSetup {
    keyStore.importPrivateKey(key1, "oldpass")
    keyStore.changePassphrase(addr1, "wrongpass", "newpass") shouldEqual Left(DecryptionFailed)
  }


  trait TestSetup {
    val keyStore = new KeyStoreImpl(Config.keyStoreDir, secureRandom)

    val key1 = ByteString(Hex.decode("7a44789ed3cd85861c0bbf9693c7e1de1862dd4396c390147ecf1275099c6e6f"))
    val addr1 = Address(Hex.decode("aa6826f00d01fe4085f0c3dd12778e206ce4e2ac"))
    val key2 = ByteString(Hex.decode("ee9fb343c34856f3e64f6f0b5e2abd1b298aaa76d0ffc667d00eac4582cb69ca"))
    val addr2 = Address(Hex.decode("f1c8084f32b8ef2cee7099446d9a6a185d732468"))
    val key3 = ByteString(Hex.decode("ed341f91661a05c249c36b8c9f6d3b796aa9f629f07ddc73b04b9ffc98641a50"))
    val addr3 = Address(Hex.decode("d2ecb1332a233d314c30fe3b53f44541b7a07a9e"))
  }

  def clearKeyStore(): Unit = {
    FileUtils.deleteDirectory(new File(Config.keyStoreDir))
  }
}
