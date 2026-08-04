import type { components } from 'tg.service/apiSchema.generated';
import { getAssetTranslationStats } from './AssetTranslationStats';

type ProjectStatsModel = components['schemas']['ProjectStatsModel'];

const baseStats = (
  overrides: Partial<ProjectStatsModel> = {}
): ProjectStatsModel => ({
  projectId: 1,
  languageCount: 0,
  languageStats: [],
  keyCount: 0,
  baseWordsCount: 0,
  translatedPercentage: 0,
  reviewedPercentage: 0,
  membersCount: 0,
  tagCount: 0,
  taskCount: 0,
  binaryAssetCount: 0,
  currentBinaryAssetTranslationCount: 0,
  outdatedBinaryAssetTranslationCount: 0,
  missingBinaryAssetTranslationCount: 0,
  ...overrides,
});

describe('getAssetTranslationStats', () => {
  it('computes percentages from binary asset translation counts', () => {
    const stats = baseStats({
      binaryAssetCount: 4,
      currentBinaryAssetTranslationCount: 50,
      outdatedBinaryAssetTranslationCount: 30,
      missingBinaryAssetTranslationCount: 20,
    });
    expect(getAssetTranslationStats(stats)).toEqual({
      current: 50,
      outdated: 30,
      missing: 20,
    });
  });

  it('returns zero percentages when total target translations is 0', () => {
    const stats = baseStats({
      binaryAssetCount: 3,
      currentBinaryAssetTranslationCount: 0,
      outdatedBinaryAssetTranslationCount: 0,
      missingBinaryAssetTranslationCount: 0,
    });
    expect(getAssetTranslationStats(stats)).toEqual({
      current: 0,
      outdated: 0,
      missing: 0,
    });
  });

  it('rounds to whole percentages', () => {
    const stats = baseStats({
      binaryAssetCount: 1,
      currentBinaryAssetTranslationCount: 1,
      outdatedBinaryAssetTranslationCount: 1,
      missingBinaryAssetTranslationCount: 1,
    });
    expect(getAssetTranslationStats(stats)).toEqual({
      current: 33,
      outdated: 33,
      missing: 33,
    });
  });
});
