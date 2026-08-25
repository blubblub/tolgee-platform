package io.tolgee.service.key

import io.tolgee.constants.Message
import io.tolgee.dtos.request.ScreenshotInfoDto
import io.tolgee.dtos.request.screenshot.ScreenshotByLocationRequest
import io.tolgee.exceptions.BadRequestException
import io.tolgee.model.Screenshot
import io.tolgee.model.binaryAsset.BinaryAsset
import io.tolgee.model.key.Key
import io.tolgee.repository.KeyRepository
import io.tolgee.repository.KeyScreenshotReferenceRepository
import io.tolgee.repository.binaryAsset.BinaryAssetRepository
import io.tolgee.repository.binaryAsset.BinaryAssetScreenshotReferenceRepository
import io.tolgee.service.security.SecurityService
import io.tolgee.util.nullIfEmpty
import org.springframework.core.io.InputStreamSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * One screenshot per screen, identified by `location`, shared by every key and asset on it.
 *
 * Re-uploading a location replaces the image in place and makes the linked keys and assets exactly
 * what the request lists — links not listed are dropped, keys and assets themselves are never
 * created, deleted or otherwise touched. A bot can therefore rerun freely; a bad run only costs
 * links until the next good one.
 */
@Service
class ScreenshotByLocationService(
  private val screenshotService: ScreenshotService,
  private val screenshotByLocationResolver: ScreenshotByLocationResolver,
  private val keyScreenshotReferenceRepository: KeyScreenshotReferenceRepository,
  private val binaryAssetScreenshotReferenceRepository: BinaryAssetScreenshotReferenceRepository,
  private val securityService: SecurityService,
) {
  companion object {
    /** More names than any real screen holds; a request beyond it is a bot bug, not a big screen. */
    const val MAX_REFERENCES = 500
  }

  data class Result(
    val screenshot: Screenshot,
    val created: Boolean,
    /** Older screenshots at the same location that were folded into this one. */
    val replacedScreenshots: Int,
    val linkedKeys: List<String>,
    val linkedAssets: List<String>,
    val unknownKeys: List<String>,
    val unknownAssets: List<String>,
  )

  @Transactional
  fun upsert(
    projectId: Long,
    image: InputStreamSource,
    request: ScreenshotByLocationRequest,
  ): Result {
    val location = request.location.trim()
    if (location.isEmpty()) throw BadRequestException(Message.SCREENSHOT_LOCATION_REQUIRED)
    if (request.keys.size + request.assets.size > MAX_REFERENCES) {
      throw BadRequestException(Message.SCREENSHOT_TOO_MANY_REFERENCES, listOf(MAX_REFERENCES))
    }

    val keys = screenshotByLocationResolver.resolveKeys(projectId, request)
    val assets = screenshotByLocationResolver.resolveAssets(projectId, request)
    keys.found.values.forEach { securityService.checkBranchModify(it) }

    // Keep the newest at this location, fold the rest into it. Legacy imports made one per key.
    val existing = screenshotService.findAllByProjectAndLocation(projectId, location)
    val primary = existing.maxByOrNull { it.id }
    val stale = existing.filter { it.id != primary?.id }

    val result =
      if (primary == null) {
        screenshotService.storeNew(image, location)
      } else {
        screenshotService.replaceImage(primary, image)
      }
    val screenshot = result.screenshot

    // Set semantics: drop every existing link, then add what the request says. Positions and text
    // come fresh with every upload, so re-adding is simpler than diffing. The links go straight
    // through the repositories — the orphan check would take the now link-less primary with them.
    val touched = stale + screenshot
    val touchedIds = touched.map { it.id }
    keyScreenshotReferenceRepository.findAll(touchedIds).forEach { reference ->
      reference.key.keyScreenshotReferences.remove(reference)
      reference.screenshot.keyScreenshotReferences.remove(reference)
      keyScreenshotReferenceRepository.delete(reference)
    }
    binaryAssetScreenshotReferenceRepository.findAllByScreenshotIdIn(touchedIds).forEach { reference ->
      reference.asset.screenshotReferences.remove(reference)
      reference.screenshot.binaryAssetScreenshotReferences.remove(reference)
      binaryAssetScreenshotReferenceRepository.delete(reference)
    }
    keyScreenshotReferenceRepository.flush()
    stale.forEach { screenshotService.delete(it) }

    request.keys.forEach { ref ->
      val key = keys.found[ref.name to ref.namespace?.nullIfEmpty] ?: return@forEach
      screenshotService.addReference(
        key = key,
        screenshot = screenshot,
        info = ScreenshotInfoDto(text = ref.text, positions = ref.positions, location = location),
        originalDimension = result.originalDimension,
        targetDimension = result.targetDimension,
      )
    }
    assets.found.values.forEach { asset -> screenshotService.addAssetReference(asset, screenshot) }
    screenshotService.initializeReferences(listOf(screenshot))

    return Result(
      screenshot = screenshot,
      created = primary == null,
      replacedScreenshots = stale.size,
      linkedKeys = keys.found.keys.map { it.first },
      linkedAssets = assets.found.keys.toList(),
      unknownKeys = keys.unknown,
      unknownAssets = assets.unknown,
    )
  }
}

/** Name lookups, kept apart so the upsert reads as the policy it implements. */
@Service
class ScreenshotByLocationResolver(
  private val keyRepository: KeyRepository,
  private val binaryAssetRepository: BinaryAssetRepository,
) {
  class Resolved<K, V>(
    val found: Map<K, V>,
    val unknown: List<String>,
  )

  /**
   * Keys by (name, namespace) on the default branch. A screen is a fact about the app as
   * shipped, so branch copies of a key are not what the bot means.
   */
  fun resolveKeys(
    projectId: Long,
    request: ScreenshotByLocationRequest,
  ): Resolved<Pair<String, String?>, Key> {
    if (request.keys.isEmpty()) return Resolved(emptyMap(), emptyList())
    val byName =
      keyRepository
        .findActiveByProjectIdAndNames(projectId, request.keys.map { it.name }.distinct())
        .filter { it.branch == null || it.branch?.isDefault == true }
        .associateBy { it.name to it.namespace?.name }
    val found = LinkedHashMap<Pair<String, String?>, Key>()
    val unknown = mutableListOf<String>()
    request.keys.forEach { ref ->
      val id = ref.name to ref.namespace?.nullIfEmpty
      val key = byName[id]
      if (key == null) unknown += ref.name else found[id] = key
    }
    return Resolved(found, unknown.distinct())
  }

  fun resolveAssets(
    projectId: Long,
    request: ScreenshotByLocationRequest,
  ): Resolved<String, BinaryAsset> {
    if (request.assets.isEmpty()) return Resolved(emptyMap(), emptyList())
    val names = request.assets.map { it.name }.distinct()
    val byName = binaryAssetRepository.findAllByProjectIdAndNameIn(projectId, names).associateBy { it.name }
    val found = LinkedHashMap<String, BinaryAsset>()
    val unknown = mutableListOf<String>()
    names.forEach { name ->
      val asset = byName[name]
      if (asset == null) unknown += name else found[name] = asset
    }
    return Resolved(found, unknown)
  }
}
