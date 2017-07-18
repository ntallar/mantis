package io.iohk.ethereum.db.components

import io.iohk.ethereum.db.storage._
import io.iohk.ethereum.db.storage.pruning.PruningMode
import io.iohk.ethereum.domain.BlockchainStorages

trait StoragesComponent {

  val pruningMode: PruningMode

  val storages: Storages

  trait Storages extends BlockchainStorages {

    val blockHeadersStorage: BlockHeadersStorage

    val blockBodiesStorage: BlockBodiesStorage

    val blockNumberMappingStorage: BlockNumberMappingStorage

    val receiptStorage: ReceiptStorage

    val nodeStorage: NodeStorage

    val evmCodeStorage: EvmCodeStorage

    val totalDifficultyStorage: TotalDifficultyStorage

    val appStateStorage: AppStateStorage

    val fastSyncStateStorage: FastSyncStateStorage

    val transactionMappingStorage: TransactionMappingStorage
  }
}
