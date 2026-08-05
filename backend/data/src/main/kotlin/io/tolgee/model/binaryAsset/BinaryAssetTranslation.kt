package io.tolgee.model.binaryAsset

import io.tolgee.activity.annotation.ActivityEntityDescribingPaths
import io.tolgee.activity.annotation.ActivityLoggedEntity
import io.tolgee.activity.annotation.ActivityLoggedProp
import io.tolgee.model.Language
import io.tolgee.model.StandardAuditModel
import io.tolgee.model.UserAccount
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@ActivityLoggedEntity
// without this the activity row has no relation to hang a link on
@ActivityEntityDescribingPaths(paths = ["asset"])
@Table(
  name = "binary_asset_translation",
  uniqueConstraints = [
    UniqueConstraint(
      name = "binary_asset_translation_asset_language_unique",
      columnNames = ["asset_id", "language_id"],
    ),
  ],
  indexes = [
    Index(name = "binary_asset_translation_asset_id_idx", columnList = "asset_id"),
    Index(name = "binary_asset_translation_language_id_idx", columnList = "language_id"),
  ],
)
class BinaryAssetTranslation(
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "asset_id", nullable = false)
  var asset: BinaryAsset,
) : StandardAuditModel() {
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "language_id", nullable = false)
  lateinit var language: Language

  @ActivityLoggedProp
  @Column(nullable = false)
  var sourceRevision: Long = 1

  // 512 matches the column; without it Hibernate assumes 255 and diffChangeLog
  // generates a migration that would SHRINK the column.
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

  @ManyToOne(fetch = FetchType.LAZY)
  var uploadedBy: UserAccount? = null
}
