package io.tolgee.model.binaryAsset

import io.tolgee.model.Language
import io.tolgee.model.Project
import io.tolgee.model.StandardAuditModel
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

/**
 * Default ElevenLabs voice for the pipeline tools, per project and optionally per language.
 *
 * Same shape as `MtServiceConfig`: a row with no [language] is the project-wide default, a row with
 * one overrides it for that language. A per-run `voiceId` param still beats both.
 */
@Entity
@Table(
  name = "binary_asset_voice",
  indexes = [
    Index(name = "binary_asset_voice_project_id_idx", columnList = "project_id"),
  ],
)
class BinaryAssetVoice(
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "project_id", nullable = false)
  var project: Project,
) : StandardAuditModel() {
  /** null = the project-wide default */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "language_id")
  var language: Language? = null

  @Column(name = "voice_id", nullable = false, length = 64)
  lateinit var voiceId: String
}
