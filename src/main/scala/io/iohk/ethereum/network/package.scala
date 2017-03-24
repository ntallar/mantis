package io.iohk.ethereum

import java.io.{File, PrintWriter}

import io.iohk.ethereum.crypto._
import io.iohk.ethereum.utils.AsymmetricCipherKeyPairSerializable
import org.spongycastle.crypto.AsymmetricCipherKeyPair
import org.spongycastle.crypto.params.ECPublicKeyParameters
import org.spongycastle.math.ec.ECPoint
import org.spongycastle.util.encoders.Hex

import scala.io.Source

package object network {

  implicit class ECPublicKeyParametersNodeId(val pubKey: ECPublicKeyParameters) extends AnyVal {
    def toNodeId: Array[Byte] =
      pubKey.asInstanceOf[ECPublicKeyParameters].getQ
      .getEncoded(false)
      .drop(1) // drop type info
  }

  def publicKeyFromNodeId(nodeId: String): ECPoint = {
    val bytes = Array(4.toByte) ++ // uncompressed
      Hex.decode(nodeId)
    curve.getCurve.decodePoint(bytes)
  }

  def loadAsymmetricCipherKeyPair(filePath: String): AsymmetricCipherKeyPair = {
    val file = new File(filePath)
    if(!file.exists()){
      val keysValuePair = generateKeyPair()

      //Write keys to file
      val (pub, priv) = AsymmetricCipherKeyPairSerializable.toHexStrings(keysValuePair)
      file.getParentFile.mkdirs()
      val writer = new PrintWriter(filePath)
      try {
        writer.write(pub ++ System.getProperty("line.separator") ++ priv)
      } finally {
        writer.close()
      }

      keysValuePair
    } else {
      val List(pub, priv) = Source.fromFile(filePath).getLines().toList
      AsymmetricCipherKeyPairSerializable.fromHexStrings(pub, priv)
    }
  }

}
