import { useMemo, useState } from 'react';
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
import { useQuery, useMutation, useQueryClient } from 'react-query';
import { Link as RouterLink } from 'react-router-dom';

import { BaseProjectView } from 'tg.views/projects/BaseProjectView';
import { useProject } from 'tg.hooks/useProject';
import { useProjectPermissions } from 'tg.hooks/useProjectPermissions';
import { LINKS, PARAMS } from 'tg.constants/links';
import { BoxLoading } from 'tg.component/common/BoxLoading';
import { binaryAssetApi } from './binaryAssetApi';
import { BinaryAssetPreview } from './BinaryAssetPreview';

type MediaFilter = 'AUDIO' | 'VIDEO' | 'IMAGE';

export const AssetsView = () => {
  const project = useProject();
  const { t } = useTranslate();
  const { satisfiesPermission } = useProjectPermissions();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState('');
  const [mediaFilters, setMediaFilters] = useState<MediaFilter[]>([]);
  const [name, setName] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);

  const canCreate = satisfiesPermission('keys.create');

  const listQuery = useQuery(
    ['binary-assets', project.id, page, search, mediaFilters.slice().sort().join(',')],
    () =>
      binaryAssetApi.list(
        project.id,
        page,
        search || undefined,
        mediaFilters.length ? mediaFilters : undefined
      ),
    { keepPreviousData: true }
  );

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
        queryClient.invalidateQueries(['binary-assets', project.id]);
      },
      onError: (e: any) => {
        setError(e?.message || t('binary_assets_create_failed', 'Create failed'));
      },
    }
  );

  const assets = listQuery.data?._embedded?.binaryAssets ?? [];
  const total = listQuery.data?.page?.totalElements ?? 0;

  const filterLabel = useMemo(() => {
    if (!mediaFilters.length) return null;
    return mediaFilters.join(', ');
  }, [mediaFilters]);

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
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }} data-cy="binary-assets-help">
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
          <Button variant="outlined" component="label" data-cy="binary-assets-file-input">
            {file ? file.name : t('binary_assets_choose_file', 'Choose source file')}
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
            setPage(0);
          }}
          data-cy="binary-assets-search"
        />
        <ToggleButtonGroup
          size="small"
          exclusive={false}
          value={mediaFilters}
          onChange={(_e, next: MediaFilter[]) => {
            setMediaFilters(next);
            setPage(0);
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
        {mediaFilters.length > 0 && (
          <Button
            size="small"
            onClick={() => {
              setMediaFilters([]);
              setPage(0);
            }}
            data-cy="binary-assets-filter-clear"
          >
            {t('binary_assets_filter_clear', 'Clear filters')}
          </Button>
        )}
      </Box>

      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 1 }}>
        {filterLabel
          ? t('binary_assets_filter_summary', '{count} assets · filter: {filter}', {
              count: total,
              filter: filterLabel,
            })
          : t('binary_assets_count', '{count} assets', { count: total })}
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
        <Box display="flex" flexDirection="column" gap={1} data-cy="binary-assets-list">
          {assets.map((asset) => (
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
                    {asset.originalFilename} · {asset.sourceLanguageTag} r
                    {asset.sourceRevision} · {asset.contentType}
                  </Typography>
                  <Box mt={1}>
                    <BinaryAssetPreview
                      projectId={project.id}
                      assetId={asset.id}
                      contentType={asset.contentType}
                      filename={asset.originalFilename}
                      compact
                    />
                  </Box>
                </Box>
                <Box display="flex" gap={1} alignItems="center">
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
              </Box>
            </Box>
          ))}
        </Box>
      )}

      {(listQuery.data?.page?.totalPages ?? 0) > 1 && (
        <Box mt={2} display="flex" gap={1}>
          <Button disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
            Previous
          </Button>
          <Button
            disabled={page + 1 >= (listQuery.data?.page?.totalPages ?? 0)}
            onClick={() => setPage((p) => p + 1)}
          >
            Next
          </Button>
        </Box>
      )}
    </BaseProjectView>
  );
};
