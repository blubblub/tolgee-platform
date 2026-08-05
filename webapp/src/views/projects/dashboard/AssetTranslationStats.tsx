import React from 'react';
import clsx from 'clsx';
import { Box, styled } from '@mui/material';
import { T } from '@tolgee/react';
import { useHistory } from 'react-router-dom';
import { useCurrentLanguage } from '@tginternal/library/hooks/useCurrentLanguage';

import type { components } from 'tg.service/apiSchema.generated';
import { LINKS, PARAMS } from 'tg.constants/links';
import { useProject } from 'tg.hooks/useProject';
import { useProjectPermissions } from 'tg.hooks/useProjectPermissions';
import { TRANSLATION_STATES } from 'tg.constants/translationStates';
import { PercentFormat } from './PercentFormat';
import { getAssetTranslationStats } from './assetTranslationPercentages';

export type { AssetTranslationStatsData } from './assetTranslationPercentages';
export { getAssetTranslationStats } from './assetTranslationPercentages';

type ProjectStatsModel = components['schemas']['ProjectStatsModel'];

type Props = {
  readonly stats: ProjectStatsModel;
};

/* Mirrors ProjectTotals so the two rows read as one set of tiles. */
const StyledTiles = styled(Box)`
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;

  @container (max-width: 800px) {
    grid-template-columns: repeat(2, 1fr);
  }
`;

const StyledTile = styled(Box)`
  background-color: ${({ theme }) => theme.palette.tile.background};
  border-radius: 20px;
  height: 120px;
  display: grid;
  gap: 10px;
  grid-auto-flow: column;
  grid-auto-columns: 1fr;
  position: relative;
  text-align: center;
  align-items: stretch;
  color: ${({ theme: { palette } }) => palette.text.primary};

  &.clickable {
    transition: background-color 0.1s ease-out;
    cursor: pointer;

    &:hover {
      background-color: ${({ theme }) => theme.palette.tile.backgroundHover};
      transition: background-color 0.2s ease-in;
    }
  }

  @container (max-width: 1200px) {
    height: 100px;
  }

  @container (max-width: 800px) {
    height: 80px;
  }
`;

const StyledTileDataItem = styled(Box)`
  display: grid;
  grid-template-rows: 1fr auto auto 1fr;
  grid-template-areas:
    '.'
    'data'
    'label'
    '.';
  border-radius: 20px;
`;

const StyledTileValue = styled(Box)`
  grid-area: data;
  font-size: 28px;
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 8px;
  @container (max-width: 800px) {
    font-size: 24px;
  }
`;

const StyledTileDescription = styled('div')`
  grid-area: label;
  padding: 0px 8px;
  font-size: 18px;
  @container (max-width: 800px) {
    font-size: 14px;
  }
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
`;

const StyledDot = styled(Box)`
  width: 10px;
  height: 10px;
  border-radius: 5px;
  flex-shrink: 0;
`;

export const AssetTranslationStats: React.FC<
  React.PropsWithChildren<Props>
> = ({ stats }) => {
  const project = useProject();
  const history = useHistory();
  const lang = useCurrentLanguage();
  const { satisfiesPermission } = useProjectPermissions();
  const percentages = getAssetTranslationStats(stats);

  const canViewAssets = satisfiesPermission('keys.view');
  const assetsUrl = LINKS.PROJECT_ASSETS.build({
    [PARAMS.PROJECT_ID]: project.id,
  });
  const openAssets = canViewAssets ? () => history.push(assetsUrl) : undefined;

  const tiles = [
    {
      key: 'assets',
      value: Number(stats.binaryAssetCount).toLocaleString(lang),
      label: (
        <T
          keyName="project_dashboard_asset_translations_assets"
          defaultValue="Assets"
        />
      ),
      color: undefined,
      dataCy: 'project-dashboard-asset-count',
    },
    {
      key: 'current',
      value: <PercentFormat number={percentages.current} />,
      label: (
        <T
          keyName="project_dashboard_asset_translation_current"
          defaultValue="Current"
        />
      ),
      color: TRANSLATION_STATES.REVIEWED.color,
      dataCy: 'project-dashboard-asset-current',
    },
    {
      key: 'outdated',
      value: <PercentFormat number={percentages.outdated} />,
      label: (
        <T
          keyName="project_dashboard_asset_translation_outdated"
          defaultValue="Outdated"
        />
      ),
      color: TRANSLATION_STATES.TRANSLATED.color,
      dataCy: 'project-dashboard-asset-outdated',
    },
    {
      key: 'missing',
      value: <PercentFormat number={percentages.missing} />,
      label: (
        <T
          keyName="project_dashboard_asset_translation_missing"
          defaultValue="Missing"
        />
      ),
      color: TRANSLATION_STATES.UNTRANSLATED.color,
      dataCy: 'project-dashboard-asset-missing',
    },
  ];

  return (
    <StyledTiles data-cy="project-dashboard-asset-translation-stats">
      {tiles.map((tile) => (
        <StyledTile
          key={tile.key}
          onClick={openAssets}
          className={clsx({ clickable: Boolean(openAssets) })}
          data-cy={tile.dataCy}
        >
          <StyledTileDataItem>
            <StyledTileValue>
              {tile.color && <StyledDot sx={{ backgroundColor: tile.color }} />}
              {tile.value}
            </StyledTileValue>
            <StyledTileDescription>{tile.label}</StyledTileDescription>
          </StyledTileDataItem>
        </StyledTile>
      ))}
    </StyledTiles>
  );
};
