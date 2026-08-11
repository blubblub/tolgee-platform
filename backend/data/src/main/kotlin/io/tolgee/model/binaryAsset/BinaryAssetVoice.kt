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
 * Default ElevenLabs voice for the pipeline tools, per project and optionally per language and tool.
 *
 * Same shape as `MtServiceConfig`: a row with no [language] is the project-wide default, a row with
 * one overrides it for that language. [tool] narrows a row further to a single pipeline tool, so
 * `voice-changer` can differ from `tts`. A per-run `voiceId` param still beats all of them.
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

  /**
   * Name of the tool this row applies to, or [ANY_TOOL] for every tool.
   *
   * Deliberately not nullable: a null here would make the uniqueness rules need four partial
   * indexes instead of two, because Postgres treats NULLs as distinct.
   */
  @Column(name = "tool", nullable = false, length = 32)
  var tool: String = ANY_TOOL

  @Column(name = "voice_id", nullable = false, length = 64)
  lateinit var voiceId: String

  companion object {
    /** Sentinel for "applies to whichever tool runs" — the behaviour before tools had own defaults. */
    const val ANY_TOOL = ""

    /** Normalises a caller-supplied tool name; omitted or blank means [ANY_TOOL]. */
    fun toolKey(raw: String?): String = raw?.trim()?.takeIf { it.isNotBlank() } ?: ANY_TOOL
  }
}
