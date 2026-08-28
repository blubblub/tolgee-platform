package io.tolgee.model.binaryAsset

import io.tolgee.activity.annotation.ActivityEntityDescribingPaths
import io.tolgee.activity.annotation.ActivityLoggedEntity
import io.tolgee.model.Screenshot
import io.tolgee.model.StandardAuditModel
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/**
 * "This asset is used on this screen." A screenshot may belong to many assets and many keys; an
 * asset may appear on many screenshots. The screenshot itself is shared with the key side — one
 * upload per screen, however many things are on it.
 *
 * Surrogate id rather than the key side's `@IdClass`: the export/import graph and the activity log
 * both handle plain entities without special casing.
 */
@Entity
@Table(
  name = "binary_asset_screenshot_reference",
  uniqueConstraints = [
    UniqueConstraint(
      name = "binary_asset_screenshot_reference_unique",
      columnNames = ["asset_id", "screenshot_id"],
    ),
  ],
  indexes = [
    Index(name = "binary_asset_screenshot_reference_asset_id_idx", columnList = "asset_id"),
    Index(name = "binary_asset_screenshot_reference_screenshot_id_idx", columnList = "screenshot_id"),
  ],
)
@ActivityLoggedEntity
@ActivityEntityDescribingPaths(paths = ["asset"])
class BinaryAssetScreenshotReference(
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "asset_id", nullable = false)
  var asset: BinaryAsset,
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "screenshot_id", nullable = false)
  var screenshot: Screenshot,
) : StandardAuditModel()
