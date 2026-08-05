import { useEffect, useMemo, useState } from 'react';
import {
  Box,
  Chip,
  CircularProgress,
  Collapse,
  IconButton,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import { ChevronDown, ChevronRight } from '@untitled-ui/icons-react';
import { useTranslate } from '@tolgee/react';
import { Link as RouterLink } from 'react-router-dom';
import { useQuery } from 'react-query';

import { BaseProjectView } from 'tg.views/projects/BaseProjectView';
import { useProject } from 'tg.hooks/useProject';
import { useProjectPermissions } from 'tg.hooks/useProjectPermissions';
import { useProjectLanguages } from 'tg.hooks/useProjectLanguages';
import { ProjectLanguagesProvider } from 'tg.hooks/ProjectLanguagesProvider';
import { LINKS, PARAMS } from 'tg.constants/links';
import { BoxLoading } from 'tg.component/common/BoxLoading';
import { useApiInfiniteQuery } from 'tg.service/http/useQueryApi';
import { useInView } from 'react-intersection-observer';
import {
  binaryAssetApi,
  BinaryAssetTranslationWithVersions,
} from './binaryAssetApi';
import { BinaryAsset, BinaryAssetTranslation } from './types';

const PAGE_SIZE = 30;

type TranslationMap = Record<number, BinaryAssetTranslation>;

const AssetLanguages = ({
  asset,
  projectId,
}: {
  asset: BinaryAsset;
  projectId: number;
}) => {
  const languages = useProjectLanguages();
  const targetLanguages = useMemo(
    () => languages.filter((l) => !l.base),
    [languages]
  );
  const { t } = useTranslate();
  const detailQuery = useQuery(
    ['binary-asset-detail', projectId, asset.id],
    () => binaryAssetApi.get(projectId, asset.id)
  );

  const translationMap: TranslationMap = useMemo(() => {
    const map: TranslationMap = {};
    detailQuery.data?.translations?.forEach((tr) => {
      map[tr.languageId] = tr as unknown as BinaryAssetTranslationWithVersions;
    });
    return map;
  }, [detailQuery.data]);

  if (targetLanguages.length === 0) {
    return (
      <Typography variant="caption" color="text.secondary">
        {t('asset_translations_no_target_languages', 'No target languages.')}
      </Typography>
    );
  }

  if (detailQuery.isLoading) {
    return (
      <Box display="flex" alignItems="center" gap={1} py={0.5}>
        <CircularProgress size={14} />
        <Typography variant="caption" color="text.secondary">
          {t('asset_translations_loading_languages', 'Loading languages…')}
        </Typography>
      </Box>
    );
  }

  return (
    <Box display="flex" gap={1} flexWrap="wrap" alignItems="center" py={0.5}>
      {targetLanguages.map((lang) => {
        const tr = translationMap[lang.id];
        const hasFile = tr && tr.status !== 'MISSING';
        const isOutdated = tr?.status === 'OUTDATED';
        const chosen = (
          tr as unknown as BinaryAssetTranslationWithVersions | undefined
        )?.chosenVersionId;
        const versionCount =
          (tr as unknown as BinaryAssetTranslationWithVersions | undefined)
            ?.versionCount ?? 0;

        const chip = (
          <Chip
            key={lang.id}
            size="small"
            variant={hasFile ? 'filled' : 'outlined'}
            color={
              isOutdated ? 'warning' : chosen != null ? 'success' : 'default'
            }
            label={
              <Box display="flex" alignItems="center" gap={0.5}>
                <span>{lang.tag}</span>
                {hasFile && versionCount > 0 && (
                  <Box
                    component="span"
                    sx={{
                      px: 0.5,
                      borderRadius: 0.5,
                      bgcolor: 'action.selected',
                      fontSize: '0.75rem',
                    }}
                  >
                    {versionCount}
                  </Box>
                )}
                {chosen != null && (
                  <Box component="span" sx={{ fontSize: '0.75rem' }}>
                    ✓
                  </Box>
                )}
              </Box>
            }
            clickable={Boolean(hasFile)}
            component={hasFile ? RouterLink : undefined}
            to={
              hasFile
                ? LINKS.PROJECT_ASSET_TRANSLATION.build({
                    [PARAMS.PROJECT_ID]: projectId,
                    [PARAMS.ASSET_ID]: asset.id,
                    [PARAMS.LANGUAGE_ID]: lang.id,
                  })
                : undefined
            }
            data-cy={`asset-translations-language-chip-${lang.tag}`}
          />
        );

        return chip;
      })}
    </Box>
  );
};

const AssetTranslationsContent = () => {
  const project = useProject();
  const { t } = useTranslate();
  const { satisfiesPermission } = useProjectPermissions();
  const languages = useProjectLanguages();
  const targetLanguages = useMemo(
    () => languages.filter((l) => !l.base),
    [languages]
  );
  const [expanded, setExpanded] = useState<Record<number, boolean>>({});

  const { ref: loadMoreRef, inView } = useInView({ rootMargin: '200px' });

  const listPath = { projectId: project.id };
  const listQueryParams = { size: PAGE_SIZE };

  const listQuery = useApiInfiniteQuery({
    url: '/v2/projects/{projectId}/binary-assets',
    method: 'get',
    path: listPath,
    query: listQueryParams,
    options: {
      keepPreviousData: true,
      noGlobalLoading: true,
      getNextPageParam: (lastPage) => {
        const p = lastPage.page;
        if (p && p.number! < p.totalPages! - 1) {
          return {
            path: listPath,
            query: { ...listQueryParams, page: p.number! + 1 },
          };
        }
        return null;
      },
    },
  });

  useEffect(() => {
    if (inView && listQuery.hasNextPage && !listQuery.isFetchingNextPage) {
      listQuery.fetchNextPage();
    }
  }, [inView, listQuery.hasNextPage, listQuery.isFetchingNextPage]);

  const assets = useMemo(
    () =>
      (listQuery.data?.pages ?? []).flatMap(
        (p) => p._embedded?.binaryAssets ?? []
      ),
    [listQuery.data]
  );
  const total = listQuery.data?.pages?.[0]?.page?.totalElements ?? 0;

  if (!satisfiesPermission('translations.view')) {
    return (
      <BaseProjectView
        windowTitle={t('asset_translations_title', 'Asset translations')}
        title={t('asset_translations_title', 'Asset translations')}
      >
        <Typography color="error">
          {t('asset_translations_no_permission', 'You cannot view this page.')}
        </Typography>
      </BaseProjectView>
    );
  }

  return (
    <BaseProjectView
      windowTitle={t('asset_translations_title', 'Asset translations')}
      title={t('asset_translations_title', 'Asset translations')}
      navigation={[
        [
          t('asset_translations_title', 'Asset translations'),
          LINKS.PROJECT_ASSET_TRANSLATIONS.build({
            [PARAMS.PROJECT_ID]: project.id,
          }),
        ],
      ]}
    >
      <Typography
        variant="body2"
        color="text.secondary"
        sx={{ mb: 2 }}
        data-cy="asset-translations-help"
      >
        {t(
          'asset_translations_help',
          'Manage per-language asset translations and pipeline versions.'
        )}
      </Typography>

      {targetLanguages.length === 0 ? (
        <Typography color="text.secondary" data-cy="asset-translations-empty">
          {t(
            'asset_translations_no_target_languages',
            'Add target languages to see asset translations.'
          )}
        </Typography>
      ) : listQuery.isLoading ? (
        <BoxLoading />
      ) : assets.length === 0 ? (
        <Typography color="text.secondary" data-cy="asset-translations-empty">
          {t('asset_translations_no_assets', 'No assets yet.')}
        </Typography>
      ) : (
        <>
          <Typography
            variant="caption"
            color="text.secondary"
            sx={{ display: 'block', mb: 1 }}
          >
            {t(
              'asset_translations_count',
              '{count, plural, one {# asset} other {# assets}}',
              { count: total }
            )}
          </Typography>

          <Table size="small" data-cy="asset-translations-table">
            <TableHead>
              <TableRow>
                <TableCell />
                <TableCell>
                  {t('asset_translations_asset_name', 'Asset')}
                </TableCell>
                <TableCell>
                  {t('asset_translations_source_language', 'Source')}
                </TableCell>
                <TableCell>
                  {t('asset_translations_status', 'Status')}
                </TableCell>
                <TableCell>
                  {t('asset_translations_languages', 'Languages')}
                </TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {assets.map((asset) => {
                const isExpanded = expanded[asset.id] ?? false;
                return (
                  <TableRow key={asset.id}>
                    <TableCell padding="checkbox">
                      <IconButton
                        size="small"
                        onClick={() =>
                          setExpanded((prev) => ({
                            ...prev,
                            [asset.id]: !prev[asset.id],
                          }))
                        }
                        data-cy={`asset-translations-expand-${asset.id}`}
                      >
                        {isExpanded ? (
                          <ChevronDown width={18} height={18} />
                        ) : (
                          <ChevronRight width={18} height={18} />
                        )}
                      </IconButton>
                    </TableCell>
                    <TableCell>
                      <Typography
                        component={RouterLink}
                        to={LINKS.PROJECT_ASSET.build({
                          [PARAMS.PROJECT_ID]: project.id,
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
                    </TableCell>
                    <TableCell>{asset.sourceLanguageTag}</TableCell>
                    <TableCell>
                      <Box display="flex" gap={1}>
                        <Chip
                          size="small"
                          label={`${asset.currentCount}/${asset.targetLanguageCount} current`}
                        />
                        {asset.outdatedCount > 0 && (
                          <Chip
                            size="small"
                            color="warning"
                            label={`${asset.outdatedCount} outdated`}
                          />
                        )}
                      </Box>
                    </TableCell>
                    <TableCell>
                      <Collapse in={isExpanded} timeout="auto" unmountOnExit>
                        <AssetLanguages asset={asset} projectId={project.id} />
                      </Collapse>
                      {!isExpanded && (
                        <Typography variant="caption" color="text.secondary">
                          {t(
                            'asset_translations_expand_hint',
                            'Expand to see languages'
                          )}
                        </Typography>
                      )}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </>
      )}

      {listQuery.hasNextPage && (
        <Box
          ref={loadMoreRef}
          mt={2}
          display="flex"
          justifyContent="center"
          data-cy="asset-translations-load-more"
        >
          <BoxLoading />
        </Box>
      )}
    </BaseProjectView>
  );
};

export const AssetTranslationsView = () => (
  <ProjectLanguagesProvider>
    <AssetTranslationsContent />
  </ProjectLanguagesProvider>
);
