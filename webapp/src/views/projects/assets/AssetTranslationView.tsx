import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  AlertTitle,
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
import {
  ArrowLeft,
  CheckCircle,
  Download01,
  Flag01,
  Microphone01,
  Stars01,
  Trash01,
  UploadCloud02,
  Zap,
} from '@untitled-ui/icons-react';
import { T, useTranslate } from '@tolgee/react';
import { useMutation, useQuery, useQueryClient } from 'react-query';
import { Link as RouterLink, useRouteMatch } from 'react-router-dom';

import { BaseProjectView } from 'tg.views/projects/BaseProjectView';
import { useProject } from 'tg.hooks/useProject';
import { useProjectPermissions } from 'tg.hooks/useProjectPermissions';
import { useProjectLanguages } from 'tg.hooks/useProjectLanguages';
import { ProjectLanguagesProvider } from 'tg.hooks/ProjectLanguagesProvider';
import { LINKS, PARAMS } from 'tg.constants/links';
import { BoxLoading } from 'tg.component/common/BoxLoading';
import { FlagImage } from '@tginternal/library/components/languages/FlagImage';
import { confirmation } from 'tg.hooks/confirmation';
import { useMessageService } from 'tg.globalContext/useMessageService';
import { ApiError } from 'tg.service/http/ApiError';
import { useApiMutation } from 'tg.service/http/useQueryApi';
import {
  binaryAssetApi,
  BinaryAssetTranslationVersionModel,
  BinaryAssetTranslationWithVersions,
  canRecordAudio,
  formatBytes,
  isAudioAsset,
  RunPayload,
  TOOL_LABELS,
} from './binaryAssetApi';
import { BinaryAssetPreview } from './BinaryAssetPreview';
import { BinaryAssetTranslation } from './types';
import { RunToolDialog } from './RunToolDialog';
import { RecordAudioDialog } from './RecordAudioDialog';
import { FileDropZone } from './FileDropZone';
import { FileDropTableCell } from './FileDropTableCell';
import { TranscriptEditor } from './TranscriptEditor';
import { TranscriptAddInline } from './TranscriptAddInline';
import { useRunErrorText } from './useRunErrorText';
import { useRunTool } from './useRunTool';

const formatDate = (s: string) => new Date(s).toLocaleString();

const parseToolParams = (
  raw: string | null
): { voiceId?: string; modelId?: string; removeBackgroundNoise?: boolean } => {
  if (!raw) return {};
  try {
    const parsed = JSON.parse(raw) as Record<string, unknown>;
    return {
      voiceId: typeof parsed.voiceId === 'string' ? parsed.voiceId : undefined,
      modelId: typeof parsed.modelId === 'string' ? parsed.modelId : undefined,
      removeBackgroundNoise: parsed.removeBackgroundNoise === true,
    };
  } catch {
    return {};
  }
};

/** Tooltip-wrapped icon button — the same action style as the assets table rows. */
const IconAction = ({
  label,
  icon,
  onClick,
  disabled,
  color = 'default',
  dataCy,
}: {
  label: string;
  icon: React.ReactNode;
  onClick?: () => void;
  disabled?: boolean;
  color?: 'default' | 'primary' | 'error' | 'success';
  dataCy: string;
}) => (
  <Tooltip title={label}>
    <span>
      <IconButton
        size="small"
        color={color}
        disabled={disabled}
        aria-label={label}
        onClick={onClick}
        data-cy={dataCy}
      >
        {icon}
      </IconButton>
    </span>
  </Tooltip>
);

const AssetTranslationContent = () => {
  const project = useProject();
  const match = useRouteMatch();
  const assetId = Number(match.params[PARAMS.ASSET_ID]);
  const languageId = Number(match.params[PARAMS.LANGUAGE_ID]);
  const { t } = useTranslate();
  const { actions } = useMessageService();
  const queryClient = useQueryClient();
  const languages = useProjectLanguages();
  const language = useMemo(
    () => languages.find((l) => l.id === languageId),
    [languages, languageId]
  );
  const { satisfiesPermission, satisfiesLanguageAccess } =
    useProjectPermissions();

  const [runDialogOpen, setRunDialogOpen] = useState(false);
  const [failedRun, setFailedRun] = useState<{
    payload: RunPayload;
    code?: string;
  } | null>(null);
  const [transcribing, setTranscribing] = useState(false);
  const [uploadingOg, setUploadingOg] = useState(false);
  const [uploadingVersion, setUploadingVersion] = useState(false);
  const [recordOpen, setRecordOpen] = useState(false);
  const ogInputRef = useRef<HTMLInputElement>(null);
  const versionInputRef = useRef<HTMLInputElement>(null);
  const runErrorText = useRunErrorText();

  const canView = satisfiesPermission('translations.view');
  const canEdit = satisfiesLanguageAccess('translations.edit', languageId);
  const canChooseFinal = satisfiesPermission('translations.state-edit');
  const canCreateTranscript = satisfiesPermission('keys.create');
  const canReview = satisfiesLanguageAccess(
    'translations.state-edit',
    languageId
  );

  const assetQuery = useQuery(
    ['binary-asset', project.id, assetId],
    () => binaryAssetApi.get(project.id, assetId),
    { enabled: canView }
  );

  const versionsQuery = useQuery(
    ['binary-asset-versions', project.id, assetId, languageId],
    () => binaryAssetApi.listVersions(project.id, assetId, languageId),
    { enabled: canView }
  );

  const translation = useMemo(() => {
    const tr = assetQuery.data?.translations?.find(
      (row) => row.languageId === languageId
    );
    return tr as unknown as BinaryAssetTranslationWithVersions | undefined;
  }, [assetQuery.data, languageId]);

  /**
   * The asset's source language has no translation row — its original is the asset's own file, and
   * its versions hang off the asset. Everything else on this page works the same.
   */
  const isSource = assetQuery.data?.sourceLanguageId === languageId;

  const invalidate = () => {
    queryClient.invalidateQueries(['binary-asset', project.id, assetId]);
    queryClient.invalidateQueries([
      'binary-asset-versions',
      project.id,
      assetId,
      languageId,
    ]);
    queryClient.invalidateQueries([
      '/v2/projects/{projectId}/binary-assets',
      { projectId: project.id },
    ]);
  };

  const setChosenVersion = useMutation(
    (versionId: number | null) =>
      binaryAssetApi.setChosenVersion(
        project.id,
        assetId,
        languageId,
        versionId === null ? { versionId: null } : { versionId }
      ),
    {
      onSuccess: () => {
        invalidate();
        actions.showMessage({
          text: t('asset_translation_final_set', 'Final version updated.'),
          variant: 'success',
        });
      },
      onError: () => {
        actions.showMessage({
          text: t(
            'asset_translation_final_set_failed',
            'Failed to set final version.'
          ),
          variant: 'error',
        });
      },
    }
  );

  const deleteVersion = useMutation(
    (versionId: number) =>
      binaryAssetApi.deleteVersion(project.id, assetId, languageId, versionId),
    {
      onSuccess: () => {
        invalidate();
        actions.showMessage({
          text: t('asset_translation_version_deleted', 'Version deleted.'),
          variant: 'success',
        });
      },
      onError: () => {
        actions.showMessage({
          text: t(
            'asset_translation_version_delete_failed',
            'Failed to delete version.'
          ),
          variant: 'error',
        });
      },
    }
  );

  const runTool = useRunTool({
    projectId: project.id,
    assetId,
    languageId,
    onSuccess: () => {
      setRunDialogOpen(false);
      setFailedRun(null);
      actions.showMessage({
        text: t(
          'asset_translation_tool_started',
          'Tool finished successfully.'
        ),
        variant: 'success',
      });
    },
    onError: (code, payload) => {
      // keep it on the page — a toast is gone before you can read it
      setFailedRun({ payload, code });
      setRunDialogOpen(false);
      actions.showMessage({ text: runErrorText(code), variant: 'error' });
    },
  });

  const replaceOg = useMutation(
    (file: File) =>
      isSource
        ? binaryAssetApi.replaceSource(project.id, assetId, file)
        : binaryAssetApi.upsertTranslation(
            project.id,
            assetId,
            languageId,
            file,
            // the button only renders once the asset has loaded
            assetQuery.data?.sourceRevision ?? 0
          ),
    {
      onSuccess: invalidate,
      onError: (e: any) =>
        actions.showMessage({
          text: e?.message || 'Upload failed',
          variant: 'error',
        }),
      onSettled: () => setUploadingOg(false),
    }
  );

  const uploadVersion = useApiMutation({
    url: '/v2/projects/{projectId}/binary-assets/{assetId}/translations/{languageId}/versions',
    method: 'post',
  });

  const uploadVersionFile = async (file: File) => {
    setUploadingVersion(true);
    try {
      await uploadVersion.mutateAsync({
        path: { projectId: project.id, assetId, languageId },
        content: { 'multipart/form-data': { file: file as any } },
      });
      invalidate();
    } catch (e) {
      (e as ApiError).handleError?.();
    } finally {
      setUploadingVersion(false);
    }
  };

  const setReviewed = useApiMutation({
    url: '/v2/projects/{projectId}/binary-assets/{assetId}/translations/{languageId}/reviewed',
    method: 'put',
  });

  const toggleReviewed = (reviewed: boolean) =>
    setReviewed.mutate(
      {
        path: { projectId: project.id, assetId, languageId },
        content: { 'application/json': { reviewed } },
      },
      { onSuccess: invalidate }
    );

  const generateTranscript = useApiMutation({
    url: '/v2/projects/{projectId}/binary-assets/{assetId}/transcript/generate/{languageId}',
    method: 'post',
  });

  const generateSourceTranscript = useApiMutation({
    url: '/v2/projects/{projectId}/binary-assets/{assetId}/transcript/generate',
    method: 'post',
  });

  const addTranscript = useApiMutation({
    url: '/v2/projects/{projectId}/binary-assets/{assetId}/transcript',
    method: 'post',
  });

  const transcribe = async () => {
    setTranscribing(true);
    try {
      if (isSource) {
        await generateSourceTranscript.mutateAsync({
          path: { projectId: project.id, assetId },
        });
      } else {
        await generateTranscript.mutateAsync({
          path: { projectId: project.id, assetId, languageId },
        });
      }
      invalidate();
    } catch (e) {
      // Per-call React Query callbacks only survive for the newest concurrent mutation.
      (e as ApiError).handleError?.();
    } finally {
      setTranscribing(false);
    }
  };

  const deleteTranslation = useMutation(
    () => binaryAssetApi.deleteTranslation(project.id, assetId, languageId),
    { onSuccess: invalidate }
  );

  const confirmDeleteTranslation = () =>
    confirmation({
      title: t('binary_assets_delete', 'Delete'),
      message: t(
        'binary_assets_delete_translation_message',
        'Delete {filename} and its version history? This cannot be undone.',
        {
          filename:
            translation?.originalFilename ??
            language?.tag ??
            String(languageId),
        }
      ),
      confirmButtonText: t('confirmation_dialog_delete', 'Delete'),
      onConfirm: () => deleteTranslation.mutate(),
    });

  const downloadVersion = async (versionId: number) => {
    const ticket = await binaryAssetApi.versionTicket(
      project.id,
      assetId,
      languageId,
      versionId
    );
    window.location.href = ticket.url;
  };

  const downloadSource = async () => {
    const ticket = await binaryAssetApi.sourceTicket(project.id, assetId);
    window.location.href = ticket.url;
  };

  const downloadTranslation = async () => {
    const ticket = await binaryAssetApi.translationTicket(
      project.id,
      assetId,
      languageId
    );
    window.location.href = ticket.url;
  };

  const handleDelete = (version: BinaryAssetTranslationVersionModel) => {
    confirmation({
      title: (
        <T
          keyName="asset_translation_delete_title"
          defaultValue="Delete version"
        />
      ),
      message: (
        <T
          keyName="asset_translation_delete_message"
          defaultValue="Delete {filename}? This cannot be undone."
          params={{ filename: version.originalFilename }}
        />
      ),
      confirmButtonText: (
        <T keyName="asset_translation_delete_confirm" defaultValue="Delete" />
      ),
      onConfirm: () => deleteVersion.mutate(version.id),
    });
  };

  if (assetQuery.isLoading || versionsQuery.isLoading || !assetQuery.data) {
    return (
      <BaseProjectView
        windowTitle={t('asset_translation_title', 'Asset translation')}
        title={t('asset_translation_title', 'Asset translation')}
      >
        <BoxLoading />
      </BaseProjectView>
    );
  }

  const asset = assetQuery.data;
  // replacing the source file is a keys.edit action, unlike replacing a language's file
  const canEditOg = isSource ? satisfiesPermission('keys.edit') : canEdit;
  const versions = versionsQuery.data ?? [];
  const chosenVersionId = isSource
    ? asset.chosenVersionId ?? null
    : translation?.chosenVersionId;
  // an asset may have no original at all; a translation may not be uploaded yet
  const ogMissing = isSource
    ? !asset.originalFilename
    : translation?.status === 'MISSING';
  const recordable =
    canEditOg &&
    canRecordAudio() &&
    (!asset.contentType ||
      isAudioAsset(asset.contentType, asset.originalFilename));

  const statusChip = isSource ? (
    <Chip
      size="small"
      variant="outlined"
      label={t('binary_assets_source_badge', 'ORIGINAL')}
      data-cy="binary-asset-status"
    />
  ) : !translation ? null : translation.status === 'CURRENT' ? (
    <Chip
      size="small"
      color={translation.reviewed ? 'success' : 'warning'}
      sx={{ textTransform: 'uppercase' }}
      label={
        translation.reviewed
          ? t('translation_state_reviewed', 'Reviewed')
          : t('binary_assets_status_needs_review', 'Needs Review')
      }
      data-cy="binary-asset-status"
    />
  ) : (
    <Chip
      size="small"
      sx={{ textTransform: 'uppercase' }}
      label={translation.status}
      data-cy="binary-asset-status"
    />
  );

  const finalLabel = t('asset_translation_final_label', 'Final');
  const setFinalLabel = t('asset_translation_set_final', 'Set as final');

  return (
    <BaseProjectView
      windowTitle={t('asset_translation_title', 'Asset translation')}
      title={t('asset_translation_title', 'Asset translation')}
      navigation={[
        [
          t('binary_assets_title', 'Assets'),
          LINKS.PROJECT_ASSETS.build({ [PARAMS.PROJECT_ID]: project.id }),
        ],
        [
          asset.name,
          LINKS.PROJECT_ASSET.build({
            [PARAMS.PROJECT_ID]: project.id,
            [PARAMS.ASSET_ID]: assetId,
          }),
        ],
        [
          language?.tag ?? String(languageId),
          LINKS.PROJECT_ASSET_TRANSLATION.build({
            [PARAMS.PROJECT_ID]: project.id,
            [PARAMS.ASSET_ID]: assetId,
            [PARAMS.LANGUAGE_ID]: languageId,
          }),
        ],
      ]}
    >
      <Box display="flex" alignItems="center" gap={1} mb={2}>
        <Button
          component={RouterLink}
          to={LINKS.PROJECT_ASSET.build({
            [PARAMS.PROJECT_ID]: project.id,
            [PARAMS.ASSET_ID]: assetId,
          })}
          startIcon={<ArrowLeft width={18} height={18} />}
          size="small"
          data-cy="asset-translation-back"
        >
          {t('asset_translation_back', 'Back to asset')}
        </Button>
      </Box>

      <Box mb={2}>
        <Typography variant="h6" fontWeight={600}>
          {asset.name}
        </Typography>
        <Box
          display="flex"
          alignItems="center"
          gap={1}
          mt={0.5}
          flexWrap="wrap"
        >
          {language?.flagEmoji && (
            <FlagImage flagEmoji={language.flagEmoji} height={18} />
          )}
          <Typography variant="body1">
            {language?.name ?? languageId} ({language?.tag ?? languageId})
          </Typography>
          {statusChip}
          {canReview && translation?.status === 'CURRENT' && (
            <IconAction
              label={
                translation.reviewed
                  ? t(
                      'binary_assets_reviewed_undo',
                      'Confirmed — click to reopen'
                    )
                  : t(
                      'binary_assets_reviewed_confirm',
                      'Confirm this final file'
                    )
              }
              icon={<CheckCircle width={16} height={16} />}
              color={translation.reviewed ? 'success' : 'default'}
              disabled={setReviewed.isLoading}
              onClick={() => toggleReviewed(!translation.reviewed)}
              dataCy="binary-asset-review-toggle"
            />
          )}
        </Box>
      </Box>

      {/* on the source language's own page this card would just repeat the OG row below */}
      {!isSource && (
        <Box
          mb={3}
          p={2}
          border={1}
          borderColor="divider"
          borderRadius={1}
          data-cy="asset-translation-source-card"
        >
          <Box
            display="flex"
            justifyContent="space-between"
            alignItems="flex-start"
            flexWrap="wrap"
            gap={1}
            mb={1}
          >
            <Box>
              <Typography fontWeight={600}>
                {t('asset_translation_source', 'Source ({tag})', {
                  tag: asset.sourceLanguageTag,
                })}
              </Typography>
              <Typography variant="body2" color="text.secondary">
                {asset.originalFilename} · {formatBytes(asset.byteSize)}
              </Typography>
            </Box>
            <IconAction
              label={t('asset_translation_download', 'Download')}
              icon={<Download01 width={16} height={16} />}
              onClick={downloadSource}
              dataCy="asset-translation-source-download"
            />
          </Box>
          <BinaryAssetPreview
            projectId={project.id}
            assetId={asset.id}
            languageId={null}
            contentType={asset.contentType}
            filename={asset.originalFilename}
          />
        </Box>
      )}

      {failedRun && (
        <Alert
          severity="error"
          sx={{ mb: 2 }}
          onClose={() => setFailedRun(null)}
          action={
            canEdit && (
              <Button
                size="small"
                color="inherit"
                disabled={runTool.isLoading}
                onClick={() => runTool.mutate(failedRun.payload)}
                data-cy="asset-version-run-retry"
              >
                {runTool.isLoading ? (
                  <CircularProgress size={16} />
                ) : (
                  t('asset_translation_retry', 'Retry')
                )}
              </Button>
            )
          }
          data-cy="asset-version-run-error"
        >
          <AlertTitle>
            {t('asset_translation_run_failed_title', '{tool} failed', {
              tool: TOOL_LABELS[failedRun.payload.tool],
            })}
          </AlertTitle>
          {runErrorText(failedRun.code)}
        </Alert>
      )}

      <Box display="flex" alignItems="center" gap={1} mb={1}>
        <Typography fontWeight={600}>
          {t('asset_translation_versions', 'Versions')}
        </Typography>
        {canEdit && (
          <IconAction
            label={t('asset_translation_upload_version', 'Upload a version')}
            icon={<UploadCloud02 width={16} height={16} />}
            onClick={() => versionInputRef.current?.click()}
            dataCy="asset-translation-version-upload"
          />
        )}
      </Box>

      {canEdit && (
        <FileDropZone
          active={!uploadingVersion}
          onFile={(file) => void uploadVersionFile(file)}
          dataCy="asset-translation-version-drop"
          sx={{
            mb: 2,
            p: 2,
            border: '1px dashed',
            borderColor: 'divider',
            borderRadius: 1,
            display: 'flex',
            alignItems: 'center',
            gap: 1,
            color: 'text.secondary',
          }}
        >
          {uploadingVersion ? (
            <CircularProgress size={18} data-cy="binary-asset-file-uploading" />
          ) : (
            <UploadCloud02 width={16} height={16} />
          )}
          <Typography variant="body2">
            {t(
              'asset_translation_drop_version',
              'Drop a file to add a version'
            )}
          </Typography>
        </FileDropZone>
      )}

      <Box sx={{ overflowX: 'auto', maxWidth: '100%', minWidth: 0 }}>
        <Table
          size="small"
          data-cy="asset-translation-table"
          // every column hugs its content; the transcript takes the slack
          sx={{ '& td, & th': { whiteSpace: 'nowrap' } }}
        >
          <TableHead>
            <TableRow>
              <TableCell>{finalLabel}</TableCell>
              <TableCell>File</TableCell>
              <TableCell>Preview</TableCell>
              <TableCell sx={{ width: '100%' }}>
                {t('binary_assets_transcript', 'Transcript')}
              </TableCell>
              <TableCell align="right">Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {/* the OG upload is the first candidate for final, versions follow */}
            <TableRow data-cy="asset-translation-og-row">
              <TableCell>
                {canChooseFinal && (
                  <IconAction
                    label={
                      chosenVersionId === null ? finalLabel : setFinalLabel
                    }
                    icon={<Flag01 width={16} height={16} />}
                    color={chosenVersionId === null ? 'primary' : 'default'}
                    disabled={setChosenVersion.isLoading}
                    onClick={() => setChosenVersion.mutate(null)}
                    dataCy="asset-translation-choose-original"
                  />
                )}
              </TableCell>
              <FileDropTableCell
                active={canEditOg && !uploadingOg}
                onFile={(file) => {
                  setUploadingOg(true);
                  replaceOg.mutate(file);
                }}
              >
                <Box display="flex" alignItems="center" gap={1}>
                  <Typography variant="body2" fontWeight={700}>
                    {t('asset_translation_original', 'Original upload')}
                  </Typography>
                  {chosenVersionId === null && (
                    <Chip
                      size="small"
                      color="primary"
                      label={t('asset_translation_final_badge', 'Final')}
                      data-cy="asset-translation-og-final-badge"
                    />
                  )}
                </Box>
                <Typography variant="caption" color="text.secondary">
                  {ogMissing ? (
                    t('asset_translation_not_uploaded', 'Not uploaded')
                  ) : (
                    <>
                      {(translation as BinaryAssetTranslation | undefined)
                        ?.originalFilename ?? asset.originalFilename}{' '}
                      ·{' '}
                      {formatBytes(
                        (translation as BinaryAssetTranslation | undefined)
                          ?.byteSize ?? asset.byteSize
                      )}{' '}
                      ·{' '}
                      {(isSource ? asset.updatedAt : translation?.updatedAt)
                        ? formatDate(
                            (isSource
                              ? asset.updatedAt
                              : translation?.updatedAt)!
                          )
                        : t('asset_translation_not_uploaded', 'Not uploaded')}
                    </>
                  )}
                </Typography>
              </FileDropTableCell>
              <TableCell>
                {ogMissing ? (
                  <Typography variant="caption" color="text.secondary">
                    —
                  </Typography>
                ) : (
                  <BinaryAssetPreview
                    projectId={project.id}
                    assetId={asset.id}
                    // the source file has no translation row to ticket through
                    languageId={isSource ? null : languageId}
                    contentType={
                      (translation as BinaryAssetTranslation | undefined)
                        ?.contentType ?? asset.contentType
                    }
                    filename={
                      (translation as BinaryAssetTranslation | undefined)
                        ?.originalFilename ?? asset.originalFilename
                    }
                    compact
                  />
                )}
              </TableCell>
              <TableCell
                // the editor needs room to wrap, unlike every other column
                sx={{ minWidth: 200, whiteSpace: 'normal !important' }}
                data-cy="binary-asset-transcript-cell"
              >
                <Box display="flex" alignItems="flex-start" gap={0.5}>
                  <Box flex={1} minWidth={0}>
                    {asset.transcriptKeyName ? (
                      <TranscriptEditor
                        projectId={project.id}
                        keyName={asset.transcriptKeyName}
                        languageTag={language?.tag ?? String(languageId)}
                        value={
                          isSource
                            ? asset.transcriptSourceText
                            : translation?.transcriptText
                        }
                        canEdit={canEdit}
                        placeholder={t(
                          'binary_assets_transcript_add_translation',
                          'Add translation'
                        )}
                        onSaved={invalidate}
                      />
                    ) : canCreateTranscript ? (
                      // no key yet — typing here creates one seeded in this language
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
                              path: { projectId: project.id, assetId },
                              content: {
                                'application/json': {
                                  text,
                                  languageTag: language?.tag,
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
                  {canEdit &&
                    (isSource
                      ? asset.transcriptionAvailable
                      : translation?.transcriptionAvailable) && (
                      <IconAction
                        label={t(
                          'binary_assets_transcript_generate_language',
                          "Transcribe this language's audio with AI"
                        )}
                        icon={
                          transcribing ? (
                            <CircularProgress size={16} />
                          ) : (
                            <Stars01 width={16} height={16} />
                          )
                        }
                        disabled={transcribing}
                        onClick={() => void transcribe()}
                        dataCy="binary-asset-transcript-generate-language"
                      />
                    )}
                </Box>
              </TableCell>
              <TableCell align="right">
                {uploadingOg ? (
                  <Box py={0.5} data-cy="binary-asset-file-uploading">
                    <CircularProgress size={18} />
                  </Box>
                ) : (
                  <Box display="flex" gap={1} justifyContent="flex-end">
                    <IconAction
                      label={t('asset_translation_download', 'Download')}
                      icon={<Download01 width={16} height={16} />}
                      disabled={ogMissing}
                      onClick={isSource ? downloadSource : downloadTranslation}
                      dataCy="asset-translation-og-download"
                    />
                    {canEdit && !ogMissing && (
                      <IconAction
                        label={t('asset_translation_run_tool', 'Run tool')}
                        icon={<Zap width={16} height={16} />}
                        color="primary"
                        disabled={runTool.isLoading}
                        onClick={() => setRunDialogOpen(true)}
                        dataCy="asset-version-run-tool"
                      />
                    )}
                    {recordable && (
                      <IconAction
                        label={t('binary_assets_record_audio', 'Record audio')}
                        icon={<Microphone01 width={16} height={16} />}
                        onClick={() => setRecordOpen(true)}
                        dataCy="asset-translation-og-record"
                      />
                    )}
                    {canEditOg && (
                      <IconAction
                        label={
                          ogMissing
                            ? t('binary_assets_upload_translation', 'Upload')
                            : t('binary_assets_replace_translation', 'Replace')
                        }
                        icon={<UploadCloud02 width={16} height={16} />}
                        onClick={() => ogInputRef.current?.click()}
                        dataCy="asset-translation-og-upload"
                      />
                    )}
                    {canEdit && !isSource && !ogMissing && (
                      <IconAction
                        label={t('binary_assets_delete', 'Delete')}
                        icon={<Trash01 width={16} height={16} />}
                        color="error"
                        onClick={confirmDeleteTranslation}
                        dataCy="binary-asset-delete-translation"
                      />
                    )}
                  </Box>
                )}
              </TableCell>
            </TableRow>
            {versions.map((version) => {
              const params = parseToolParams(version.toolParams);
              const isChosen = chosenVersionId === version.id;
              return (
                <TableRow key={version.id} data-cy="asset-version-row">
                  <TableCell>
                    {canChooseFinal && (
                      <IconAction
                        label={isChosen ? finalLabel : setFinalLabel}
                        icon={<Flag01 width={16} height={16} />}
                        color={isChosen ? 'primary' : 'default'}
                        disabled={setChosenVersion.isLoading}
                        onClick={() => setChosenVersion.mutate(version.id)}
                        dataCy="asset-version-choose-final"
                      />
                    )}
                  </TableCell>
                  <TableCell>
                    <Box display="flex" alignItems="center" gap={1}>
                      <Typography variant="body2">
                        {version.originalFilename}
                      </Typography>
                      {isChosen && (
                        <Chip
                          size="small"
                          color="primary"
                          label={t('asset_translation_final_badge', 'Final')}
                          data-cy="asset-version-final-badge"
                        />
                      )}
                    </Box>
                    <Typography variant="caption" color="text.secondary">
                      {version.tool}
                      {params.voiceId && ` · ${params.voiceId}`}
                      {params.modelId && ` · ${params.modelId}`}
                      {params.removeBackgroundNoise &&
                        ` · ${t(
                          'asset_translation_remove_background_noise',
                          'Remove background noise'
                        )}`}
                    </Typography>
                    <Typography
                      variant="caption"
                      color="text.secondary"
                      display="block"
                    >
                      {formatBytes(version.byteSize)} ·{' '}
                      {formatDate(version.createdAt)}
                      {version.createdById != null &&
                        ` · #${version.createdById}`}
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <VersionPreview
                      projectId={project.id}
                      assetId={asset.id}
                      languageId={languageId}
                      versionId={version.id}
                      contentType={version.contentType}
                      filename={version.originalFilename}
                    />
                  </TableCell>
                  <TableCell>
                    <Typography variant="caption" color="text.secondary">
                      —
                    </Typography>
                  </TableCell>
                  <TableCell align="right">
                    <Box display="flex" gap={1} justifyContent="flex-end">
                      <IconAction
                        label={t('asset_translation_download', 'Download')}
                        icon={<Download01 width={16} height={16} />}
                        onClick={() => downloadVersion(version.id)}
                        dataCy="asset-version-download"
                      />
                      {canEdit && (
                        <IconAction
                          label={t('asset_translation_delete', 'Delete')}
                          icon={<Trash01 width={16} height={16} />}
                          color="error"
                          onClick={() => handleDelete(version)}
                          dataCy="asset-version-delete"
                        />
                      )}
                    </Box>
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </Box>

      {versions.length === 0 && (
        <Typography
          color="text.secondary"
          sx={{ mt: 1 }}
          data-cy="asset-translation-no-versions"
        >
          {t(
            'asset_translation_no_versions',
            'No versions yet. Run a tool to create one.'
          )}
        </Typography>
      )}

      <input
        ref={ogInputRef}
        type="file"
        hidden
        onChange={(e) => {
          const file = e.target.files?.[0];
          if (file) {
            setUploadingOg(true);
            replaceOg.mutate(file);
          }
          e.target.value = '';
        }}
      />
      <input
        ref={versionInputRef}
        type="file"
        hidden
        onChange={(e) => {
          const file = e.target.files?.[0];
          if (file) {
            void uploadVersionFile(file);
          }
          e.target.value = '';
        }}
      />

      <RunToolDialog
        projectId={project.id}
        assetId={assetId}
        languageId={languageId}
        open={runDialogOpen}
        isLoading={runTool.isLoading}
        onClose={() => setRunDialogOpen(false)}
        onSubmit={(payload) => runTool.mutate(payload)}
      />

      <RecordAudioDialog
        open={recordOpen}
        onClose={() => setRecordOpen(false)}
        onUse={async (file) => {
          setUploadingOg(true);
          await replaceOg.mutateAsync(file);
        }}
      />
    </BaseProjectView>
  );
};

const VersionPreview = ({
  projectId,
  assetId,
  languageId,
  versionId,
  contentType,
  filename,
}: {
  projectId: number;
  assetId: number;
  languageId: number;
  versionId: number;
  contentType: string;
  filename: string;
}) => {
  const { t } = useTranslate();
  const [src, setSrc] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    binaryAssetApi
      .versionTicket(projectId, assetId, languageId, versionId)
      .then((ticket) => {
        if (!cancelled) {
          const u = ticket.url.includes('?')
            ? `${ticket.url}&inline=true`
            : `${ticket.url}?inline=true`;
          setSrc(u);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [projectId, assetId, languageId, versionId]);

  if (!src) return null;

  const kind = contentType.startsWith('audio/')
    ? 'audio'
    : contentType.startsWith('video/')
    ? 'video'
    : contentType.startsWith('image/')
    ? 'image'
    : 'unknown';

  if (kind === 'audio') {
    return (
      <Box
        component="audio"
        controls
        preload="metadata"
        src={src}
        sx={{ width: '100%', maxWidth: 480, height: 36 }}
        data-cy="asset-version-preview-audio"
      />
    );
  }
  if (kind === 'video') {
    return (
      <Box
        component="video"
        controls
        preload="metadata"
        src={src}
        sx={{ width: '100%', maxWidth: 480, maxHeight: 320, borderRadius: 1 }}
        data-cy="asset-version-preview-video"
      />
    );
  }
  if (kind === 'image') {
    return (
      <Box
        component="img"
        src={src}
        alt={filename}
        sx={{
          maxWidth: 360,
          maxHeight: 280,
          objectFit: 'contain',
          borderRadius: 1,
        }}
        data-cy="asset-version-preview-image"
      />
    );
  }
  return (
    <Typography variant="caption" color="text.secondary">
      {t('asset_translation_preview_unsupported', 'No inline preview')}
    </Typography>
  );
};

export const AssetTranslationView = () => (
  <ProjectLanguagesProvider>
    <AssetTranslationContent />
  </ProjectLanguagesProvider>
);
