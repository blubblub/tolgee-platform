import { useRef, useState } from 'react';
import {
  Box,
  Button,
  Chip,
  CircularProgress,
  IconButton,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
} from '@mui/material';
import { Stars01 } from '@untitled-ui/icons-react';
import { useTranslate } from '@tolgee/react';
import { useMutation, useQuery, useQueryClient } from 'react-query';
import { useRouteMatch } from 'react-router-dom';

import { BaseProjectView } from 'tg.views/projects/BaseProjectView';
import { useProject } from 'tg.hooks/useProject';
import { useProjectPermissions } from 'tg.hooks/useProjectPermissions';
import { LINKS, PARAMS } from 'tg.constants/links';
import { BoxLoading } from 'tg.component/common/BoxLoading';
import { useApiMutation } from 'tg.service/http/useQueryApi';
import { binaryAssetApi } from './binaryAssetApi';
import { BinaryAssetPreview, previewKind } from './BinaryAssetPreview';
import { AssetTranscript } from './AssetTranscript';
import { TranscriptEditor } from './TranscriptEditor';
import { BinaryAssetTranslationStatus } from './types';

const statusColor = (status: BinaryAssetTranslationStatus) => {
  switch (status) {
    case 'CURRENT':
      return 'success';
    case 'OUTDATED':
      return 'warning';
    default:
      return 'default';
  }
};

export const AssetView = () => {
  const project = useProject();
  const match = useRouteMatch();
  const assetId = Number(match.params[PARAMS.ASSET_ID]);
  const { t } = useTranslate();
  const { satisfiesPermission, satisfiesLanguageAccess } =
    useProjectPermissions();
  const queryClient = useQueryClient();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [uploadLanguageId, setUploadLanguageId] = useState<
    number | 'source' | null
  >(null);
  const [error, setError] = useState<string | null>(null);
  const [generatingLanguageId, setGeneratingLanguageId] = useState<
    number | null
  >(null);

  const generateLanguage = useApiMutation({
    url: '/v2/projects/{projectId}/binary-assets/{assetId}/transcript/generate/{languageId}',
    method: 'post',
  });

  const canEdit = satisfiesPermission('keys.edit');
  const canTranslate = satisfiesPermission('translations.edit');
  const canDelete = satisfiesPermission('keys.delete');

  const detailQuery = useQuery(['binary-asset', project.id, assetId], () =>
    binaryAssetApi.get(project.id, assetId)
  );

  const invalidate = () => {
    queryClient.invalidateQueries(['binary-asset', project.id, assetId]);
    queryClient.invalidateQueries(['binary-assets', project.id]);
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

  const upsertTranslation = useMutation(
    (vars: { languageId: number; file: File; revision: number }) =>
      binaryAssetApi.upsertTranslation(
        project.id,
        assetId,
        vars.languageId,
        vars.file,
        vars.revision
      ),
    {
      onSuccess: () => {
        setError(null);
        invalidate();
      },
      onError: (e: any) => setError(e?.message || 'Upload failed'),
    }
  );

  const deleteTranslation = useMutation(
    (languageId: number) =>
      binaryAssetApi.deleteTranslation(project.id, assetId, languageId),
    { onSuccess: invalidate }
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

  const downloadTranslation = async (languageId: number) => {
    const ticket = await binaryAssetApi.translationTicket(
      project.id,
      assetId,
      languageId
    );
    window.location.href = ticket.url;
  };

  const onFileChosen = (file: File | null) => {
    if (!file || uploadLanguageId === null) return;
    if (uploadLanguageId === 'source') {
      replaceSource.mutate(file);
    } else {
      const revision = detailQuery.data?.sourceRevision ?? 1;
      upsertTranslation.mutate({
        languageId: uploadLanguageId,
        file,
        revision,
      });
    }
    setUploadLanguageId(null);
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  if (detailQuery.isLoading || !detailQuery.data) {
    return (
      <BaseProjectView windowTitle="Asset" title="Asset">
        <BoxLoading />
      </BaseProjectView>
    );
  }

  const asset = detailQuery.data;
  // transcripts only make sense for something spoken
  const hasTranscriptSupport = ['audio', 'video'].includes(
    previewKind(asset.contentType, asset.originalFilename)
  );

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
          {asset.originalFilename} · {asset.byteSize} bytes ·{' '}
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
              onClick={() => {
                setUploadLanguageId('source');
                fileInputRef.current?.click();
              }}
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

      {hasTranscriptSupport && (
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
      )}

      <Typography fontWeight={600} mb={1}>
        {t('binary_assets_translations', 'Localized files')}
      </Typography>
      <Table size="small" data-cy="binary-asset-translations">
        <TableHead>
          <TableRow>
            <TableCell>Language</TableCell>
            <TableCell>Status</TableCell>
            <TableCell>Preview</TableCell>
            <TableCell>File</TableCell>
            {hasTranscriptSupport && (
              <TableCell>
                {t('binary_assets_transcript', 'Transcript')}
              </TableCell>
            )}
            <TableCell align="right">Actions</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {(asset.translations ?? []).map((row) => (
            <TableRow key={row.languageId}>
              <TableCell>
                {row.languageName} ({row.languageTag})
              </TableCell>
              <TableCell>
                <Chip
                  size="small"
                  color={statusColor(row.status) as any}
                  label={row.status}
                />
              </TableCell>
              <TableCell sx={{ minWidth: 220 }}>
                {row.status !== 'MISSING' ? (
                  <BinaryAssetPreview
                    projectId={project.id}
                    assetId={asset.id}
                    languageId={row.languageId}
                    contentType={row.contentType}
                    filename={row.originalFilename}
                    compact
                  />
                ) : (
                  <Typography variant="caption" color="text.secondary">
                    —
                  </Typography>
                )}
              </TableCell>
              <TableCell>
                {row.originalFilename
                  ? `${row.originalFilename} · ${row.byteSize} B`
                  : '—'}
              </TableCell>
              {hasTranscriptSupport && (
                <TableCell
                  sx={{ maxWidth: 260, minWidth: 180 }}
                  data-cy="binary-asset-transcript-cell"
                >
                  {asset.transcriptKeyName ? (
                    <Box display="flex" alignItems="flex-start" gap={0.5}>
                      <Box flex={1} minWidth={0}>
                        <TranscriptEditor
                          projectId={project.id}
                          keyName={asset.transcriptKeyName}
                          languageTag={row.languageTag}
                          value={row.transcriptText}
                          canEdit={satisfiesLanguageAccess(
                            'translations.edit',
                            row.languageId
                          )}
                          placeholder={t(
                            'binary_assets_transcript_add_translation',
                            'Add translation'
                          )}
                          onSaved={invalidate}
                        />
                      </Box>
                      {row.transcriptionAvailable &&
                        satisfiesLanguageAccess(
                          'translations.edit',
                          row.languageId
                        ) && (
                          <Tooltip
                            title={t(
                              'binary_assets_transcript_generate_language',
                              "Transcribe this language's audio with AI"
                            )}
                          >
                            <span>
                              <IconButton
                                size="small"
                                disabled={
                                  generateLanguage.isLoading &&
                                  generatingLanguageId === row.languageId
                                }
                                onClick={() => {
                                  setGeneratingLanguageId(row.languageId);
                                  generateLanguage.mutate(
                                    {
                                      path: {
                                        projectId: project.id,
                                        assetId: asset.id,
                                        languageId: row.languageId,
                                      },
                                    },
                                    { onSuccess: invalidate }
                                  );
                                }}
                                data-cy="binary-asset-transcript-generate-language"
                              >
                                {generateLanguage.isLoading &&
                                generatingLanguageId === row.languageId ? (
                                  <CircularProgress size={16} />
                                ) : (
                                  <Stars01 width={16} height={16} />
                                )}
                              </IconButton>
                            </span>
                          </Tooltip>
                        )}
                    </Box>
                  ) : (
                    <Typography variant="caption" color="text.secondary">
                      —
                    </Typography>
                  )}
                </TableCell>
              )}
              <TableCell align="right">
                <Box
                  display="flex"
                  gap={1}
                  justifyContent="flex-end"
                  flexWrap="wrap"
                >
                  {row.status !== 'MISSING' && (
                    <Button
                      size="small"
                      onClick={() => downloadTranslation(row.languageId)}
                    >
                      Download
                    </Button>
                  )}
                  {canTranslate && (
                    <Button
                      size="small"
                      variant="outlined"
                      onClick={() => {
                        setUploadLanguageId(row.languageId);
                        fileInputRef.current?.click();
                      }}
                      data-cy="binary-asset-upload-translation"
                    >
                      {row.status === 'MISSING' ? 'Upload' : 'Replace'}
                    </Button>
                  )}
                  {canTranslate && row.status !== 'MISSING' && (
                    <Button
                      size="small"
                      color="error"
                      onClick={() => deleteTranslation.mutate(row.languageId)}
                    >
                      Delete
                    </Button>
                  )}
                </Box>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>

      <input
        ref={fileInputRef}
        type="file"
        hidden
        onChange={(e) => onFileChosen(e.target.files?.[0] ?? null)}
      />
    </BaseProjectView>
  );
};
