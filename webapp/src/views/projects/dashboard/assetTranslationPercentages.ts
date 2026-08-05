import type { components } from 'tg.service/apiSchema.generated';

type ProjectStatsModel = components['schemas']['ProjectStatsModel'];

export type AssetTranslationStatsData = {
  readonly current: number;
  readonly outdated: number;
  readonly missing: number;
};

/**
 * Kept apart from the component so the test can import it without pulling in React or
 * @tginternal/library, whose vite polyfill shims do not resolve under vitest.
 */
export const getAssetTranslationStats = (
  stats: ProjectStatsModel
): AssetTranslationStatsData => {
  const total =
    stats.currentBinaryAssetTranslationCount +
    stats.outdatedBinaryAssetTranslationCount +
    stats.missingBinaryAssetTranslationCount;

  if (total === 0) {
    return { current: 0, outdated: 0, missing: 0 };
  }

  return {
    current: Math.round(
      (stats.currentBinaryAssetTranslationCount / total) * 100
    ),
    outdated: Math.round(
      (stats.outdatedBinaryAssetTranslationCount / total) * 100
    ),
    missing: Math.round(
      (stats.missingBinaryAssetTranslationCount / total) * 100
    ),
  };
};
