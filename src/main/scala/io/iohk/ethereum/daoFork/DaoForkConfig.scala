package io.iohk.ethereum.daoFork

import akka.util.ByteString
import io.iohk.ethereum.domain.Address
import org.spongycastle.util.encoders.Hex

trait DaoForkConfig {

  val proDaoFork: Boolean
  val daoForkBlockNumber: BigInt
  val daoForkBlockHash: ByteString
  val blockExtraData: ByteString
  val range: Int
  val refundContract: Address
  val drainList: Seq[Address]

  private val extratadaBlockRange = daoForkBlockNumber until (daoForkBlockNumber + range)

  def isDaoForkBlock(blockNumber: BigInt): Boolean = proDaoFork && (daoForkBlockNumber == blockNumber)

  def requiresExtraData(blockNumber: BigInt): Boolean = proDaoFork && (extratadaBlockRange contains blockNumber)
}

case class DefaultDaoForkConfig(proDaoFork: Boolean, daoForkBlockNumber: BigInt, daoForkBlockHash: ByteString) extends DaoForkConfig {
  override val blockExtraData = ByteString(Hex.decode("64616f2d686172642d666f726b"))
  override lazy val range = 10
  override val refundContract = Address(Hex.decode("bf4ed7b27f1d666546e30d74d50d173d20bca754"))
  override val drainList = Seq(
    Address(Hex.decode("d4fe7bc31cedb7bfb8a345f31e668033056b2728")),
    Address(Hex.decode("b3fb0e5aba0e20e5c49d252dfd30e102b171a425")),
    Address(Hex.decode("2c19c7f9ae8b751e37aeb2d93a699722395ae18f")),
    Address(Hex.decode("ecd135fa4f61a655311e86238c92adcd779555d2")),
    Address(Hex.decode("1975bd06d486162d5dc297798dfc41edd5d160a7")),
    Address(Hex.decode("a3acf3a1e16b1d7c315e23510fdd7847b48234f6")),
    Address(Hex.decode("319f70bab6845585f412ec7724b744fec6095c85")),
    Address(Hex.decode("06706dd3f2c9abf0a21ddcc6941d9b86f0596936")),
    Address(Hex.decode("5c8536898fbb74fc7445814902fd08422eac56d0")),
    Address(Hex.decode("6966ab0d485353095148a2155858910e0965b6f9")),
    Address(Hex.decode("779543a0491a837ca36ce8c635d6154e3c4911a6")),
    Address(Hex.decode("2a5ed960395e2a49b1c758cef4aa15213cfd874c")),
    Address(Hex.decode("5c6e67ccd5849c0d29219c4f95f1a7a93b3f5dc5")),
    Address(Hex.decode("9c50426be05db97f5d64fc54bf89eff947f0a321")),
    Address(Hex.decode("200450f06520bdd6c527622a273333384d870efb")),
    Address(Hex.decode("be8539bfe837b67d1282b2b1d61c3f723966f049")),
    Address(Hex.decode("6b0c4d41ba9ab8d8cfb5d379c69a612f2ced8ecb")),
    Address(Hex.decode("f1385fb24aad0cd7432824085e42aff90886fef5")),
    Address(Hex.decode("d1ac8b1ef1b69ff51d1d401a476e7e612414f091")),
    Address(Hex.decode("8163e7fb499e90f8544ea62bbf80d21cd26d9efd")),
    Address(Hex.decode("51e0ddd9998364a2eb38588679f0d2c42653e4a6")),
    Address(Hex.decode("627a0a960c079c21c34f7612d5d230e01b4ad4c7")),
    Address(Hex.decode("f0b1aa0eb660754448a7937c022e30aa692fe0c5")),
    Address(Hex.decode("24c4d950dfd4dd1902bbed3508144a54542bba94")),
    Address(Hex.decode("9f27daea7aca0aa0446220b98d028715e3bc803d")),
    Address(Hex.decode("a5dc5acd6a7968a4554d89d65e59b7fd3bff0f90")),
    Address(Hex.decode("d9aef3a1e38a39c16b31d1ace71bca8ef58d315b")),
    Address(Hex.decode("63ed5a272de2f6d968408b4acb9024f4cc208ebf")),
    Address(Hex.decode("6f6704e5a10332af6672e50b3d9754dc460dfa4d")),
    Address(Hex.decode("77ca7b50b6cd7e2f3fa008e24ab793fd56cb15f6")),
    Address(Hex.decode("492ea3bb0f3315521c31f273e565b868fc090f17")),
    Address(Hex.decode("0ff30d6de14a8224aa97b78aea5388d1c51c1f00")),
    Address(Hex.decode("9ea779f907f0b315b364b0cfc39a0fde5b02a416")),
    Address(Hex.decode("ceaeb481747ca6c540a000c1f3641f8cef161fa7")),
    Address(Hex.decode("cc34673c6c40e791051898567a1222daf90be287")),
    Address(Hex.decode("579a80d909f346fbfb1189493f521d7f48d52238")),
    Address(Hex.decode("e308bd1ac5fda103967359b2712dd89deffb7973")),
    Address(Hex.decode("4cb31628079fb14e4bc3cd5e30c2f7489b00960c")),
    Address(Hex.decode("ac1ecab32727358dba8962a0f3b261731aad9723")),
    Address(Hex.decode("4fd6ace747f06ece9c49699c7cabc62d02211f75")),
    Address(Hex.decode("440c59b325d2997a134c2c7c60a8c61611212bad")),
    Address(Hex.decode("4486a3d68fac6967006d7a517b889fd3f98c102b")),
    Address(Hex.decode("9c15b54878ba618f494b38f0ae7443db6af648ba")),
    Address(Hex.decode("27b137a85656544b1ccb5a0f2e561a5703c6a68f")),
    Address(Hex.decode("21c7fdb9ed8d291d79ffd82eb2c4356ec0d81241")),
    Address(Hex.decode("23b75c2f6791eef49c69684db4c6c1f93bf49a50")),
    Address(Hex.decode("1ca6abd14d30affe533b24d7a21bff4c2d5e1f3b")),
    Address(Hex.decode("b9637156d330c0d605a791f1c31ba5890582fe1c")),
    Address(Hex.decode("6131c42fa982e56929107413a9d526fd99405560")),
    Address(Hex.decode("1591fc0f688c81fbeb17f5426a162a7024d430c2")),
    Address(Hex.decode("542a9515200d14b68e934e9830d91645a980dd7a")),
    Address(Hex.decode("c4bbd073882dd2add2424cf47d35213405b01324")),
    Address(Hex.decode("782495b7b3355efb2833d56ecb34dc22ad7dfcc4")),
    Address(Hex.decode("58b95c9a9d5d26825e70a82b6adb139d3fd829eb")),
    Address(Hex.decode("3ba4d81db016dc2890c81f3acec2454bff5aada5")),
    Address(Hex.decode("b52042c8ca3f8aa246fa79c3feaa3d959347c0ab")),
    Address(Hex.decode("e4ae1efdfc53b73893af49113d8694a057b9c0d1")),
    Address(Hex.decode("3c02a7bc0391e86d91b7d144e61c2c01a25a79c5")),
    Address(Hex.decode("0737a6b837f97f46ebade41b9bc3e1c509c85c53")),
    Address(Hex.decode("97f43a37f595ab5dd318fb46e7a155eae057317a")),
    Address(Hex.decode("52c5317c848ba20c7504cb2c8052abd1fde29d03")),
    Address(Hex.decode("4863226780fe7c0356454236d3b1c8792785748d")),
    Address(Hex.decode("5d2b2e6fcbe3b11d26b525e085ff818dae332479")),
    Address(Hex.decode("5f9f3392e9f62f63b8eac0beb55541fc8627f42c")),
    Address(Hex.decode("057b56736d32b86616a10f619859c6cd6f59092a")),
    Address(Hex.decode("9aa008f65de0b923a2a4f02012ad034a5e2e2192")),
    Address(Hex.decode("304a554a310c7e546dfe434669c62820b7d83490")),
    Address(Hex.decode("914d1b8b43e92723e64fd0a06f5bdb8dd9b10c79")),
    Address(Hex.decode("4deb0033bb26bc534b197e61d19e0733e5679784")),
    Address(Hex.decode("07f5c1e1bc2c93e0402f23341973a0e043f7bf8a")),
    Address(Hex.decode("35a051a0010aba705c9008d7a7eff6fb88f6ea7b")),
    Address(Hex.decode("4fa802324e929786dbda3b8820dc7834e9134a2a")),
    Address(Hex.decode("9da397b9e80755301a3b32173283a91c0ef6c87e")),
    Address(Hex.decode("8d9edb3054ce5c5774a420ac37ebae0ac02343c6")),
    Address(Hex.decode("0101f3be8ebb4bbd39a2e3b9a3639d4259832fd9")),
    Address(Hex.decode("5dc28b15dffed94048d73806ce4b7a4612a1d48f")),
    Address(Hex.decode("bcf899e6c7d9d5a215ab1e3444c86806fa854c76")),
    Address(Hex.decode("12e626b0eebfe86a56d633b9864e389b45dcb260")),
    Address(Hex.decode("a2f1ccba9395d7fcb155bba8bc92db9bafaeade7")),
    Address(Hex.decode("ec8e57756626fdc07c63ad2eafbd28d08e7b0ca5")),
    Address(Hex.decode("d164b088bd9108b60d0ca3751da4bceb207b0782")),
    Address(Hex.decode("6231b6d0d5e77fe001c2a460bd9584fee60d409b")),
    Address(Hex.decode("1cba23d343a983e9b5cfd19496b9a9701ada385f")),
    Address(Hex.decode("a82f360a8d3455c5c41366975bde739c37bfeb8a")),
    Address(Hex.decode("9fcd2deaff372a39cc679d5c5e4de7bafb0b1339")),
    Address(Hex.decode("005f5cee7a43331d5a3d3eec71305925a62f34b6")),
    Address(Hex.decode("0e0da70933f4c7849fc0d203f5d1d43b9ae4532d")),
    Address(Hex.decode("d131637d5275fd1a68a3200f4ad25c71a2a9522e")),
    Address(Hex.decode("bc07118b9ac290e4622f5e77a0853539789effbe")),
    Address(Hex.decode("47e7aa56d6bdf3f36be34619660de61275420af8")),
    Address(Hex.decode("acd87e28b0c9d1254e868b81cba4cc20d9a32225")),
    Address(Hex.decode("adf80daec7ba8dcf15392f1ac611fff65d94f880")),
    Address(Hex.decode("5524c55fb03cf21f549444ccbecb664d0acad706")),
    Address(Hex.decode("40b803a9abce16f50f36a77ba41180eb90023925")),
    Address(Hex.decode("fe24cdd8648121a43a7c86d289be4dd2951ed49f")),
    Address(Hex.decode("17802f43a0137c506ba92291391a8a8f207f487d")),
    Address(Hex.decode("253488078a4edf4d6f42f113d1e62836a942cf1a")),
    Address(Hex.decode("86af3e9626fce1957c82e88cbf04ddf3a2ed7915")),
    Address(Hex.decode("b136707642a4ea12fb4bae820f03d2562ebff487")),
    Address(Hex.decode("dbe9b615a3ae8709af8b93336ce9b477e4ac0940")),
    Address(Hex.decode("f14c14075d6c4ed84b86798af0956deef67365b5")),
    Address(Hex.decode("ca544e5c4687d109611d0f8f928b53a25af72448")),
    Address(Hex.decode("aeeb8ff27288bdabc0fa5ebb731b6f409507516c")),
    Address(Hex.decode("cbb9d3703e651b0d496cdefb8b92c25aeb2171f7")),
    Address(Hex.decode("6d87578288b6cb5549d5076a207456a1f6a63dc0")),
    Address(Hex.decode("b2c6f0dfbb716ac562e2d85d6cb2f8d5ee87603e")),
    Address(Hex.decode("accc230e8a6e5be9160b8cdf2864dd2a001c28b6")),
    Address(Hex.decode("2b3455ec7fedf16e646268bf88846bd7a2319bb2")),
    Address(Hex.decode("4613f3bca5c44ea06337a9e439fbc6d42e501d0a")),
    Address(Hex.decode("d343b217de44030afaa275f54d31a9317c7f441e")),
    Address(Hex.decode("84ef4b2357079cd7a7c69fd7a37cd0609a679106")),
    Address(Hex.decode("da2fef9e4a3230988ff17df2165440f37e8b1708")),
    Address(Hex.decode("f4c64518ea10f995918a454158c6b61407ea345c")),
    Address(Hex.decode("7602b46df5390e432ef1c307d4f2c9ff6d65cc97")),
    Address(Hex.decode("bb9bc244d798123fde783fcc1c72d3bb8c189413")),
    Address(Hex.decode("807640a13483f8ac783c557fcdf27be11ea4ac7a"))
  )
}
