import { useEffect, useMemo, useState } from 'react';
import {
  Box,
  Button,
  Checkbox,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControl,
  FormControlLabel,
  InputLabel,
  MenuItem,
  Radio,
  Select,
  TextField,
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
} from './binaryAssetApi';
import { BinaryAssetPreview } from './BinaryAssetPreview';
import { BinaryAssetTranslation } from './types';

const formatBytes = (n: number) => {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(2)} MB`;
};

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

type Tool = 'tts' | 'voice-changer';

const DEFAULT_MODEL_PLACEHOLDER: Record<Tool, string> = {
  tts: 'eleven_multilingual_v2',
  'voice-changer': 'eleven_multilingual_sts_v2',
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
  const [tool, setTool] = useState<Tool>('tts');
  const [voiceId, setVoiceId] = useState('');
  const [modelId, setModelId] = useState('');
  const [removeBackgroundNoise, setRemoveBackgroundNoise] = useState(false);
  const [baseVersionId, setBaseVersionId] = useState<string>('og');

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
      onError: (e: any) => {
        actions.showMessage({
          text:
            e?.message ||
            t(
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
      onError: (e: any) => {
        actions.showMessage({
          text:
            e?.message ||
            t(
              'asset_translation_version_delete_failed',
              'Failed to delete version.'
            ),
          variant: 'error',
        });
      },
    }
  );

  const runTool = useMutation(
    () =>
      binaryAssetApi.runTool(project.id, assetId, languageId, {
        tool,
        params: {
          voiceId,
          ...(modelId ? { modelId } : {}),
          ...(tool === 'voice-changer' ? { removeBackgroundNoise } : {}),
        },
        baseVersionId:
          baseVersionId === 'og' ? undefined : Number(baseVersionId),
      }),
    {
      onSuccess: () => {
        invalidate();
        setRunDialogOpen(false);
        setVoiceId('');
        setModelId('');
        setRemoveBackgroundNoise(false);
        setBaseVersionId('og');
        actions.showMessage({
          text: t(
            'asset_translation_tool_started',
            'Tool finished successfully.'
          ),
          variant: 'success',
        });
      },
      onError: (e: any) => {
        actions.showMessage({
          text:
            e?.message || t('asset_translation_tool_failed', 'Tool failed.'),
          variant: 'error',
        });
      },
    }
  );

  const downloadVersion = async (versionId: number) => {
    const ticket = await binaryAssetApi.versionTicket(
      project.id,
      assetId,
      languageId,
      versionId
    );
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
      </Box>

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

      {canChooseFinal && (
        <Box mt={2}>
          <FormControlLabel
            control={
              <Radio
                checked={chosenVersionId === null}
                onChange={() => setChosenVersion.mutate(null)}
                data-cy="asset-translation-choose-original"
              />
            }
            label={t(
              'asset_translation_original_final_label',
              'Original is final'
            )}
          />
        </Box>
      )}

      <Dialog
        open={runDialogOpen}
        onClose={() => setRunDialogOpen(false)}
        maxWidth="sm"
        fullWidth
        data-cy="asset-version-run-dialog"
      >
        <DialogTitle>
          {t('asset_translation_run_dialog_title', 'Run tool')}
        </DialogTitle>
        <DialogContent>
          <Box display="flex" flexDirection="column" gap={2} mt={1}>
            <FormControl fullWidth size="small">
              <InputLabel id="asset-version-tool-label">
                {t('asset_translation_tool_label', 'Tool')}
              </InputLabel>
              <Select
                labelId="asset-version-tool-label"
                value={tool}
                label={t('asset_translation_tool_label', 'Tool')}
                onChange={(e) => setTool(e.target.value as Tool)}
                data-cy="asset-version-tool-select"
              >
                <MenuItem value="tts">
                  {t('asset_translation_tool_tts', 'Text-to-speech')}
                </MenuItem>
                <MenuItem value="voice-changer">
                  {t('asset_translation_tool_voice_changer', 'Voice changer')}
                </MenuItem>
              </Select>
            </FormControl>

            <TextField
              size="small"
              label={t('asset_translation_voice_id', 'Voice ID')}
              value={voiceId}
              onChange={(e) => setVoiceId(e.target.value)}
              required
              data-cy="asset-version-voice-id"
            />

            <TextField
              size="small"
              label={t('asset_translation_model_id', 'Model ID')}
              placeholder={DEFAULT_MODEL_PLACEHOLDER[tool]}
              value={modelId}
              onChange={(e) => setModelId(e.target.value)}
              data-cy="asset-version-model-id"
            />

            {tool === 'voice-changer' && (
              <FormControlLabel
                control={
                  <Checkbox
                    checked={removeBackgroundNoise}
                    onChange={(e) => setRemoveBackgroundNoise(e.target.checked)}
                    data-cy="asset-version-remove-background-noise"
                  />
                }
                label={t(
                  'asset_translation_remove_background_noise',
                  'Remove background noise'
                )}
              />
            )}

            <FormControl fullWidth size="small">
              <InputLabel id="asset-version-base-label">
                {t('asset_translation_base_file', 'Base file')}
              </InputLabel>
              <Select
                labelId="asset-version-base-label"
                value={baseVersionId}
                label={t('asset_translation_base_file', 'Base file')}
                onChange={(e) => setBaseVersionId(e.target.value)}
                data-cy="asset-version-base-select"
              >
                <MenuItem value="og">
                  {t('asset_translation_original', 'Original upload')}
                </MenuItem>
                {versions.map((v) => (
                  <MenuItem key={v.id} value={String(v.id)}>
                    {v.originalFilename} ({v.tool})
                  </MenuItem>
                ))}
              </Select>
            </FormControl>
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRunDialogOpen(false)}>
            {t('asset_translation_cancel', 'Cancel')}
          </Button>
          <Button
            variant="contained"
            disabled={!voiceId.trim() || runTool.isLoading}
            onClick={() => runTool.mutate()}
            data-cy="asset-version-run-submit"
          >
            {runTool.isLoading ? (
              <CircularProgress size={18} />
            ) : (
              t('asset_translation_run', 'Run')
            )}
          </Button>
        </DialogActions>
      </Dialog>
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
