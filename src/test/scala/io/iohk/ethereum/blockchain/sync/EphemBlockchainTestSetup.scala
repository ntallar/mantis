package io.iohk.ethereum.blockchain.sync

import io.iohk.ethereum.db.components.Storages.PruningModeComponent
import io.iohk.ethereum.db.components.{SharedEphemDataSources, Storages}
import io.iohk.ethereum.db.storage.pruning.{ArchivePruning, PruningMode}
import io.iohk.ethereum.domain.BlockchainImpl
import io.iohk.ethereum.nodebuilder.{BlockchainBuilder, StorageBuilder}


trait EphemBlockchainTestSetup extends BlockchainBuilder with StorageBuilder {

  trait Pruning extends PruningModeComponent {
    override val pruningMode: PruningMode = ArchivePruning
  }

  override lazy val storagesInstance =  new SharedEphemDataSources with Pruning with Storages.DefaultStorages

  override lazy val blockchain = BlockchainImpl(storagesInstance.storages)
}
