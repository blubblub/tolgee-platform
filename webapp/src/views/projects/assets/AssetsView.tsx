import { useEffect, useMemo, useState } from 'react';
import {
  Box,
  Button,
  Chip,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from '@mui/material';
import { Plus } from '@untitled-ui/icons-react';
import { useTranslate } from '@tolgee/react';
import { useMutation, useQueryClient } from 'react-query';
import { Link as RouterLink } from 'react-router-dom';

import { BaseProjectView } from 'tg.views/projects/BaseProjectView';
import { useProject } from 'tg.hooks/useProject';
import { useProjectLanguages } from 'tg.hooks/useProjectLanguages';
import { ProjectLanguagesProvider } from 'tg.hooks/ProjectLanguagesProvider';
import { useProjectPermissions } from 'tg.hooks/useProjectPermissions';
import { useUrlSearchState } from 'tg.hooks/useUrlSearchState';
import { LanguagesSelect } from 'tg.component/common/form/LanguagesSelect/LanguagesSelect';
import { LINKS, PARAMS } from 'tg.constants/links';
import { BoxLoading } from 'tg.component/common/BoxLoading';
import {
  invalidateUrlPrefix,
  useApiInfiniteQuery,
} from 'tg.service/http/useQueryApi';
import { useInView } from 'react-intersection-observer';
import { binaryAssetApi, visibleTranslations } from './binaryAssetApi';
import { AssetLocalizedFiles } from './AssetLocalizedFiles';

type MediaFilter = 'AUDIO' | 'VIDEO' | 'IMAGE';

const PAGE_SIZE = 30;

/** Stable identity — useUrlSearchState memoizes on the default value. */
const ALL_LANGUAGES: string[] = [];

const AssetsViewContent = () => {
  const project = useProject();
  const { t } = useTranslate();
  const { satisfiesPermission } = useProjectPermissions();
  const queryClient = useQueryClient();
  const projectLanguages = useProjectLanguages();
  const [search, setSearch] = useState('');
  const [mediaFilters, setMediaFilters] = useState<MediaFilter[]>([]);
  const [name, setName] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  // shareable like the translations view: /assets?languages=de&languages=fr
  const [selectedLanguages, setSelectedLanguages] = useUrlSearchState(
    'languages',
    { array: true, defaultVal: ALL_LANGUAGES }
  );

  const canCreate = satisfiesPermission('keys.create');

  const { ref: loadMoreRef, inView } = useInView({ rootMargin: '200px' });

  const listPath = { projectId: project.id };
  const listQueryParams = {
    size: PAGE_SIZE,
    search: search || undefined,
    filterMediaType: mediaFilters.length ? mediaFilters : undefined,
  };

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

  const createMutation = useMutation(
    () =>
      binaryAssetApi.create(project.id, {
        name: name.trim(),
        file: file!,
      }),
    {
      onSuccess: () => {
        setName('');
        setFile(null);
        setError(null);
        invalidateUrlPrefix(
          queryClient,
          '/v2/projects/{projectId}/binary-assets'
        );
      },
      onError: (e: any) => {
        setError(
          e?.message || t('binary_assets_create_failed', 'Create failed')
        );
      },
    }
  );

  const assets = useMemo(
    () =>
      (listQuery.data?.pages ?? []).flatMap(
        (p) => p._embedded?.binaryAssets ?? []
      ),
    [listQuery.data]
  );
  const total = listQuery.data?.pages?.[0]?.page?.totalElements ?? 0;

  const filterLabel = useMemo(() => {
    if (!mediaFilters.length) return null;
    return mediaFilters.join(', ');
  }, [mediaFilters]);

  const hasFilters = mediaFilters.length > 0 || selectedLanguages.length > 0;

  return (
    <BaseProjectView
      windowTitle={t('binary_assets_title', 'Assets')}
      title={t('binary_assets_title', 'Assets')}
      navigation={[
        [
          t('binary_assets_title', 'Assets'),
          LINKS.PROJECT_ASSETS.build({ [PARAMS.PROJECT_ID]: project.id }),
        ],
      ]}
    >
      <Typography
        variant="body2"
        color="text.secondary"
        sx={{ mb: 2 }}
        data-cy="binary-assets-help"
      >
        {t(
          'binary_assets_help',
          'Project-global binary assets (not branch-scoped). Audio, video, and images preview inline. Assets are managed here, not under Translations.'
        )}
      </Typography>

      {canCreate && (
        <Box
          display="flex"
          gap={1}
          flexWrap="wrap"
          alignItems="center"
          mb={3}
          data-cy="binary-assets-create"
        >
          <TextField
            size="small"
            label={t('binary_assets_name', 'Name')}
            value={name}
            onChange={(e) => setName(e.target.value)}
            data-cy="binary-assets-name-input"
          />
          <Button
            variant="outlined"
            component="label"
            data-cy="binary-assets-file-input"
          >
            {file
              ? file.name
              : t('binary_assets_choose_file', 'Choose source file')}
            <input
              hidden
              type="file"
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            />
          </Button>
          <Button
            variant="contained"
            startIcon={<Plus />}
            disabled={!name.trim() || !file || createMutation.isLoading}
            onClick={() => createMutation.mutate()}
            data-cy="binary-assets-create-button"
          >
            {t('binary_assets_create', 'Create asset')}
          </Button>
          {error && (
            <Typography color="error" variant="body2">
              {error}
            </Typography>
          )}
        </Box>
      )}

      <Box
        mb={2}
        display="flex"
        gap={2}
        flexWrap="wrap"
        alignItems="center"
        data-cy="binary-assets-filters"
      >
        <TextField
          size="small"
          sx={{ flex: 1, minWidth: 200 }}
          placeholder={t('binary_assets_search', 'Search assets')}
          value={search}
          onChange={(e) => {
            setSearch(e.target.value);
          }}
          data-cy="binary-assets-search"
        />
        <ToggleButtonGroup
          size="small"
          exclusive={false}
          value={mediaFilters}
          onChange={(_e, next: MediaFilter[]) => {
            setMediaFilters(next);
          }}
          aria-label={t('binary_assets_type_filter', 'Filter by type')}
          data-cy="binary-assets-type-filter"
        >
          <ToggleButton value="AUDIO" data-cy="binary-assets-filter-audio">
            {t('binary_assets_filter_audio', 'Audio')}
          </ToggleButton>
          <ToggleButton value="VIDEO" data-cy="binary-assets-filter-video">
            {t('binary_assets_filter_video', 'Video')}
          </ToggleButton>
          <ToggleButton value="IMAGE" data-cy="binary-assets-filter-image">
            {t('binary_assets_filter_image', 'Image')}
          </ToggleButton>
        </ToggleButtonGroup>
        <LanguagesSelect
          onChange={setSelectedLanguages}
          value={selectedLanguages}
          languages={projectLanguages}
          context="assets"
          enableEmpty
          placeholder={t('binary_assets_all_languages', 'All languages')}
        />
        {hasFilters && (
          <Button
            size="small"
            onClick={() => {
              setMediaFilters([]);
              setSelectedLanguages([]);
            }}
            data-cy="binary-assets-filter-clear"
          >
            {t('binary_assets_filter_clear', 'Clear filters')}
          </Button>
        )}
      </Box>

      <Typography
        variant="caption"
        color="text.secondary"
        sx={{ display: 'block', mb: 1 }}
      >
        {filterLabel
          ? t(
              'binary_assets_filter_summary',
              '{count, plural, one {# asset} other {# assets}} · filter: {filter}',
              {
                count: total,
                filter: filterLabel,
              }
            )
          : t(
              'binary_assets_count',
              '{count, plural, one {# asset} other {# assets}}',
              { count: total }
            )}
      </Typography>

      {listQuery.isError && (
        <Typography color="error" sx={{ mb: 2 }} data-cy="binary-assets-error">
          {(listQuery.error as any)?.message ||
            t('binary_assets_load_failed', 'Failed to load assets.')}
        </Typography>
      )}

      {listQuery.isLoading ? (
        <BoxLoading />
      ) : assets.length === 0 ? (
        <Typography color="text.secondary" data-cy="binary-assets-empty">
          {t('binary_assets_empty', 'No assets yet.')}
        </Typography>
      ) : (
        <Box
          display="flex"
          flexDirection="column"
          gap={2}
          data-cy="binary-assets-list"
        >
          {assets.map((asset) => {
            // counts follow the language selection, so they match the rows below
            const rows = visibleTranslations(asset, selectedLanguages);
            const currentCount = rows.filter(
              (r) => r.status === 'CURRENT'
            ).length;
            const outdatedCount = rows.filter(
              (r) => r.status === 'OUTDATED'
            ).length;
            return (
              <Box
                key={asset.id}
                sx={{
                  p: 2,
                  border: 1,
                  borderColor: 'divider',
                  borderRadius: 1,
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
                    <Typography
                      fontWeight={600}
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
                    <Typography variant="body2" color="text.secondary">
                      {/* the filename lives in the source row now */}
                      {asset.sourceLanguageTag} r{asset.sourceRevision} ·{' '}
                      {asset.contentType}
                    </Typography>
                  </Box>
                  <Box display="flex" gap={1} alignItems="center">
                    <Chip
                      size="small"
                      label={`${currentCount}/${rows.length} current`}
                    />
                    {outdatedCount > 0 && (
                      <Chip
                        size="small"
                        color="warning"
                        label={`${outdatedCount} outdated`}
                      />
                    )}
                  </Box>
                </Box>

                <Box mt={2}>
                  <AssetLocalizedFiles
                    projectId={project.id}
                    asset={asset}
                    languageTags={selectedLanguages}
                    sourceLanguageName={
                      projectLanguages.find(
                        (l) => l.tag === asset.sourceLanguageTag
                      )?.name ?? asset.sourceLanguageTag
                    }
                  />
                </Box>
              </Box>
            );
          })}
        </Box>
      )}

      {listQuery.hasNextPage && (
        <Box
          ref={loadMoreRef}
          mt={2}
          display="flex"
          justifyContent="center"
          data-cy="binary-assets-load-more"
        >
          <BoxLoading />
        </Box>
      )}
    </BaseProjectView>
  );
};

export const AssetsView = () => (
  <ProjectLanguagesProvider>
    <AssetsViewContent />
  </ProjectLanguagesProvider>
);
