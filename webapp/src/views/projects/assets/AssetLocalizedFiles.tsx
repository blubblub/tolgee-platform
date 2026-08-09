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
  Microphone01,
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
import { confirmation } from 'tg.hooks/confirmation';
import { useProjectPermissions } from 'tg.hooks/useProjectPermissions';
import { ApiError } from 'tg.service/http/ApiError';
import {
  invalidateUrlPrefix,
  useApiMutation,
} from 'tg.service/http/useQueryApi';
import {
  binaryAssetApi,
  canRecordAudio,
  formatBytes,
  isAudioAsset,
  RunPayload,
  truncateMiddle,
  visibleTranslations,
} from './binaryAssetApi';
import { BinaryAssetPreview } from './BinaryAssetPreview';
import { FileDropTableCell } from './FileDropTableCell';
import { AssetSourceTranscript } from './AssetSourceTranscript';
import { TranscriptEditor } from './TranscriptEditor';
import { TranscriptAddInline } from './TranscriptAddInline';
import { BinaryAsset, BinaryAssetTranslationStatus } from './types';
import { RunToolDialog } from './RunToolDialog';
import { RecordAudioDialog } from './RecordAudioDialog';
import { useRunErrorText } from './useRunErrorText';

const statusColor = (
  status: BinaryAssetTranslationStatus,
  reviewed = false
) => {
  switch (status) {
    case 'CURRENT':
      return reviewed ? 'success' : 'warning';
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
  /** Name of the source language. When set, the source is listed first as a bolded row. */
  sourceLanguageName?: string;
};

/**
 * Per-language files of one asset with their edit actions. Shared by the asset detail page and the
 * assets list, so both offer the same editing without the list having to link away for every change.
 */
export const AssetLocalizedFiles = ({
  projectId,
  asset,
  languageTags,
  sourceLanguageName,
}: Props) => {
  const { t } = useTranslate();
  const { satisfiesPermission, satisfiesLanguageAccess } =
    useProjectPermissions();
  const queryClient = useQueryClient();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [uploadTarget, setUploadTarget] = useState<number | 'source' | null>(
    null
  );
  // which row's File cell spins for a drop-upload; null = idle (one at a time)
  const [dropUploading, setDropUploading] = useState<number | 'source' | null>(
    null
  );
  // which preview the record dialog serves; final=true stores a version and selects it
  const [recordTarget, setRecordTarget] = useState<{
    target: number | 'source';
    final: boolean;
  } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [transcribingLanguageIds, setTranscribingLanguageIds] = useState<
    number[]
  >([]);
  const [runLanguageId, setRunLanguageId] = useState<number | null>(null);
  const [recordingFinalLanguageId, setRecordingFinalLanguageId] = useState<
    number | null
  >(null);
  // AI runs can overlap; each Final cell stays pending through generation and final selection.
  const [regeneratingLanguageIds, setRegeneratingLanguageIds] = useState<
    number[]
  >([]);
  const runErrorText = useRunErrorText();

  // a page of assets would otherwise ask for a download ticket per language before anyone scrolls
  const { ref: inViewRef, inView } = useInView({
    rootMargin: '300px',
    triggerOnce: true,
  });

  const canTranslate = satisfiesPermission('translations.edit');
  const canReview = (languageId: number) =>
    satisfiesLanguageAccess('translations.state-edit', languageId);
  const reviewLabel = (reviewed?: boolean) =>
    reviewed
      ? t('binary_assets_reviewed_undo', 'Confirmed — click to reopen')
      : t('binary_assets_reviewed_confirm', 'Confirm this final file');
  const canEditSource = satisfiesPermission('keys.edit');
  const canCreateTranscript = satisfiesPermission('keys.create');
  // recording produces an audio take, so only offer it where an audio file would land
  const recordable =
    canRecordAudio() && isAudioAsset(asset.contentType, asset.originalFilename);

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

  const addTranscript = useApiMutation({
    url: '/v2/projects/{projectId}/binary-assets/{assetId}/transcript',
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

  const chooseFinal = useApiMutation({
    url: '/v2/projects/{projectId}/binary-assets/{assetId}/translations/{languageId}/versions/chosen-version',
    method: 'put',
  });

  const uploadVersion = useApiMutation({
    url: '/v2/projects/{projectId}/binary-assets/{assetId}/translations/{languageId}/versions',
    method: 'post',
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

  const confirmDeleteTranslation = (languageId: number, filename: string) =>
    confirmation({
      title: t('binary_assets_delete', 'Delete'),
      message: t(
        'binary_assets_delete_translation_message',
        'Delete {filename} and its version history? This cannot be undone.',
        { filename }
      ),
      confirmButtonText: t('confirmation_dialog_delete', 'Delete'),
      onConfirm: () => deleteTranslation.mutate(languageId),
    });

  const replaceSource = useMutation(
    (file: File) => binaryAssetApi.replaceSource(projectId, asset.id, file),
    {
      onSuccess: () => {
        setError(null);
        invalidate();
      },
      onError: (e: any) => setError(e?.message || 'Upload failed'),
    }
  );

  const onFileChosen = (file: File | null) => {
    if (file && uploadTarget === 'source') {
      replaceSource.mutate(file);
    } else if (file && typeof uploadTarget === 'number') {
      upsertTranslation.mutate({ languageId: uploadTarget, file });
    }
    setUploadTarget(null);
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  const uploadFile = async (target: number | 'source', file: File) => {
    // dropped or recorded — same mutations as the upload button, plus a spinner in the File cell
    setDropUploading(target);
    try {
      if (target === 'source') {
        await replaceSource.mutateAsync(file);
      } else {
        await upsertTranslation.mutateAsync({ languageId: target, file });
      }
    } finally {
      setDropUploading(null);
    }
  };

  const uploadFinalVersion = async (languageId: number, file: File) => {
    setRecordingFinalLanguageId(languageId);
    setError(null);
    try {
      const version = await uploadVersion.mutateAsync({
        path: { projectId, assetId: asset.id, languageId },
        content: { 'multipart/form-data': { file: file as any } },
      });
      try {
        await chooseFinal.mutateAsync({
          path: { projectId, assetId: asset.id, languageId },
          content: { 'application/json': { versionId: version.id } },
        });
      } catch {
        setError(
          t(
            'binary_assets_set_final_failed',
            'The new version finished, but setting it as the final file failed — pick it on the pipeline page.'
          )
        );
      }
      invalidate();
    } catch (e: any) {
      setError(e?.message || 'Upload failed');
      throw e;
    } finally {
      setRecordingFinalLanguageId(null);
    }
  };

  const generateTranscript = async (languageId: number) => {
    setTranscribingLanguageIds((ids) => [...ids, languageId]);
    try {
      await generateLanguage.mutateAsync({
        path: { projectId, assetId: asset.id, languageId },
      });
      invalidate();
    } catch (e) {
      // Per-call React Query callbacks only survive for the newest concurrent mutation.
      (e as ApiError).handleError?.();
    } finally {
      setTranscribingLanguageIds((ids) =>
        ids.filter((id) => id !== languageId)
      );
    }
  };

  const generateFinal = async (languageId: number, payload: RunPayload) => {
    setRegeneratingLanguageIds((ids) => [...ids, languageId]);
    setError(null);
    try {
      const version = await binaryAssetApi.runTool(
        projectId,
        asset.id,
        languageId,
        payload
      );
      try {
        await chooseFinal.mutateAsync({
          path: { projectId, assetId: asset.id, languageId },
          content: { 'application/json': { versionId: version.id } },
        });
      } catch {
        setError(
          t(
            'binary_assets_set_final_failed',
            'The new version finished, but setting it as the final file failed — pick it on the pipeline page.'
          )
        );
      }
      queryClient.invalidateQueries([
        'binary-asset-versions',
        projectId,
        asset.id,
        languageId,
      ]);
      invalidate();
    } catch (e: any) {
      setError(runErrorText(e?.code));
    } finally {
      setRegeneratingLanguageIds((ids) =>
        ids.filter((id) => id !== languageId)
      );
    }
  };

  const transcribeButton = (languageId: number, available?: boolean) => {
    const transcribing = transcribingLanguageIds.includes(languageId);
    return (
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
              disabled={transcribing}
              onClick={() => void generateTranscript(languageId)}
              data-cy="binary-asset-transcript-generate-language"
            >
              {transcribing ? (
                <CircularProgress size={16} />
              ) : (
                <Stars01 width={16} height={16} />
              )}
            </IconButton>
          </span>
        </Tooltip>
      )
    );
  };

  const recordButton = (
    target: number | 'source',
    final = false,
    disabled = false
  ) => {
    const label = final
      ? t('binary_assets_record_final', 'Record a new final version')
      : t('binary_assets_record_audio', 'Record audio');
    return (
      <Tooltip title={label}>
        <span>
          <IconButton
            size="small"
            aria-label={label}
            disabled={disabled}
            onClick={() => setRecordTarget({ target, final })}
            data-cy={
              final
                ? 'binary-asset-final-record-audio'
                : 'binary-asset-preview-record-audio'
            }
          >
            <Microphone01 width={16} height={16} />
          </IconButton>
        </span>
      </Tooltip>
    );
  };

  return (
    <Box ref={inViewRef}>
      {error && (
        <Typography color="error" variant="body2" role="alert" sx={{ mb: 1 }}>
          {error}
        </Typography>
      )}

      {rows.length === 0 && !sourceLanguageName ? (
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
        <Box sx={{ overflowX: 'auto', maxWidth: '100%', minWidth: 0 }}>
          <Table
            size="small"
            data-cy="binary-asset-translations"
            // every column hugs its content; the transcript takes the slack
            sx={{ '& td, & th': { whiteSpace: 'nowrap' } }}
          >
            <TableHead>
              <TableRow>
                <TableCell>Language</TableCell>
                <TableCell>Status</TableCell>
                <TableCell>Preview</TableCell>
                <TableCell>File</TableCell>
                {/* any asset may carry a transcript; only AI transcription is speech-gated */}
                <TableCell sx={{ width: '100%' }}>
                  {t('binary_assets_transcript', 'Transcript')}
                </TableCell>
                <TableCell>{t('binary_assets_final', 'Final')}</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {sourceLanguageName && (
                <TableRow data-cy="binary-asset-source-row">
                  <TableCell component="th" scope="row">
                    <Typography variant="body2" fontWeight={700}>
                      {sourceLanguageName} ({asset.sourceLanguageTag})
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Chip
                      size="small"
                      variant="outlined"
                      label={t('binary_assets_source_badge', 'ORIGINAL')}
                    />
                  </TableCell>
                  <TableCell>
                    <Box display="flex" alignItems="center" gap={0.5}>
                      <BinaryAssetPreview
                        projectId={projectId}
                        assetId={asset.id}
                        contentType={asset.contentType}
                        filename={asset.originalFilename}
                        enabled={inView}
                        compact
                      />
                      {canEditSource && recordable && recordButton('source')}
                    </Box>
                  </TableCell>
                  <FileDropTableCell
                    active={canEditSource && dropUploading === null}
                    onFile={(file) => {
                      void uploadFile('source', file).catch(() => undefined);
                    }}
                  >
                    {dropUploading === 'source' ? (
                      <Box
                        display="flex"
                        alignItems="center"
                        py={0.5}
                        data-cy="binary-asset-file-uploading"
                      >
                        <CircularProgress size={18} />
                      </Box>
                    ) : (
                      <Box display="flex" alignItems="center" gap={0.5}>
                        <Tooltip title={asset.originalFilename}>
                          <Typography variant="body2" fontWeight={700}>
                            {truncateMiddle(asset.originalFilename)} ·{' '}
                            {formatBytes(asset.byteSize)}
                          </Typography>
                        </Tooltip>
                      </Box>
                    )}
                  </FileDropTableCell>
                  <TableCell
                    sx={{
                      minWidth: 200,
                      whiteSpace: 'normal !important',
                    }}
                  >
                    <AssetSourceTranscript
                      projectId={projectId}
                      asset={asset}
                    />
                  </TableCell>
                  <TableCell>
                    <Typography variant="caption" color="text.secondary">
                      —
                    </Typography>
                  </TableCell>
                  <TableCell align="right">
                    <Box display="flex" gap={1} justifyContent="flex-end">
                      {canEditSource && (
                        <Tooltip
                          title={t(
                            'binary_assets_replace_source',
                            'Replace source'
                          )}
                        >
                          <IconButton
                            size="small"
                            onClick={() => {
                              setUploadTarget('source');
                              fileInputRef.current?.click();
                            }}
                            data-cy="binary-asset-replace-source"
                          >
                            <UploadCloud02 width={16} height={16} />
                          </IconButton>
                        </Tooltip>
                      )}
                    </Box>
                  </TableCell>
                </TableRow>
              )}
              {rows.map((row) => (
                <TableRow key={row.languageId}>
                  <TableCell component="th" scope="row">
                    <Tooltip
                      title={t('asset_translation_pipeline', 'Pipeline')}
                    >
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
                      color={statusColor(row.status, row.reviewed) as any}
                      sx={{ textTransform: 'uppercase' }}
                      label={
                        row.status === 'CURRENT'
                          ? row.reviewed
                            ? t('translation_state_reviewed', 'Reviewed')
                            : t(
                                'binary_assets_status_needs_review',
                                'Needs Review'
                              )
                          : row.status
                      }
                      data-cy="binary-asset-status"
                    />
                  </TableCell>
                  <TableCell>
                    <Box display="flex" alignItems="center" gap={0.5}>
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
                      {satisfiesLanguageAccess(
                        'translations.edit',
                        row.languageId
                      ) &&
                        recordable &&
                        recordButton(row.languageId)}
                    </Box>
                  </TableCell>
                  <FileDropTableCell
                    active={canTranslate && dropUploading === null}
                    onFile={(file) => {
                      void uploadFile(row.languageId, file).catch(
                        () => undefined
                      );
                    }}
                  >
                    {dropUploading === row.languageId ? (
                      <Box
                        display="flex"
                        alignItems="center"
                        py={0.5}
                        data-cy="binary-asset-file-uploading"
                      >
                        <CircularProgress size={18} />
                      </Box>
                    ) : (
                      <Box display="flex" alignItems="center" gap={0.5}>
                        {row.originalFilename ? (
                          <Tooltip title={row.originalFilename}>
                            <Typography variant="body2">
                              {truncateMiddle(row.originalFilename)} ·{' '}
                              {formatBytes(row.byteSize ?? 0)}
                            </Typography>
                          </Tooltip>
                        ) : (
                          '—'
                        )}
                      </Box>
                    )}
                  </FileDropTableCell>
                  <TableCell
                    // the editor needs room to wrap, unlike every other column
                    sx={{
                      minWidth: 200,
                      whiteSpace: 'normal !important',
                    }}
                    data-cy="binary-asset-transcript-cell"
                  >
                    <Box display="flex" alignItems="flex-start" gap={0.5}>
                      <Box flex={1} minWidth={0}>
                        {asset.transcriptKeyName ? (
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
                        ) : canCreateTranscript ? (
                          // no key yet — typing here creates one seeded in this language,
                          // whether or not the language has a file uploaded
                          <TranscriptAddInline
                            creating={addTranscript.isLoading}
                            placeholderDataCy="binary-asset-language-transcript-placeholder"
                            inputDataCy="binary-asset-language-transcript-input"
                            placeholder={t(
                              'binary_assets_transcript_add_translation',
                              'Add translation'
                            )}
                            onCreate={(text) =>
                              addTranscript.mutate(
                                {
                                  path: { projectId, assetId: asset.id },
                                  content: {
                                    'application/json': {
                                      text,
                                      languageTag: row.languageTag,
                                    },
                                  },
                                },
                                { onSuccess: invalidate }
                              )
                            }
                          />
                        ) : (
                          <Typography variant="caption" color="text.secondary">
                            —
                          </Typography>
                        )}
                      </Box>
                      {transcribeButton(
                        row.languageId,
                        row.transcriptionAvailable
                      )}
                    </Box>
                  </TableCell>
                  <TableCell data-cy="binary-asset-final-cell">
                    {recordingFinalLanguageId === row.languageId ||
                    regeneratingLanguageIds.includes(row.languageId) ? (
                      <Box
                        display="flex"
                        alignItems="center"
                        py={0.5}
                        data-cy="binary-asset-regenerating"
                      >
                        <CircularProgress size={18} />
                      </Box>
                    ) : row.status === 'MISSING' ? (
                      <Typography variant="caption" color="text.secondary">
                        —
                      </Typography>
                    ) : (
                      <Box display="flex" alignItems="center" gap={0.5}>
                        {/* a chosen pipeline/uploaded version, else the OG upload */}
                        <BinaryAssetPreview
                          projectId={projectId}
                          assetId={asset.id}
                          languageId={row.languageId}
                          versionId={row.chosenVersionId}
                          contentType={
                            row.chosenVersionId ? undefined : row.contentType
                          }
                          filename={
                            row.chosenVersionFilename ?? row.originalFilename
                          }
                          enabled={inView}
                          compact
                        />
                        {recordable &&
                          satisfiesLanguageAccess(
                            'translations.edit',
                            row.languageId
                          ) &&
                          canReview(row.languageId) &&
                          recordButton(
                            row.languageId,
                            true,
                            recordingFinalLanguageId !== null ||
                              regeneratingLanguageIds.length > 0
                          )}
                      </Box>
                    )}
                  </TableCell>
                  <TableCell align="right">
                    <Box display="flex" gap={1} justifyContent="flex-end">
                      {/* nothing to confirm until a file exists */}
                      {canReview(row.languageId) &&
                        row.status === 'CURRENT' && (
                          <Tooltip title={reviewLabel(row.reviewed)}>
                            <span>
                              <IconButton
                                size="small"
                                color={row.reviewed ? 'success' : 'default'}
                                disabled={setReviewed.isLoading}
                                aria-label={reviewLabel(row.reviewed)}
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
                          <span>
                            <IconButton
                              size="small"
                              color="primary"
                              disabled={
                                recordingFinalLanguageId !== null ||
                                regeneratingLanguageIds.includes(row.languageId)
                              }
                              onClick={() => setRunLanguageId(row.languageId)}
                              data-cy="binary-asset-run-tool"
                            >
                              {regeneratingLanguageIds.includes(
                                row.languageId
                              ) ? (
                                <CircularProgress size={16} />
                              ) : (
                                <Zap width={16} height={16} />
                              )}
                            </IconButton>
                          </span>
                        </Tooltip>
                      )}
                      {canTranslate && (
                        <Tooltip
                          title={
                            row.status === 'MISSING'
                              ? t('binary_assets_upload_translation', 'Upload')
                              : t(
                                  'binary_assets_replace_translation',
                                  'Replace'
                                )
                          }
                        >
                          <IconButton
                            size="small"
                            onClick={() => {
                              setUploadTarget(row.languageId);
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
                              confirmDeleteTranslation(
                                row.languageId,
                                row.originalFilename ?? row.languageName
                              )
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
        </Box>
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
          isLoading={false}
          onClose={() => setRunLanguageId(null)}
          onSubmit={(payload) => {
            // close right away — progress shows in the row, not behind a modal
            const languageId = runLanguageId;
            setRunLanguageId(null);
            void generateFinal(languageId, payload);
          }}
        />
      )}

      <RecordAudioDialog
        open={recordTarget !== null}
        useAsFinal={recordTarget?.final}
        onClose={() => setRecordTarget(null)}
        onUse={async (file) => {
          const record = recordTarget;
          if (record?.final && typeof record.target === 'number') {
            await uploadFinalVersion(record.target, file);
          } else if (record) {
            await uploadFile(record.target, file);
          }
        }}
      />
    </Box>
  );
};
