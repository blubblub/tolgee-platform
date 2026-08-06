import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  AlertTitle,
  Box,
  Button,
  Chip,
  CircularProgress,
  FormControlLabel,
  Radio,
  Typography,
} from '@mui/material';
import { ArrowLeft, Download01, Trash01 } from '@untitled-ui/icons-react';
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
import {
  binaryAssetApi,
  BinaryAssetTranslationVersionModel,
  BinaryAssetTranslationWithVersions,
  formatBytes,
  RunPayload,
  TOOL_LABELS,
} from './binaryAssetApi';
import { BinaryAssetPreview } from './BinaryAssetPreview';
import { BinaryAssetTranslation } from './types';
import { RunToolDialog } from './RunToolDialog';
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
  const runErrorText = useRunErrorText();

  const canView = satisfiesPermission('translations.view');
  const canEdit = satisfiesLanguageAccess('translations.edit', languageId);
  const canChooseFinal = satisfiesPermission('translations.state-edit');

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
  const versions = versionsQuery.data ?? [];
  const chosenVersionId = translation?.chosenVersionId;

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
        <Box display="flex" alignItems="center" gap={1} mt={0.5}>
          {language?.flagEmoji && (
            <FlagImage flagEmoji={language.flagEmoji} height={18} />
          )}
          <Typography variant="body1">
            {language?.name ?? languageId} ({language?.tag ?? languageId})
          </Typography>
          {translation?.status === 'OUTDATED' && (
            <Chip
              size="small"
              color="warning"
              label={t('asset_translation_outdated', 'Outdated')}
              data-cy="asset-translation-outdated-badge"
            />
          )}
        </Box>
      </Box>

      <Box
        mb={3}
        p={2}
        border={1}
        borderColor="divider"
        borderRadius={1}
        data-cy="asset-translation-source-card"
      >
        <Typography fontWeight={600} mb={1}>
          {t('asset_translation_source', 'Source ({tag})', {
            tag: asset.sourceLanguageTag,
          })}
        </Typography>
        <Typography variant="body2" color="text.secondary" mb={1}>
          {asset.originalFilename} · {formatBytes(asset.byteSize)}
        </Typography>
        <Box mb={1.5}>
          <BinaryAssetPreview
            projectId={project.id}
            assetId={asset.id}
            languageId={null}
            contentType={asset.contentType}
            filename={asset.originalFilename}
          />
        </Box>
        <Button
          size="small"
          startIcon={<Download01 width={16} height={16} />}
          onClick={downloadSource}
          data-cy="asset-translation-source-download"
        >
          {t('asset_translation_download', 'Download')}
        </Button>
      </Box>

      <Box
        mb={3}
        p={2}
        border={1}
        borderColor="divider"
        borderRadius={1}
        data-cy="asset-translation-og-card"
      >
        <Box
          display="flex"
          justifyContent="space-between"
          alignItems="flex-start"
          flexWrap="wrap"
          gap={1}
          mb={1}
        >
          <Typography fontWeight={600}>
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
        <Typography variant="body2" color="text.secondary" mb={1}>
          {(translation as BinaryAssetTranslation | undefined)
            ?.originalFilename ?? asset.originalFilename}{' '}
          ·{' '}
          {formatBytes(
            (translation as BinaryAssetTranslation | undefined)?.byteSize ??
              asset.byteSize
          )}{' '}
          ·{' '}
          {translation?.updatedAt
            ? formatDate(translation.updatedAt)
            : t('asset_translation_not_uploaded', 'Not uploaded')}
        </Typography>
        <Box mb={1.5}>
          <BinaryAssetPreview
            projectId={project.id}
            assetId={asset.id}
            languageId={languageId}
            contentType={
              (translation as BinaryAssetTranslation | undefined)?.contentType
            }
            filename={
              (translation as BinaryAssetTranslation | undefined)
                ?.originalFilename
            }
          />
        </Box>
        <Box
          display="flex"
          justifyContent="space-between"
          alignItems="center"
          flexWrap="wrap"
          gap={1}
        >
          <Box display="flex" gap={1} flexWrap="wrap">
            <Button
              size="small"
              startIcon={<Download01 width={16} height={16} />}
              onClick={downloadTranslation}
              disabled={translation?.status === 'MISSING'}
              data-cy="asset-translation-og-download"
            >
              {t('asset_translation_download', 'Download')}
            </Button>
            {canEdit && (
              <Button
                size="small"
                variant="outlined"
                onClick={() => setRunDialogOpen(true)}
                data-cy="asset-version-run-tool"
              >
                {t('asset_translation_run_tool', 'Run tool')}
              </Button>
            )}
          </Box>
          {canChooseFinal && (
            <FormControlLabel
              control={
                <Radio
                  checked={chosenVersionId === null}
                  onChange={() => setChosenVersion.mutate(null)}
                  data-cy="asset-translation-choose-original"
                />
              }
              label={t('asset_translation_final_label', 'Final')}
            />
          )}
        </Box>
      </Box>

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

      <Typography fontWeight={600} mb={1}>
        {t('asset_translation_versions', 'Versions')}
      </Typography>

      {versions.length === 0 ? (
        <Typography
          color="text.secondary"
          data-cy="asset-translation-no-versions"
        >
          {t(
            'asset_translation_no_versions',
            'No versions yet. Run a tool to create one.'
          )}
        </Typography>
      ) : (
        <Box display="flex" flexDirection="column" gap={1}>
          {versions.map((version) => {
            const params = parseToolParams(version.toolParams);
            const isChosen = chosenVersionId === version.id;
            return (
              <Box
                key={version.id}
                p={2}
                border={1}
                borderColor="divider"
                borderRadius={1}
                data-cy="asset-version-row"
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
                      {version.originalFilename}
                    </Typography>
                    <Typography variant="body2" color="text.secondary">
                      {version.tool}
                      {params.voiceId && ` · ${params.voiceId}`}
                      {params.modelId && ` · ${params.modelId}`}
                      {params.removeBackgroundNoise &&
                        ` · ${t(
                          'asset_translation_remove_background_noise',
                          'Remove background noise'
                        )}`}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      {formatBytes(version.byteSize)} ·{' '}
                      {formatDate(version.createdAt)}
                      {version.createdById != null &&
                        ` · #${version.createdById}`}
                    </Typography>
                  </Box>
                  {isChosen && (
                    <Chip
                      size="small"
                      color="primary"
                      label={t('asset_translation_final_badge', 'Final')}
                      data-cy="asset-version-final-badge"
                    />
                  )}
                </Box>
                <Box mb={1.5}>
                  <VersionPreview
                    projectId={project.id}
                    assetId={asset.id}
                    languageId={languageId}
                    versionId={version.id}
                    contentType={version.contentType}
                    filename={version.originalFilename}
                  />
                </Box>
                <Box
                  display="flex"
                  justifyContent="space-between"
                  alignItems="center"
                  flexWrap="wrap"
                  gap={1}
                >
                  <Box display="flex" gap={1}>
                    <Button
                      size="small"
                      startIcon={<Download01 width={16} height={16} />}
                      onClick={() => downloadVersion(version.id)}
                      data-cy="asset-version-download"
                    >
                      {t('asset_translation_download', 'Download')}
                    </Button>
                    {canEdit && (
                      <Button
                        size="small"
                        color="error"
                        startIcon={<Trash01 width={16} height={16} />}
                        onClick={() => handleDelete(version)}
                        data-cy="asset-version-delete"
                      >
                        {t('asset_translation_delete', 'Delete')}
                      </Button>
                    )}
                  </Box>
                  {canChooseFinal && (
                    <FormControlLabel
                      control={
                        <Radio
                          checked={isChosen}
                          onChange={() => setChosenVersion.mutate(version.id)}
                          data-cy="asset-version-choose-final"
                        />
                      }
                      label={t('asset_translation_final_label', 'Final')}
                    />
                  )}
                </Box>
              </Box>
            );
          })}
        </Box>
      )}

      <RunToolDialog
        projectId={project.id}
        assetId={assetId}
        languageId={languageId}
        open={runDialogOpen}
        isLoading={runTool.isLoading}
        onClose={() => setRunDialogOpen(false)}
        onSubmit={(payload) => runTool.mutate(payload)}
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
