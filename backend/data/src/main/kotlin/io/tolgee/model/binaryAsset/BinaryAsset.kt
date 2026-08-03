package io.tolgee.model.binaryAsset

import io.tolgee.activity.annotation.ActivityDescribingProp
import io.tolgee.activity.annotation.ActivityLoggedEntity
import io.tolgee.activity.annotation.ActivityLoggedProp
import io.tolgee.model.Language
import io.tolgee.model.Project
import io.tolgee.model.StandardAuditModel
import io.tolgee.model.UserAccount
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.ColumnDefault

@Entity
@ActivityLoggedEntity
@Table(
  name = "binary_asset",
  uniqueConstraints = [
    UniqueConstraint(name = "binary_asset_project_name_unique", columnNames = ["project_id", "name"]),
  ],
  indexes = [
    Index(name = "binary_asset_project_id_idx", columnList = "project_id"),
    Index(name = "binary_asset_source_language_id_idx", columnList = "source_language_id"),
  ],
)
class BinaryAsset(
  @ManyToOne(fetch = FetchType.LAZY)
  var project: Project,
) : StandardAuditModel() {
  @ActivityLoggedProp
  @ActivityDescribingProp
  @Column(nullable = false)
  lateinit var name: String

  @ActivityLoggedProp
  @Column(columnDefinition = "text")
  var description: String? = null

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "source_language_id", nullable = false)
  lateinit var sourceLanguage: Language

  @ColumnDefault("1")
  @Column(nullable = false)
  var sourceRevision: Long = 1

  @Column(nullable = false)
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

  @OneToMany(mappedBy = "asset", orphanRemoval = true)
  var translations: MutableList<BinaryAssetTranslation> = mutableListOf()
}
