package io.iohk.ethereum.consensus

import java.math.BigInteger
import java.nio.{ByteBuffer, ByteOrder}
import java.util

import akka.util.ByteString
import io.iohk.ethereum.crypto.{kec256, kec512}
import io.iohk.ethereum.domain.BlockHeader
import io.iohk.ethereum.utils.ByteUtils
import org.spongycastle.util.BigIntegers
import org.spongycastle.util.encoders.Hex

import scala.annotation.tailrec

/** REVISION 23 of https://github.com/ethereum/wiki/wiki/Ethash */
object Ethash {

  // scalastyle:off magic.number

  // value used in Fowler–Noll–Vo hash function
  val FNV_PRIME: Int = 0x01000193

  // bytes in word
  val WORD_BYTES: Int = 4

  // bytes in dataset at genesis
  val DATASET_BYTES_INIT: Long = BigInt(2).pow(30).toLong

  // dataset growth per epoch
  val DATASET_BYTES_GROWTH: Long = BigInt(2).pow(23).toLong

  // bytes in cache at genesis
  val CACHE_BYTES_INIT: Long = BigInt(2).pow(24).toLong

  // cache growth per epoch
  val CACHE_BYTES_GROWTH: Long = BigInt(2).pow(17).toLong

  // blocks per epoch
  val EPOCH_LENGTH: Int = 30000

  // width of mix
  val MIX_BYTES: Int = 128

  // hash length in bytes
  val HASH_BYTES: Int = 64

  // number of parents of each dataset element
  val DATASET_PARENTS: Int = 256

  // number of rounds in cache production
  val CACHE_ROUNDS: Int = 3

  // number of accesses in hashimoto loop
  val ACCESSES: Int = 64

  // scalastyle:on magic.number

  def seed(blockNumber: Long): ByteString = {
    (BigInt(0) until epoch(blockNumber))
      .foldLeft(ByteString(Hex.decode("00" * 32))) { case (b, _) => kec256(b) }
  }

  def epoch(blockNumber: Long): Long = blockNumber / EPOCH_LENGTH

  def cacheSize(blockNumber: Long): Long = {
    val sz = (CACHE_BYTES_INIT + CACHE_BYTES_GROWTH * epoch(blockNumber)) - HASH_BYTES
    highestPrimeBelow(sz, HASH_BYTES)
  }

  def dagSize(blockNumber: Long): Long = {
    val sz = DATASET_BYTES_INIT + DATASET_BYTES_GROWTH * epoch(blockNumber) - MIX_BYTES
    highestPrimeBelow(sz, MIX_BYTES)
  }

  @tailrec
  private def highestPrimeBelow(n: Long, len: Long): Long = {
    if (isPrime(n / len)) n
    else highestPrimeBelow(n - 2 * len, len)
  }

  private def isPrime(n: BigInt): Boolean =
    n > 1 && !((2 to Math.floor(Math.sqrt(n.toLong)).toLong) exists (n % _ == 0))

  def makeCache(blockNumber: Long): Array[Int] = {
    /* watch out, arrays are mutable here */

    val n = (cacheSize(blockNumber) / HASH_BYTES).toInt
    val s = seed(blockNumber).toArray[Byte]

    val bytes = new Array[Array[Byte]](n)
    bytes(0) = kec512(s)

    (1 until n).foreach { i =>
      bytes(i) = kec512(bytes(i - 1))
    }

    (0 until CACHE_ROUNDS).foreach { _ =>
      (0 until n).foreach { i =>
        val v = remainderUnsigned(getIntFromWord(bytes(i)), n)
        bytes(i) = kec512(ByteUtils.xor(bytes((i - 1 + n) % n), bytes(v)))
      }
    }

    val res = new Array[Int](bytes.length * bytes(0).length / 4)
    bytes.indices.foreach { i =>
      val ints = bytesToInts(bytes(i))
      System.arraycopy(ints, 0, res, i * ints.length, ints.length)
    }
    res
  }

  def hashimotoLight(hashWithoutNonce: Array[Byte], nonce: Array[Byte], fullSize: Long, cache: Array[Int]): ProofOfWork = {
    hashimoto(hashWithoutNonce, nonce, fullSize, (calcDatasetItem _).curried(cache))
  }

  def hashimoto(hashWithoutNonce: Array[Byte], nonce: Array[Byte], fullSize: Long, datasetLookup: Int => Array[Int]): ProofOfWork = {
    /* watch out, arrays are mutable here */

    val w = MIX_BYTES / WORD_BYTES
    val mixHashes = MIX_BYTES / HASH_BYTES
    val s = bytesToInts(kec512(hashWithoutNonce ++ nonce.reverse))
    val mix = new Array[Int](MIX_BYTES / 4)

    (0 until mixHashes).foreach { i =>
      System.arraycopy(s, 0, mix, i * s.length, s.length)
    }

    val numFullPages = (fullSize / MIX_BYTES).toInt

    (0 until ACCESSES).foreach { i =>
      val p = remainderUnsigned(fnv(i ^ s(0), mix(i % w)), numFullPages)
      val newData = new Array[Int](mix.length)
      val off = p * mixHashes

      (0 until mixHashes).foreach { j =>
        val lookup = datasetLookup(off + j)
        System.arraycopy(lookup, 0, newData, j * lookup.length, lookup.length)
      }

      mix.indices.foreach { k =>
        mix(k) = fnv(mix(k), newData(k))
      }
    }

    val cmix = new Array[Int](mix.length / 4)
    (mix.indices by 4).foreach { i =>
      val fnv1 = fnv(mix(i), mix(i + 1))
      val fnv2 = fnv(fnv1, mix(i + 2))
      val fnv3 = fnv(fnv2, mix(i + 3))
      cmix(i >> 2) = fnv3
    }

    ProofOfWork(
      mixHash = ByteString(intsToBytes(cmix)),
      difficultyBoundary = ByteString(kec256(intsToBytes(s) ++ intsToBytes(cmix))))
  }

  def calcDatasetItem(cache: Array[Int], index: Int): Array[Int] = {
    /* watch out, arrays are mutable here */

    val r = HASH_BYTES / WORD_BYTES
    val n = cache.length / r
    val initialMix = util.Arrays.copyOfRange(cache, index % n * r, (index % n + 1) * r)

    initialMix(0) = index ^ initialMix(0)
    val mix = bytesToInts(kec512(intsToBytes(initialMix)))
    val dsParents = DATASET_PARENTS
    val mixLen = mix.length

    (0 until dsParents).foreach { j =>
      val cacheIdx = remainderUnsigned(fnv(index ^ j, mix(j % r)), n)
      val off = cacheIdx * r
      (0 until mixLen).foreach { k =>
        mix(k) = fnv(mix(k), cache(off + k))
      }
    }
    bytesToInts(kec512(intsToBytes(mix)))
  }

  private def remainderUnsigned(dividend: Int, divisor: Int): Int = {
    if (divisor >= 0) {
      if (dividend >= 0) {
        dividend % divisor
      } else {
        // The implementation is a Java port of algorithm described in the book
        // "Hacker's Delight" (section "Unsigned short division from signed division").
        val q = ((dividend >>> 1) / divisor) << 1
        val w = dividend - q * divisor
        if (w < 0 || w >= divisor) w - divisor else w
      }
    } else if (dividend >= 0 || dividend < divisor) dividend else dividend - divisor
  }

  private def fnv(v1: Int, v2: Int): Int = {
    (v1 * FNV_PRIME) ^ v2
  }

  private def bytesToInts(bytes: Array[Byte]): Array[Int] =
    bytes.grouped(4).map(getIntFromWord).toArray

  private def intsToBytes(input: Array[Int]): Array[Byte] = {
    input.flatMap { i =>
      Array(
        (i & 0xFF).toByte,
        ((i >> 8) & 0xFF).toByte,
        ((i >> 16) & 0xFF).toByte,
        ((i >> 24) & 0xFF).toByte)
    }
  }

  private def getIntFromWord(arr: Array[Byte]): Int = {
    ByteBuffer.wrap(arr, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt
  }

  def checkDifficulty(blockHeader: BlockHeader, proofOfWork: ProofOfWork): Boolean = {
    @tailrec
    def compare(a1: Array[Byte], a2: Array[Byte]): Int = {
      if (a1.length > a2.length) 1
      else if (a1.length < a2.length) -1
      else {
        if (a1.length == 0 && a2.length == 0) 0
        else if ((a1.head & 0xFF) > (a2.head & 0xFF)) 1
        else if ((a1.head & 0xFF) < (a2.head & 0xFF)) -1
        else compare(a1.tail, a2.tail)
      }
    }

    val headerDifficultyAsByteArray: Array[Byte] =
      BigIntegers.asUnsignedByteArray(32, BigInteger.ONE.shiftLeft(256).divide(BigInteger.valueOf(blockHeader.difficulty.toLong)))

    compare(headerDifficultyAsByteArray, proofOfWork.difficultyBoundary.toArray[Byte]) >= 0
  }

  case class ProofOfWork(mixHash: ByteString, difficultyBoundary: ByteString)
}
