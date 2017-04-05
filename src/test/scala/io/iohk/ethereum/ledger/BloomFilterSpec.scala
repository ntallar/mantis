package io.iohk.ethereum.ledger

import akka.util.ByteString
import io.iohk.ethereum.domain.{Address, Receipt, TxLogEntry}
import org.scalatest.{FlatSpec, Matchers}
import org.spongycastle.util.encoders.Hex

class BloomFilterSpec extends FlatSpec with Matchers {

  it should "properly create the bloom filter for without logs" in {
    val obtained = BloomFilter.create(receiptWithoutLogs.logs.toSet)
    obtained shouldBe receiptWithoutLogs.logsBloomFilter
  }

  it should "properly create the bloom filter for with one log entry with one topic" in {
    val obtained = BloomFilter.create(receiptOneLogOneTopic.logs.toSet)
    obtained shouldBe receiptOneLogOneTopic.logsBloomFilter
  }

  it should "properly create the bloom filter for with many logs" in {
    val obtained = BloomFilter.create(receiptWithManyLogs.logs.toSet)
    obtained shouldBe receiptWithManyLogs.logsBloomFilter
  }

  //From tx 0xe9e91f1ee4b56c0df2e9f06c2b8c27c6076195a88a7b8537ba8313d80e6f124e
  val receiptWithoutLogs = Receipt(
    postTransactionStateHash = ByteString(Hex.decode("fa28ef92787192b577a8628e520b546ab58b72102572e08191ddecd51d0851e5")),
    cumulativeGasUsed = 50244,
    logsBloomFilter = ByteString(Hex.decode("00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000")),
    logs = Seq[TxLogEntry]()
  )

  //From tx 0x864f61c4fbf1952bfb55d4617e4bde3a0338322b37c832119ed1e8717b502530
  val receiptOneLogOneTopic = Receipt(
    postTransactionStateHash = ByteString(Hex.decode("d74e64c4beb7627811f456baedfe05d26364bef11136b922b8c44769ad1e6ac6")),
    cumulativeGasUsed = BigInt("1674016"),
    logsBloomFilter = ByteString(Hex.decode("00000800000000000000000000000000000000000000000000000000000000000000000000080000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000080000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000100000000000000010000000000000000000000000200000000000000000000000000000000000000000000000000000000000000000000000000000000000000")),
    logs = Seq[TxLogEntry](
      TxLogEntry(
        loggerAddress = Address(Hex.decode("e414716f017b5c1457bf98e985bccb135dff81f2")),
        logTopics = Seq(ByteString(Hex.decode("962cd36cf694aa154c5d3a551f19c98f356d906e96828eeb616e16fae6415738"))),
        data = ByteString(Hex.decode("000000000000000000000000000000000000000000000000000000000000000f0000000000000000000000000000000000000000000000000000000000000009"))
      )
    )
  )

  //From tx 0x0bb157f90f918fad96d6954d9e620a4aa490da57a66303a6b41e855fd0f19a59
  val receiptWithManyLogs = Receipt(
    postTransactionStateHash = ByteString(Hex.decode("fe375456a6f22f90f2f55bd57e72c7c663ef7733d5795f091a06496ad5895c67")),
    cumulativeGasUsed = 319269,
    logsBloomFilter = ByteString(Hex.decode("000000000000000000000000000000000000000000000000000000000000000000000000000000000400000000000000000000020000000000000000000000000000000000000000000000020000000000000000000000000001000000000000004000000200000000000000000008020000020000000000000000001000000000000000000000004000040000000000000000000000000000000000000000000000000000000004001000000000000000000000000004080008000000000100000000000000000000000000000000000800000000000000000000000000200000000000001000000000000a0000000000000000000000000000000000000000")),
    logs = Seq[TxLogEntry](
      TxLogEntry(
        loggerAddress = Address(Hex.decode("276c5c6ca8507ed7bac085fc9b9521f4f54b58d3")),
        logTopics = Seq(
          ByteString(Hex.decode("ea0f544916910bb1ff33390cbe54a3f5d36d298328578399311cde3c9a750686")),
          ByteString(Hex.decode("0000000000000000000000000000000000000000000000000000000000000000")),
          ByteString(Hex.decode("000000000000000000000000939292f2b41b74ccb7261a452de556ba2c45db86")),
          ByteString(Hex.decode("0000000000000000000000000000000000000000000000000000000000000000"))
        ),
        data = ByteString(Hex.decode("00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001"))
      ),
      TxLogEntry(
        loggerAddress = Address(Hex.decode("276c5c6ca8507ed7bac085fc9b9521f4f54b58d3")),
        logTopics = Seq(
          ByteString(Hex.decode("ea0f544916910bb1ff33390cbe54a3f5d36d298328578399311cde3c9a750686")),
          ByteString(Hex.decode("0000000000000000000000000000000000000000000000000000000000000000")),
          ByteString(Hex.decode("0000000000000000000000000000000000000000000000000000000000000000")),
          ByteString(Hex.decode("000000000000000000000000490c0dd13bfea5865ca985297cf2bed3f77beb5d"))
        ),
        data = ByteString(Hex.decode("00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001"))
      ),
      TxLogEntry(
        loggerAddress = Address(Hex.decode("1158c3c9a70e85d8358972810ed984c8e6ffcf0f")),
        logTopics = Seq(ByteString(Hex.decode("009f837f1feddc3de305fab200310a83d2871686078dab617c02b44360c9e236"))),
        data = ByteString(Hex.decode("000000000000000000000000939292f2b41b74ccb7261a452de556ba2c45db86000000000000000000000000490c0dd13bfea5865ca985297cf2bed3f77beb5d0000000000000000000000000000000000000000000000000000000056bfb58000000000000000000000000000000000000000000000000000000000000000010000000000000000000000000000000000000000000000000000000000000000"))
      ),
      TxLogEntry(
        loggerAddress = Address(Hex.decode("276c5c6ca8507ed7bac085fc9b9521f4f54b58d3")),
        logTopics = Seq(
          ByteString(Hex.decode("ea0f544916910bb1ff33390cbe54a3f5d36d298328578399311cde3c9a750686")),
          ByteString(Hex.decode("0000000000000000000000000000000000000000000000000000000000000000")),
          ByteString(Hex.decode("0000000000000000000000000000000000000000000000000000000000000000")),
          ByteString(Hex.decode("00000000000000000000000048175da4c20313bcb6b62d74937d3ff985885701"))
        ),
        data = ByteString(Hex.decode("00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"))
      )
    )
  )
}
