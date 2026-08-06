import { useMemo, useRef, useState } from 'react';
import {
  Box,
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
import {
  CheckCircle,
  Stars01,
  Trash01,
  UploadCloud02,
  Zap,
} from '@untitled-ui/icons-react';
import { useTranslate } from '@tolgee/react';
import { useMutation, useQueryClient } from 'react-query';
import { Link as RouterLink } from 'react-router-dom';
import { useInView } from 'react-intersection-observer';

import { LINKS, PARAMS } from 'tg.constants/links';
import { useProjectPermissions } from 'tg.hooks/useProjectPermissions';
import {
  invalidateUrlPrefix,
  useApiMutation,
} from 'tg.service/http/useQueryApi';
import {
  binaryAssetApi,
  formatBytes,
  visibleTranslations,
} from './binaryAssetApi';
import { BinaryAssetPreview, previewKind } from './BinaryAssetPreview';
import { TranscriptEditor } from './TranscriptEditor';
import { BinaryAsset, BinaryAssetTranslationStatus } from './types';
import { RunToolDialog } from './RunToolDialog';
import { useRunErrorText } from './useRunErrorText';
import { useRunTool } from './useRunTool';

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

type Props = {
  projectId: number;
  asset: BinaryAsset;
  /** Tags to show; empty or undefined means every target language. */
  languageTags?: string[];
};

/**
 * Per-language files of one asset with their edit actions. Shared by the asset detail page and the
 * assets list, so both offer the same editing without the list having to link away for every change.
 */
export const AssetLocalizedFiles = ({
  projectId,
  asset,
  languageTags,
}: Props) => {
  const { t } = useTranslate();
  const { satisfiesPermission, satisfiesLanguageAccess } =
    useProjectPermissions();
  const queryClient = useQueryClient();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [uploadLanguageId, setUploadLanguageId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [generatingLanguageId, setGeneratingLanguageId] = useState<
    number | null
  >(null);
  const [runLanguageId, setRunLanguageId] = useState<number | null>(null);
  const runErrorText = useRunErrorText();

  // a page of assets would otherwise ask for a download ticket per language before anyone scrolls
  const { ref: inViewRef, inView } = useInView({
    rootMargin: '300px',
    triggerOnce: true,
  });

  const canTranslate = satisfiesPermission('translations.edit');
  const canReview = satisfiesPermission('translations.state-edit');

  const rows = useMemo(
    () => visibleTranslations(asset, languageTags),
    [asset.translations, languageTags]
  );

  const invalidate = () => {
    queryClient.invalidateQueries(['binary-asset', projectId, asset.id]);
    invalidateUrlPrefix(queryClient, '/v2/projects/{projectId}/binary-assets');
  };

  const generateLanguage = useApiMutation({
    url: '/v2/projects/{projectId}/binary-assets/{assetId}/transcript/generate/{languageId}',
    method: 'post',
  });

  const setReviewed = useApiMutation({
    url: '/v2/projects/{projectId}/binary-assets/{assetId}/translations/{languageId}/reviewed',
    method: 'put',
  });

  const toggleReviewed = (languageId: number, reviewed: boolean) =>
    setReviewed.mutate(
      {
        path: { projectId, assetId: asset.id, languageId },
        content: { 'application/json': { reviewed } },
      },
      { onSuccess: invalidate }
    );

  const runTool = useRunTool({
    projectId,
    assetId: asset.id,
    // the dialog only opens with a language selected; 0 is never submitted
    languageId: runLanguageId ?? 0,
    onSuccess: () => {
      setRunLanguageId(null);
      setError(null);
    },
    onError: (code) => {
      setRunLanguageId(null);
      setError(runErrorText(code));
    },
  });

  const upsertTranslation = useMutation(
    (vars: { languageId: number; file: File }) =>
      binaryAssetApi.upsertTranslation(
        projectId,
        asset.id,
        vars.languageId,
        vars.file,
        asset.sourceRevision
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
      binaryAssetApi.deleteTranslation(projectId, asset.id, languageId),
    { onSuccess: invalidate }
  );

  const onFileChosen = (file: File | null) => {
    if (file && uploadLanguageId !== null) {
      upsertTranslation.mutate({ languageId: uploadLanguageId, file });
    }
    setUploadLanguageId(null);
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  const generateTranscript = (languageId: number) => {
    setGeneratingLanguageId(languageId);
    generateLanguage.mutate(
      { path: { projectId, assetId: asset.id, languageId } },
      { onSuccess: invalidate }
    );
  };

  // transcripts only make sense for something spoken
  const hasTranscriptSupport = ['audio', 'video'].includes(
    previewKind(asset.contentType, asset.originalFilename)
  );

  const transcribeButton = (languageId: number, available?: boolean) =>
    available &&
    satisfiesLanguageAccess('translations.edit', languageId) && (
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
              generateLanguage.isLoading && generatingLanguageId === languageId
            }
            onClick={() => generateTranscript(languageId)}
            data-cy="binary-asset-transcript-generate-language"
          >
            {generateLanguage.isLoading &&
            generatingLanguageId === languageId ? (
              <CircularProgress size={16} />
            ) : (
              <Stars01 width={16} height={16} />
            )}
          </IconButton>
        </span>
      </Tooltip>
    );

  return (
    <Box ref={inViewRef}>
      {error && (
        <Typography color="error" variant="body2" sx={{ mb: 1 }}>
          {error}
        </Typography>
      )}

      {rows.length === 0 ? (
        <Typography
          variant="body2"
          color="text.secondary"
          data-cy="binary-asset-translations-empty"
        >
          {t(
            'binary_assets_no_languages_selected',
            'No localized files for the selected languages.'
          )}
        </Typography>
      ) : (
        <Table
          size="small"
          data-cy="binary-asset-translations"
          // every column hugs its content; File takes the slack so nothing else wraps
          sx={{ '& td, & th': { whiteSpace: 'nowrap' } }}
        >
          <TableHead>
            <TableRow>
              <TableCell>Language</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Preview</TableCell>
              <TableCell sx={{ width: '100%' }}>File</TableCell>
              {hasTranscriptSupport && (
                <TableCell>
                  {t('binary_assets_transcript', 'Transcript')}
                </TableCell>
              )}
              <TableCell>{t('binary_assets_final', 'Final')}</TableCell>
              <TableCell align="right">Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.map((row) => (
              <TableRow key={row.languageId}>
                <TableCell>
                  <Tooltip title={t('asset_translation_pipeline', 'Pipeline')}>
                    <Typography
                      variant="body2"
                      component={RouterLink}
                      to={LINKS.PROJECT_ASSET_TRANSLATION.build({
                        [PARAMS.PROJECT_ID]: projectId,
                        [PARAMS.ASSET_ID]: asset.id,
                        [PARAMS.LANGUAGE_ID]: row.languageId,
                      })}
                      sx={{
                        color: 'inherit',
                        textDecoration: 'none',
                        '&:hover': { textDecoration: 'underline' },
                      }}
                      data-cy="binary-asset-translation-pipeline"
                    >
                      {row.languageName} ({row.languageTag})
                    </Typography>
                  </Tooltip>
                </TableCell>
                <TableCell>
                  <Chip
                    size="small"
                    color={statusColor(row.status) as any}
                    label={row.status}
                  />
                </TableCell>
                <TableCell>
                  {row.status !== 'MISSING' ? (
                    <BinaryAssetPreview
                      projectId={projectId}
                      assetId={asset.id}
                      languageId={row.languageId}
                      contentType={row.contentType}
                      filename={row.originalFilename}
                      enabled={inView}
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
                    ? `${row.originalFilename} · ${formatBytes(
                        row.byteSize ?? 0
                      )}`
                    : '—'}
                </TableCell>
                {hasTranscriptSupport && (
                  <TableCell
                    // the editor needs room to wrap, unlike every other column
                    sx={{
                      maxWidth: 260,
                      minWidth: 180,
                      whiteSpace: 'normal !important',
                    }}
                    data-cy="binary-asset-transcript-cell"
                  >
                    <Box display="flex" alignItems="flex-start" gap={0.5}>
                      {asset.transcriptKeyName ? (
                        <Box flex={1} minWidth={0}>
                          <TranscriptEditor
                            projectId={projectId}
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
                      ) : (
                        <Typography variant="caption" color="text.secondary">
                          —
                        </Typography>
                      )}
                      {transcribeButton(
                        row.languageId,
                        row.transcriptionAvailable
                      )}
                    </Box>
                  </TableCell>
                )}
                <TableCell data-cy="binary-asset-final-cell">
                  {row.status === 'MISSING' ? (
                    <Typography variant="caption" color="text.secondary">
                      —
                    </Typography>
                  ) : (
                    <Typography
                      variant="body2"
                      component={RouterLink}
                      to={LINKS.PROJECT_ASSET_TRANSLATION.build({
                        [PARAMS.PROJECT_ID]: projectId,
                        [PARAMS.ASSET_ID]: asset.id,
                        [PARAMS.LANGUAGE_ID]: row.languageId,
                      })}
                      sx={{
                        color: 'inherit',
                        textDecoration: 'none',
                        '&:hover': { textDecoration: 'underline' },
                      }}
                    >
                      {/* no chosen version means the uploaded original is final */}
                      {row.chosenVersionFilename ??
                        t('binary_assets_final_original', 'Original')}
                      {row.versionCount
                        ? ` · ${t(
                            'binary_assets_final_versions',
                            '{count, plural, one {# version} other {# versions}}',
                            {
                              count: row.versionCount,
                            }
                          )}`
                        : ''}
                    </Typography>
                  )}
                </TableCell>
                <TableCell align="right">
                  <Box display="flex" gap={1} justifyContent="flex-end">
                    {/* nothing to confirm until a file exists */}
                    {canReview && row.status !== 'MISSING' && (
                      <Tooltip
                        title={
                          row.reviewed
                            ? t(
                                'binary_assets_reviewed_undo',
                                'Confirmed — click to reopen'
                              )
                            : t(
                                'binary_assets_reviewed_confirm',
                                'Confirm this final file'
                              )
                        }
                      >
                        <span>
                          <IconButton
                            size="small"
                            color={row.reviewed ? 'success' : 'default'}
                            disabled={setReviewed.isLoading}
                            onClick={() =>
                              toggleReviewed(row.languageId, !row.reviewed)
                            }
                            data-cy="binary-asset-review-toggle"
                          >
                            <CheckCircle width={16} height={16} />
                          </IconButton>
                        </span>
                      </Tooltip>
                    )}
                    {/* a run reads the uploaded file, so there must be one */}
                    {canTranslate && row.status !== 'MISSING' && (
                      <Tooltip
                        title={t(
                          'binary_assets_generate_audio',
                          'Generate with AI (pipeline)'
                        )}
                      >
                        <IconButton
                          size="small"
                          color="primary"
                          onClick={() => setRunLanguageId(row.languageId)}
                          data-cy="binary-asset-run-tool"
                        >
                          <Zap width={16} height={16} />
                        </IconButton>
                      </Tooltip>
                    )}
                    {canTranslate && (
                      <Tooltip
                        title={
                          row.status === 'MISSING'
                            ? t('binary_assets_upload_translation', 'Upload')
                            : t('binary_assets_replace_translation', 'Replace')
                        }
                      >
                        <IconButton
                          size="small"
                          onClick={() => {
                            setUploadLanguageId(row.languageId);
                            fileInputRef.current?.click();
                          }}
                          data-cy="binary-asset-upload-translation"
                        >
                          <UploadCloud02 width={16} height={16} />
                        </IconButton>
                      </Tooltip>
                    )}
                    {canTranslate && row.status !== 'MISSING' && (
                      <Tooltip title={t('binary_assets_delete', 'Delete')}>
                        <IconButton
                          size="small"
                          color="error"
                          onClick={() =>
                            deleteTranslation.mutate(row.languageId)
                          }
                          data-cy="binary-asset-delete-translation"
                        >
                          <Trash01 width={16} height={16} />
                        </IconButton>
                      </Tooltip>
                    )}
                  </Box>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}

      <input
        ref={fileInputRef}
        type="file"
        hidden
        onChange={(e) => onFileChosen(e.target.files?.[0] ?? null)}
      />

      {runLanguageId !== null && (
        <RunToolDialog
          projectId={projectId}
          assetId={asset.id}
          languageId={runLanguageId}
          open
          isLoading={runTool.isLoading}
          onClose={() => setRunLanguageId(null)}
          onSubmit={(payload) => runTool.mutate(payload)}
        />
      )}
    </Box>
  );
};
