package io.tolgee.model.binaryAsset

import io.tolgee.activity.annotation.ActivityEntityDescribingPaths
import io.tolgee.activity.annotation.ActivityLoggedEntity
import io.tolgee.activity.annotation.ActivityLoggedProp
import io.tolgee.model.StandardAuditModel
import io.tolgee.model.UserAccount
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

@Entity
@Table(name = "binary_asset_translation_version")
@ActivityLoggedEntity
@ActivityEntityDescribingPaths(paths = ["translation", "asset"])
class BinaryAssetTranslationVersion(
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "asset_id", nullable = false)
  var asset: BinaryAsset,
  /**
   * Null for a version of the asset's own source file — the source language has no translation row,
   * the asset itself is its original. Any other language points at its translation.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "translation_id")
  var translation: BinaryAssetTranslation? = null,
) : StandardAuditModel() {
  @Column(nullable = false, length = 512)
  lateinit var storageKey: String

  @Column(nullable = false)
  lateinit var originalFilename: String

  @Column(nullable = false)
  lateinit var contentType: String

  @Column(nullable = false)
  var byteSize: Long = 0

  @Column(nullable = false, length = 64)
  lateinit var sha256: String

  @ActivityLoggedProp
  @Column(nullable = false, length = 64)
  lateinit var tool: String

  @Column(columnDefinition = "text")
  var toolParams: String? = null

  @ActivityLoggedProp
  @Column(nullable = false)
  var chosen: Boolean = false

  @ManyToOne(fetch = FetchType.LAZY)
  var createdBy: UserAccount? = null
}
