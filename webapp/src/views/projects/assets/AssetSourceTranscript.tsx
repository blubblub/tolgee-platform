import { useState } from 'react';
import {
  Box,
  CircularProgress,
  IconButton,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import { Stars01 } from '@untitled-ui/icons-react';
import { useTranslate } from '@tolgee/react';
import { useQueryClient } from 'react-query';

import { useProjectPermissions } from 'tg.hooks/useProjectPermissions';
import {
  invalidateUrlPrefix,
  useApiMutation,
} from 'tg.service/http/useQueryApi';
import { TranscriptEditor } from './TranscriptEditor';
import { BinaryAsset } from './types';

type Props = {
  projectId: number;
  asset: BinaryAsset;
};

/**
 * The source-language transcript, inline on the assets list. An asset with no transcript key yet
 * gets one on the first save or AI run, so the text can be written before anything is uploaded.
 */
export const AssetSourceTranscript = ({ projectId, asset }: Props) => {
  const { t } = useTranslate();
  const { satisfiesPermission, satisfiesLanguageAccess } =
    useProjectPermissions();
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState('');
  const [editing, setEditing] = useState(false);

  const path = { projectId, assetId: asset.id };

  const invalidate = () => {
    queryClient.invalidateQueries(['binary-asset', projectId, asset.id]);
    invalidateUrlPrefix(queryClient, '/v2/projects/{projectId}/binary-assets');
  };

  const addTranscript = useApiMutation({
    url: '/v2/projects/{projectId}/binary-assets/{assetId}/transcript',
    method: 'post',
  });

  const generateTranscript = useApiMutation({
    url: '/v2/projects/{projectId}/binary-assets/{assetId}/transcript/generate',
    method: 'post',
  });

  const canCreate = satisfiesPermission('keys.create');
  const canEditSource = satisfiesLanguageAccess(
    'translations.edit',
    asset.sourceLanguageId
  );

  const transcribeButton = (regenerate: boolean) =>
    canCreate &&
    asset.transcriptionAvailable && (
      <Tooltip
        title={
          regenerate
            ? t('binary_assets_transcript_regenerate', 'Regenerate with AI')
            : t('binary_assets_transcript_generate', 'Transcribe with AI')
        }
      >
        <span>
          <IconButton
            size="small"
            disabled={generateTranscript.isLoading}
            onClick={() =>
              generateTranscript.mutate({ path }, { onSuccess: invalidate })
            }
            data-cy="binary-assets-source-transcript-generate"
          >
            {generateTranscript.isLoading ? (
              <CircularProgress size={16} />
            ) : (
              <Stars01 width={16} height={16} />
            )}
          </IconButton>
        </span>
      </Tooltip>
    );

  if (asset.transcriptKeyName) {
    return (
      <Box
        display="flex"
        alignItems="flex-start"
        gap={0.5}
        mt={0.5}
        maxWidth={520}
        data-cy="binary-assets-source-transcript"
      >
        <Box flex={1} minWidth={0}>
          <TranscriptEditor
            projectId={projectId}
            keyName={asset.transcriptKeyName}
            languageTag={asset.sourceLanguageTag}
            value={asset.transcriptSourceText}
            canEdit={canEditSource}
            placeholder={t(
              'binary_assets_transcript_empty',
              'No transcript text yet.'
            )}
            onSaved={invalidate}
          />
        </Box>
        {transcribeButton(true)}
      </Box>
    );
  }

  if (!canCreate) {
    return null;
  }

  return (
    <Box
      display="flex"
      alignItems="center"
      gap={0.5}
      mt={0.5}
      data-cy="binary-assets-source-transcript"
    >
      {editing ? (
        <TextField
          size="small"
          autoFocus
          fullWidth
          multiline
          placeholder={t(
            'binary_assets_transcript_text_placeholder',
            'What is said in this file'
          )}
          value={draft}
          disabled={addTranscript.isLoading}
          onChange={(e) => setDraft(e.target.value)}
          onBlur={() => {
            if (!draft.trim()) {
              setEditing(false);
              return;
            }
            addTranscript.mutate(
              {
                path,
                content: { 'application/json': { text: draft.trim() } },
              },
              {
                onSuccess: () => {
                  setEditing(false);
                  setDraft('');
                  invalidate();
                },
              }
            );
          }}
          data-cy="binary-assets-source-transcript-input"
        />
      ) : (
        <Typography
          variant="body2"
          color="text.secondary"
          onClick={() => setEditing(true)}
          sx={{ fontStyle: 'italic', cursor: 'text', flex: 1 }}
          data-cy="binary-assets-source-transcript-placeholder"
        >
          {t('binary_assets_transcript_add_source', 'Add transcript')}
        </Typography>
      )}
      {transcribeButton(false)}
    </Box>
  );
};
