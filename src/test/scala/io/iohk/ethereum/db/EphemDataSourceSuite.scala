package io.iohk.ethereum.db

import org.scalatest.FunSuite
import org.scalatest.prop.PropertyChecks
import io.iohk.ethereum.ObjectGenerators

import org.scalacheck.Gen

import scala.util.Random

class EphemDataSourceSuite extends FunSuite
  with PropertyChecks
  with ObjectGenerators {

  val KeySize: Int = 32
  val KeyNumberLimit: Int = 40
  def putMultiple(dataSource: DataSource, toInsert: Seq[(Array[Byte], Array[Byte])]): DataSource = {
    toInsert.foldLeft(dataSource){ case (recDB, keyValuePair) =>
      recDB.update(Seq(), Seq(keyValuePair))
    }
  }

  def removeMultiple(dataSource: DataSource, toDelete: Seq[Array[Byte]]): DataSource = {
    toDelete.foldLeft(dataSource){ case (recDB, key) =>
      recDB.update(Seq(key), Seq())
    }
  }

  test("EphemDataSource insert"){
    forAll(seqByteArrayOfNItemsGen(KeySize)) { unFilteredKeyList: Seq[Array[Byte]] =>
      val keyList = unFilteredKeyList.filter(_.length == KeySize)
      val db = putMultiple(dataSource = EphemDataSource(), toInsert = keyList.zip(keyList))
      keyList.foreach { key =>
        val obtained = db.get(key)
        assert(obtained.isDefined)
        assert(obtained.get sameElements key)
      }
    }
  }

  test("EphemDataSource delete"){
    forAll(seqByteArrayOfNItemsGen(KeySize)) { unFilteredKeyList: Seq[Array[Byte]] =>
      val keyList = unFilteredKeyList.filter(_.length == KeySize)
      val (keysToDelete, keyValueLeft) = Random.shuffle(keyList).splitAt(Gen.choose(0, keyList.size).sample.get)

      val dbAfterInsert = putMultiple(dataSource = EphemDataSource(), toInsert = keyList.zip(keyList))
      val db = removeMultiple(dataSource = dbAfterInsert, toDelete = keysToDelete)
      keyValueLeft.foreach { key =>
        val obtained = db.get(key)
        assert(obtained.isDefined)
        assert(obtained.get sameElements key)
      }
      keysToDelete.foreach { key => assert(db.get(key).isEmpty) }
    }
  }
}
