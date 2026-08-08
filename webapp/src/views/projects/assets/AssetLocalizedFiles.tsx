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
  /**
   * Name of the source language. When set, the source is listed first as a bolded row — the detail
   * page presents it separately instead, so it leaves this out.
   */
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
  const [error, setError] = useState<string | null>(null);
  const [generatingLanguageId, setGeneratingLanguageId] = useState<
    number | null
  >(null);
  const [runLanguageId, setRunLanguageId] = useState<number | null>(null);
  // from submit until the new version is chosen + refreshed — the Final cell spins meanwhile
  const [regeneratingLanguageId, setRegeneratingLanguageId] = useState<
    number | null
  >(null);
  // react-query v3 snapshots the mutation callbacks when mutate() runs — before the submit-time
  // state updates land — so the success handler must read the language through a ref, not state
  const regeneratingRef = useRef<number | null>(null);
  const runErrorText = useRunErrorText();

  // a page of assets would otherwise ask for a download ticket per language before anyone scrolls
  const { ref: inViewRef, inView } = useInView({
    rootMargin: '300px',
    triggerOnce: true,
  });

  const canTranslate = satisfiesPermission('translations.edit');
  const canReview = satisfiesPermission('translations.state-edit');
  const canEditSource = satisfiesPermission('keys.edit');
  const canCreateTranscript = satisfiesPermission('keys.create');

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

  const runTool = useRunTool({
    projectId,
    assetId: asset.id,
    // the dialog only opens with a language selected; 0 is never submitted
    languageId: runLanguageId ?? 0,
    onSuccess: (version) => {
      setRunLanguageId(null);
      setError(null);
      // a regeneration from the table is meant to become the final right away
      chooseFinal.mutate(
        {
          path: {
            projectId,
            assetId: asset.id,
            languageId: regeneratingRef.current ?? 0,
          },
          content: { 'application/json': { versionId: version.id } },
        },
        {
          onSuccess: () => {
            invalidate();
            regeneratingRef.current = null;
            setRegeneratingLanguageId(null);
          },
          onError: () => {
            invalidate();
            regeneratingRef.current = null;
            setRegeneratingLanguageId(null);
            setError(
              t(
                'binary_assets_set_final_failed',
                'The new version finished, but setting it as the final file failed — pick it on the pipeline page.'
              )
            );
          },
        }
      );
    },
    onError: (code) => {
      regeneratingRef.current = null;
      setRegeneratingLanguageId(null);
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

  const onFileDropped = (target: number | 'source', file: File) => {
    // same mutations as the upload button, plus a spinner in that row's File cell
    setDropUploading(target);
    const onSettled = () => setDropUploading(null);
    if (target === 'source') {
      replaceSource.mutate(file, { onSettled });
    } else {
      upsertTranslation.mutate({ languageId: target, file }, { onSettled });
    }
  };

  const generateTranscript = (languageId: number) => {
    setGeneratingLanguageId(languageId);
    generateLanguage.mutate(
      { path: { projectId, assetId: asset.id, languageId } },
      { onSuccess: invalidate }
    );
  };

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
                  <TableCell>
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
                    <BinaryAssetPreview
                      projectId={projectId}
                      assetId={asset.id}
                      contentType={asset.contentType}
                      filename={asset.originalFilename}
                      enabled={inView}
                      compact
                    />
                  </TableCell>
                  <FileDropTableCell
                    active={canEditSource && dropUploading === null}
                    onFile={(file) => onFileDropped('source', file)}
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
                      <Tooltip title={asset.originalFilename}>
                        <Typography variant="body2" fontWeight={700}>
                          {truncateMiddle(asset.originalFilename)} ·{' '}
                          {formatBytes(asset.byteSize)}
                        </Typography>
                      </Tooltip>
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
                  <TableCell>
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
                  <FileDropTableCell
                    active={canTranslate && dropUploading === null}
                    onFile={(file) => onFileDropped(row.languageId, file)}
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
                    ) : row.originalFilename ? (
                      <Tooltip title={row.originalFilename}>
                        <Typography variant="body2">
                          {truncateMiddle(row.originalFilename)} ·{' '}
                          {formatBytes(row.byteSize ?? 0)}
                        </Typography>
                      </Tooltip>
                    ) : (
                      '—'
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
                    {regeneratingLanguageId === row.languageId ? (
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
                      /* the final file itself — a chosen pipeline version, else the upload */
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
                          <span>
                            <IconButton
                              size="small"
                              color="primary"
                              // one run at a time keeps the chosen-final chain unambiguous
                              disabled={regeneratingLanguageId !== null}
                              onClick={() => setRunLanguageId(row.languageId)}
                              data-cy="binary-asset-run-tool"
                            >
                              {regeneratingLanguageId === row.languageId ? (
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
          isLoading={runTool.isLoading}
          onClose={() => setRunLanguageId(null)}
          onSubmit={(payload) => {
            // close right away — progress shows in the row, not behind a modal
            regeneratingRef.current = runLanguageId;
            setRegeneratingLanguageId(runLanguageId);
            setRunLanguageId(null);
            runTool.mutate(payload);
          }}
        />
      )}
    </Box>
  );
};
