import { ReactNode } from 'react';
import { Box, Chip, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';

import { LINKS, PARAMS } from 'tg.constants/links';
import { visibleTranslations } from './binaryAssetApi';
import { AssetLocalizedFiles } from './AssetLocalizedFiles';
import { BinaryAsset } from './types';

type Props = {
  projectId: number;
  asset: BinaryAsset;
  /** Tags to show; empty or undefined means every target language. */
  languageTags?: string[];
  /** Name of the source language — when set, the source is listed first as a bolded row. */
  sourceLanguageName?: string;
  /** The list links the name to the detail page; the detail page itself renders plain text. */
  linkToDetail?: boolean;
  /** Extra header content, e.g. the detail page's download/delete actions. */
  actions?: ReactNode;
};

/**
 * One asset card: header with counts plus the per-language files table. The assets list renders
 * one per asset and the detail page renders the same card, just filtered to a single asset.
 */
export const AssetCard = ({
  projectId,
  asset,
  languageTags,
  sourceLanguageName,
  linkToDetail,
  actions,
}: Props) => {
  // counts follow the language selection, so they match the rows below
  const rows = visibleTranslations(asset, languageTags);
  const currentCount = rows.filter((r) => r.status === 'CURRENT').length;
  const outdatedCount = rows.filter((r) => r.status === 'OUTDATED').length;

  return (
    <Box
      sx={{
        p: 2,
        border: 1,
        borderColor: 'divider',
        borderRadius: 1,
        // a flex item defaults to min-width:auto, so a wide table would
        // stretch the card and the whole page instead of scrolling inside
        minWidth: 0,
      }}
      data-cy="binary-assets-list-item"
    >
      <Box
        display="flex"
        justifyContent="space-between"
        gap={2}
        flexWrap="wrap"
        alignItems="flex-start"
      >
        <Box flex={1} minWidth={200}>
          {linkToDetail ? (
            <Typography
              fontWeight={600}
              component={RouterLink}
              to={LINKS.PROJECT_ASSET.build({
                [PARAMS.PROJECT_ID]: projectId,
                [PARAMS.ASSET_ID]: asset.id,
              })}
              sx={{
                color: 'inherit',
                textDecoration: 'none',
                '&:hover': { textDecoration: 'underline' },
              }}
            >
              {asset.name}
            </Typography>
          ) : (
            <Typography fontWeight={600}>{asset.name}</Typography>
          )}
          <Typography variant="body2" color="text.secondary">
            {/* the filename lives in the source row now */}
            {asset.sourceLanguageTag} r{asset.sourceRevision}
            {asset.contentType ? ` · ${asset.contentType}` : ''}
          </Typography>
        </Box>
        <Box display="flex" gap={1} alignItems="center">
          <Chip size="small" label={`${currentCount}/${rows.length} current`} />
          {outdatedCount > 0 && (
            <Chip
              size="small"
              color="warning"
              label={`${outdatedCount} outdated`}
            />
          )}
          {actions}
        </Box>
      </Box>

      <Box mt={2}>
        <AssetLocalizedFiles
          projectId={projectId}
          asset={asset}
          languageTags={languageTags}
          sourceLanguageName={sourceLanguageName}
        />
      </Box>
    </Box>
  );
};
