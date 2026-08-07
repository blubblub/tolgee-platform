import { useRef, useState } from 'react';
import { Box, Button, Typography } from '@mui/material';
import { useTranslate } from '@tolgee/react';
import { useMutation, useQuery, useQueryClient } from 'react-query';
import { useRouteMatch } from 'react-router-dom';

import { BaseProjectView } from 'tg.views/projects/BaseProjectView';
import { useProject } from 'tg.hooks/useProject';
import { useProjectPermissions } from 'tg.hooks/useProjectPermissions';
import { LINKS, PARAMS } from 'tg.constants/links';
import { BoxLoading } from 'tg.component/common/BoxLoading';
import { invalidateUrlPrefix } from 'tg.service/http/useQueryApi';
import { binaryAssetApi, formatBytes } from './binaryAssetApi';
import { BinaryAssetPreview } from './BinaryAssetPreview';
import { AssetTranscript } from './AssetTranscript';
import { AssetLocalizedFiles } from './AssetLocalizedFiles';

export const AssetView = () => {
  const project = useProject();
  const match = useRouteMatch();
  const assetId = Number(match.params[PARAMS.ASSET_ID]);
  const { t } = useTranslate();
  const { satisfiesPermission, satisfiesLanguageAccess } =
    useProjectPermissions();
  const queryClient = useQueryClient();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [error, setError] = useState<string | null>(null);

  const canEdit = satisfiesPermission('keys.edit');
  const canDelete = satisfiesPermission('keys.delete');

  const detailQuery = useQuery(['binary-asset', project.id, assetId], () =>
    binaryAssetApi.get(project.id, assetId)
  );

  const invalidate = () => {
    queryClient.invalidateQueries(['binary-asset', project.id, assetId]);
    invalidateUrlPrefix(queryClient, '/v2/projects/{projectId}/binary-assets');
  };

  const replaceSource = useMutation(
    (file: File) => binaryAssetApi.replaceSource(project.id, assetId, file),
    {
      onSuccess: () => {
        setError(null);
        invalidate();
      },
      onError: (e: any) => setError(e?.message || 'Upload failed'),
    }
  );

  const deleteAsset = useMutation(
    () => binaryAssetApi.deleteAsset(project.id, assetId),
    {
      onSuccess: () => {
        window.location.href = LINKS.PROJECT_ASSETS.build({
          [PARAMS.PROJECT_ID]: project.id,
        });
      },
    }
  );

  const downloadSource = async () => {
    const ticket = await binaryAssetApi.sourceTicket(project.id, assetId);
    window.location.href = ticket.url;
  };

  if (detailQuery.isLoading || !detailQuery.data) {
    return (
      <BaseProjectView windowTitle="Asset" title="Asset">
        <BoxLoading />
      </BaseProjectView>
    );
  }

  const asset = detailQuery.data;

  return (
    <BaseProjectView
      windowTitle={asset.name}
      title={asset.name}
      navigation={[
        [
          t('binary_assets_title', 'Assets'),
          LINKS.PROJECT_ASSETS.build({ [PARAMS.PROJECT_ID]: project.id }),
        ],
        [
          asset.name,
          LINKS.PROJECT_ASSET.build({
            [PARAMS.PROJECT_ID]: project.id,
            [PARAMS.ASSET_ID]: asset.id,
          }),
        ],
      ]}
    >
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t(
          'binary_assets_help',
          'Project-global binary assets (not branch-scoped). Audio, video, and images preview inline.'
        )}
      </Typography>

      {error && (
        <Typography color="error" sx={{ mb: 2 }}>
          {error}
        </Typography>
      )}

      <Box
        mb={3}
        p={2}
        border={1}
        borderColor="divider"
        borderRadius={1}
        data-cy="binary-asset-source"
      >
        <Typography fontWeight={600} mb={1}>
          {t('binary_assets_source', 'Source')} ({asset.sourceLanguageTag}) · r
          {asset.sourceRevision}
        </Typography>
        <Typography variant="body2" color="text.secondary" mb={1}>
          {asset.originalFilename} · {formatBytes(asset.byteSize)} ·{' '}
          {asset.contentType}
        </Typography>
        <Box mb={1.5}>
          <BinaryAssetPreview
            projectId={project.id}
            assetId={asset.id}
            contentType={asset.contentType}
            filename={asset.originalFilename}
          />
        </Box>
        <Box display="flex" gap={1} flexWrap="wrap">
          <Button
            size="small"
            onClick={downloadSource}
            data-cy="binary-asset-download-source"
          >
            {t('binary_assets_download', 'Download')}
          </Button>
          {canEdit && (
            <Button
              size="small"
              variant="outlined"
              onClick={() => fileInputRef.current?.click()}
              data-cy="binary-asset-replace-source"
            >
              {t('binary_assets_replace_source', 'Replace source')}
            </Button>
          )}
          {canDelete && (
            <Button
              size="small"
              color="error"
              onClick={() => {
                if (
                  window.confirm('Delete this asset and all localized files?')
                ) {
                  deleteAsset.mutate();
                }
              }}
              data-cy="binary-asset-delete"
            >
              {t('binary_assets_delete', 'Delete asset')}
            </Button>
          )}
        </Box>
      </Box>

      {/* any asset may carry a transcript; only AI transcription is limited to speech */}
      <AssetTranscript
        asset={asset}
        projectId={project.id}
        canCreate={satisfiesPermission('keys.create')}
        canEdit={canEdit}
        canEditSource={satisfiesLanguageAccess(
          'translations.edit',
          asset.sourceLanguageId
        )}
        onChange={invalidate}
      />
      <Typography fontWeight={600} mb={1}>
        {t('binary_assets_translations', 'Localized files')}
      </Typography>
      <AssetLocalizedFiles projectId={project.id} asset={asset} />

      <input
        ref={fileInputRef}
        type="file"
        hidden
        onChange={(e) => {
          const file = e.target.files?.[0];
          if (file) replaceSource.mutate(file);
          e.target.value = '';
        }}
      />
    </BaseProjectView>
  );
};
