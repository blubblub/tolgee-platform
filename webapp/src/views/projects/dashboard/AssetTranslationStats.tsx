import React from 'react';
import { Box, styled } from '@mui/material';
import { T } from '@tolgee/react';

import type { components } from 'tg.service/apiSchema.generated';
import { TRANSLATION_STATES } from 'tg.constants/translationStates';
import { PercentFormat } from './PercentFormat';

type ProjectStatsModel = components['schemas']['ProjectStatsModel'];

type Props = {
  readonly stats: ProjectStatsModel;
};

export type AssetTranslationStatsData = {
  readonly current: number;
  readonly outdated: number;
  readonly missing: number;
};

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

const StyledContainer = styled(Box)`
  background-color: ${({ theme }) => theme.palette.tile.background};
  border-radius: 20px;
  padding: 16px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 16px 32px;

  @container (max-width: 800px) {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }
`;

const StyledHeading = styled(Box)`
  font-size: 18px;
  font-weight: 500;
`;

const StyledStats = styled(Box)`
  display: flex;
  flex-wrap: wrap;
  gap: 16px 24px;
`;

const StyledStatItem = styled(Box)`
  display: flex;
  align-items: center;
  gap: 8px;
`;

const StyledDot = styled(Box)`
  width: 8px;
  height: 8px;
  border-radius: 4px;
`;

const StyledValue = styled(Box)`
  font-size: 18px;
  font-weight: 500;
`;

const StyledLabel = styled(Box)`
  font-size: 14px;
  color: ${({ theme }) => theme.palette.text.secondary};
`;

const STAT_ITEMS = [
  {
    key: 'current' as const,
    labelKey: 'project_dashboard_asset_translation_current',
    defaultValue: 'Current',
    color: TRANSLATION_STATES.REVIEWED.color,
  },
  {
    key: 'outdated' as const,
    labelKey: 'project_dashboard_asset_translation_outdated',
    defaultValue: 'Outdated',
    color: TRANSLATION_STATES.TRANSLATED.color,
  },
  {
    key: 'missing' as const,
    labelKey: 'project_dashboard_asset_translation_missing',
    defaultValue: 'Missing',
    color: TRANSLATION_STATES.UNTRANSLATED.color,
  },
] as const;

export const AssetTranslationStats: React.FC<
  React.PropsWithChildren<Props>
> = ({ stats }) => {
  const percentages = getAssetTranslationStats(stats);

  return (
    <StyledContainer data-cy="project-dashboard-asset-translation-stats">
      <StyledHeading data-cy="project-dashboard-asset-translation-heading">
        <T
          keyName="project_dashboard_asset_translations_heading"
          defaultValue="Asset translations"
        />
      </StyledHeading>
      <Box data-cy="project-dashboard-asset-translation-count">
        <T
          keyName="project_dashboard_asset_translations_count"
          defaultValue="{count, plural, one {# asset} other {# assets}}"
          params={{ count: stats.binaryAssetCount }}
        />
      </Box>
      <StyledStats>
        {STAT_ITEMS.map(({ key, labelKey, defaultValue, color }) => (
          <StyledStatItem key={key}>
            <StyledDot sx={{ backgroundColor: color }} />
            <StyledValue>
              <PercentFormat number={percentages[key]} />
            </StyledValue>
            <StyledLabel>
              <T keyName={labelKey} defaultValue={defaultValue} />
            </StyledLabel>
          </StyledStatItem>
        ))}
      </StyledStats>
    </StyledContainer>
  );
};
