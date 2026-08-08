import { useEffect, useState } from 'react';
import {
  Box,
  Button,
  Checkbox,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControl,
  FormControlLabel,
  InputLabel,
  MenuItem,
  Select,
  TextField,
} from '@mui/material';
import { useTranslate } from '@tolgee/react';
import { useQuery } from 'react-query';

import {
  binaryAssetApi,
  resolveDefaultVoice,
  RunPayload,
  Tool,
} from './binaryAssetApi';

const DEFAULT_MODEL_PLACEHOLDER: Record<Tool, string> = {
  tts: 'eleven_multilingual_v2',
  'voice-changer': 'eleven_multilingual_sts_v2',
};

/** The everyday regeneration tool — preselected on every open and listed first. */
const DEFAULT_TOOL: Tool = 'voice-changer';

type Props = {
  projectId: number;
  assetId: number;
  languageId: number;
  open: boolean;
  isLoading: boolean;
  onClose: () => void;
  onSubmit: (payload: RunPayload) => void;
};

/**
 * The run form, shared by the pipeline page and the asset page. Loads voices and versions itself —
 * both hit query keys the pages already use, so opening it costs nothing extra.
 */
export const RunToolDialog = ({
  projectId,
  assetId,
  languageId,
  open,
  isLoading,
  onClose,
  onSubmit,
}: Props) => {
  const { t } = useTranslate();
  const [tool, setTool] = useState<Tool>(DEFAULT_TOOL);
  const [voiceId, setVoiceId] = useState('');
  const [modelId, setModelId] = useState('');
  const [removeBackgroundNoise, setRemoveBackgroundNoise] = useState(false);
  const [baseVersionId, setBaseVersionId] = useState('og');

  const voicesQuery = useQuery(
    ['binary-asset-voices', projectId],
    () => binaryAssetApi.listVoices(projectId),
    { enabled: open }
  );

  const versionsQuery = useQuery(
    ['binary-asset-versions', projectId, assetId, languageId],
    () => binaryAssetApi.listVersions(projectId, assetId, languageId),
    { enabled: open }
  );

  const defaultVoiceId = resolveDefaultVoice(voicesQuery.data, languageId);
  const versions = versionsQuery.data ?? [];

  // prefill on open — the field stays editable, so a one-off voice still wins
  useEffect(() => {
    if (open) {
      setTool(DEFAULT_TOOL);
      setVoiceId(defaultVoiceId);
      setBaseVersionId('og');
    }
  }, [open, defaultVoiceId]);

  return (
    <Dialog
      open={open}
      onClose={onClose}
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
              <MenuItem value="voice-changer">
                {t('asset_translation_tool_voice_changer', 'Voice changer')}
              </MenuItem>
              <MenuItem value="tts">
                {t('asset_translation_tool_tts', 'Text-to-speech')}
              </MenuItem>
            </Select>
          </FormControl>

          <TextField
            size="small"
            label={t('asset_translation_voice_id', 'Voice ID')}
            value={voiceId}
            onChange={(e) => setVoiceId(e.target.value)}
            required
            helperText={
              defaultVoiceId && voiceId === defaultVoiceId
                ? t(
                    'asset_translation_voice_id_default',
                    'Default for this project or language — edit to use another voice.'
                  )
                : undefined
            }
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
        <Button onClick={onClose}>
          {t('asset_translation_cancel', 'Cancel')}
        </Button>
        <Button
          variant="contained"
          disabled={!voiceId.trim() || isLoading}
          onClick={() =>
            onSubmit({
              tool,
              params: {
                voiceId,
                ...(modelId ? { modelId } : {}),
                ...(tool === 'voice-changer' ? { removeBackgroundNoise } : {}),
              },
              baseVersionId:
                baseVersionId === 'og' ? undefined : Number(baseVersionId),
            })
          }
          data-cy="asset-version-run-submit"
        >
          {isLoading ? (
            <CircularProgress size={18} />
          ) : (
            t('asset_translation_run', 'Run')
          )}
        </Button>
      </DialogActions>
    </Dialog>
  );
};
