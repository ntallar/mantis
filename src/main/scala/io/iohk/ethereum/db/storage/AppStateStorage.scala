package io.iohk.ethereum.db.storage

import io.iohk.ethereum.db.dataSource.DataSource
import io.iohk.ethereum.db.storage.AppStateStorage._

/**
  * This class is used to store app state variables
  *   Key: see AppStateStorage.Keys
  *   Value: stored string value
  */
class AppStateStorage(val dataSource: DataSource) extends KeyValueStorage[Key, Value]{
  type T = AppStateStorage

  val namespace: IndexedSeq[Byte] = Namespaces.AppStateNamespace
  def keySerializer: Key => IndexedSeq[Byte] = _.name.getBytes
  def valueSerializer: String => IndexedSeq[Byte] = _.getBytes
  def valueDeserializer: IndexedSeq[Byte] => String = (valueBytes: IndexedSeq[Byte]) => new String(valueBytes.toArray)

  protected def apply(dataSource: DataSource): AppStateStorage = new AppStateStorage(dataSource)

  def getBestBlockNumber(): BigInt =
    BigInt(get(Keys.BestBlockNumber).getOrElse("0"))

  def putBestBlockNumber(bestBlockNumber: BigInt): AppStateStorage =
    put(Keys.BestBlockNumber, bestBlockNumber.toString)

  def getFastSyncTargetBlockNumber(): Option[BigInt] =
    get(Keys.FastSyncTargetBlock).map(BigInt.apply)

  def putFastSyncTargetBlockNumber(fastSyncTargetBlockNumber: BigInt): AppStateStorage =
    put(Keys.FastSyncTargetBlock, fastSyncTargetBlockNumber.toString)
}

object AppStateStorage {
  type Value = String

  case class Key private (name: String)

  object Keys {
    val BestBlockNumber = Key("BestBlockNumber")
    val FastSyncTargetBlock = Key("FastSyncTargetBlock")
  }
}
