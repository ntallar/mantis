package io.iohk.ethereum

import scala.concurrent.Future

package object jsonrpc {
  type ServiceResponse[T] = Future[Either[JsonRpcError, T]]
}
