package io.tolgee.model.binaryAsset

import io.tolgee.activity.annotation.ActivityDescribingProp
import io.tolgee.activity.annotation.ActivityLoggedEntity
import io.tolgee.activity.annotation.ActivityLoggedProp
import io.tolgee.model.Language
import io.tolgee.model.Project
import io.tolgee.model.StandardAuditModel
import io.tolgee.model.UserAccount
import io.tolgee.model.key.Key
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

  /**
   * Plain-text transcript of the spoken content, stored as a regular key so the whole
   * translation stack (states, MT, review, export) applies to it.
   *
   * ManyToOne rather than OneToOne: a linked (not owned) key may back several assets, e.g. two
   * voiceovers of the same on-screen string.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "transcript_key_id")
  var transcriptKey: Key? = null

  /** True when the key was created for this asset and should die with it. */
  @ActivityLoggedProp
  @ColumnDefault("false")
  @Column(nullable = false)
  var transcriptKeyOwned: Boolean = false

  @OneToMany(mappedBy = "asset", orphanRemoval = true)
  var translations: MutableList<BinaryAssetTranslation> = mutableListOf()

  /** Every pipeline version of this asset — the source file's and every language's. */
  @OneToMany(mappedBy = "asset", orphanRemoval = true)
  var versions: MutableList<BinaryAssetTranslationVersion> = mutableListOf()
}
