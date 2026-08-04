import { useState } from 'react';
import { Box, Button, Chip, TextField, Typography } from '@mui/material';
import { T, useTranslate } from '@tolgee/react';
import { Link } from 'react-router-dom';

import { LINKS, PARAMS } from 'tg.constants/links';
import { queryEncode } from 'tg.hooks/useUrlSearchState';
import { useApiMutation } from 'tg.service/http/useQueryApi';
import { TranscriptEditor } from './TranscriptEditor';
import { BinaryAsset } from './types';

type Props = {
  asset: BinaryAsset;
  projectId: number;
  canCreate: boolean;
  canEdit: boolean;
  /** Whether the user may edit the source language's transcript text. */
  canEditSource: boolean;
  onChange: () => void;
};

/** Deep-link to the single-key view instead of mounting the translations context here. */
export const transcriptKeyLink = (projectId: number, keyId: number) =>
  LINKS.PROJECT_TRANSLATIONS_SINGLE.build({
    [PARAMS.PROJECT_ID]: projectId,
  }) + queryEncode({ id: keyId });

export const AssetTranscript = ({
  asset,
  projectId,
  canCreate,
  canEdit,
  canEditSource,
  onChange,
}: Props) => {
  const { t } = useTranslate();
  const [text, setText] = useState('');
  const [linkKeyId, setLinkKeyId] = useState('');
  const [mode, setMode] = useState<'none' | 'create' | 'link'>('none');

  const addTranscript = useApiMutation({
    url: '/v2/projects/{projectId}/binary-assets/{assetId}/transcript',
    method: 'post',
  });

  const removeTranscript = useApiMutation({
    url: '/v2/projects/{projectId}/binary-assets/{assetId}/transcript',
    method: 'delete',
  });

  const generateTranscript = useApiMutation({
    url: '/v2/projects/{projectId}/binary-assets/{assetId}/transcript/generate',
    method: 'post',
  });

  const generate = () =>
    generateTranscript.mutate({ path }, { onSuccess: done });

  const done = () => {
    setMode('none');
    setText('');
    setLinkKeyId('');
    onChange();
  };

  const path = { projectId, assetId: asset.id };

  if (asset.transcriptKeyId) {
    return (
      <Box
        mb={3}
        p={2}
        border={1}
        borderColor="divider"
        borderRadius={1}
        data-cy="binary-asset-transcript"
      >
        <Box display="flex" alignItems="center" gap={1} flexWrap="wrap" mb={1}>
          <Typography fontWeight={600}>
            <T keyName="binary_assets_transcript" defaultValue="Transcript" />
          </Typography>
          <Chip
            size="small"
            label={asset.transcriptKeyName}
            data-cy="binary-asset-transcript-key"
          />
          {!asset.transcriptKeyOwned && (
            <Chip
              size="small"
              variant="outlined"
              label={t('binary_assets_transcript_linked', 'Linked key')}
            />
          )}
          {asset.transcriptKeyDeleted && (
            <Chip
              size="small"
              color="error"
              label={t('binary_assets_transcript_deleted', 'Key is in trash')}
              data-cy="binary-asset-transcript-deleted"
            />
          )}
        </Box>
        <Box mb={1.5} data-cy="binary-asset-transcript-source-text">
          <TranscriptEditor
            projectId={projectId}
            keyName={asset.transcriptKeyName!}
            languageTag={asset.sourceLanguageTag}
            value={asset.transcriptSourceText}
            canEdit={canEditSource}
            placeholder={t(
              'binary_assets_transcript_empty',
              'No transcript text yet.'
            )}
            onSaved={onChange}
          />
        </Box>
        <Box display="flex" gap={1} flexWrap="wrap">
          <Button
            size="small"
            variant="outlined"
            component={Link}
            to={transcriptKeyLink(projectId, asset.transcriptKeyId)}
            data-cy="binary-asset-transcript-edit"
          >
            {t('binary_assets_transcript_edit', 'Edit translations')}
          </Button>
          {canCreate && asset.transcriptionAvailable && (
            <Button
              size="small"
              variant="outlined"
              disabled={generateTranscript.isLoading}
              onClick={() => {
                if (
                  window.confirm(
                    t(
                      'binary_assets_transcript_regenerate_confirm',
                      'Replace the current transcript text with a new AI transcription?'
                    )
                  )
                ) {
                  generate();
                }
              }}
              data-cy="binary-asset-transcript-regenerate"
            >
              {generateTranscript.isLoading
                ? t('binary_assets_transcript_generating', 'Transcribing…')
                : t(
                    'binary_assets_transcript_regenerate',
                    'Regenerate with AI'
                  )}
            </Button>
          )}
          {canEdit && (
            <Button
              size="small"
              color="error"
              disabled={removeTranscript.isLoading}
              onClick={() =>
                removeTranscript.mutate({ path }, { onSuccess: done })
              }
              data-cy="binary-asset-transcript-unlink"
            >
              {asset.transcriptKeyOwned
                ? t('binary_assets_transcript_delete', 'Delete transcript')
                : t('binary_assets_transcript_unlink', 'Unlink')}
            </Button>
          )}
        </Box>
      </Box>
    );
  }

  if (!canCreate) {
    return null;
  }

  return (
    <Box
      mb={3}
      p={2}
      border={1}
      borderColor="divider"
      borderRadius={1}
      data-cy="binary-asset-transcript"
    >
      <Typography fontWeight={600} mb={1}>
        <T keyName="binary_assets_transcript" defaultValue="Transcript" />
      </Typography>
      <Typography variant="body2" color="text.secondary" mb={1.5}>
        <T
          keyName="binary_assets_transcript_help"
          defaultValue="Store what is said as a translation key, so it can be translated and reviewed like any other string."
        />
      </Typography>

      {mode === 'none' && (
        <Box display="flex" gap={1} flexWrap="wrap">
          <Button
            size="small"
            variant="outlined"
            onClick={() => setMode('create')}
            data-cy="binary-asset-transcript-add"
          >
            {t('binary_assets_transcript_add', 'Add transcript')}
          </Button>
          <Button
            size="small"
            onClick={() => setMode('link')}
            data-cy="binary-asset-transcript-link-existing"
          >
            {t('binary_assets_transcript_link', 'Link existing key')}
          </Button>
          {asset.transcriptionAvailable && (
            <Button
              size="small"
              variant="contained"
              disabled={generateTranscript.isLoading}
              onClick={generate}
              data-cy="binary-asset-transcript-generate"
            >
              {generateTranscript.isLoading
                ? t('binary_assets_transcript_generating', 'Transcribing…')
                : t('binary_assets_transcript_generate', 'Transcribe with AI')}
            </Button>
          )}
        </Box>
      )}

      {mode === 'create' && (
        <Box display="flex" gap={1} flexWrap="wrap" alignItems="flex-start">
          <TextField
            size="small"
            multiline
            minRows={2}
            sx={{ flex: 1, minWidth: 260 }}
            placeholder={t(
              'binary_assets_transcript_text_placeholder',
              'What is said in this file'
            )}
            value={text}
            onChange={(e) => setText(e.target.value)}
            data-cy="binary-asset-transcript-text-input"
          />
          <Button
            size="small"
            variant="contained"
            disabled={addTranscript.isLoading}
            onClick={() =>
              addTranscript.mutate(
                {
                  path,
                  content: {
                    'application/json': { text: text.trim() || undefined },
                  },
                },
                { onSuccess: done }
              )
            }
            data-cy="binary-asset-transcript-save"
          >
            {t('binary_assets_transcript_save', 'Create')}
          </Button>
          <Button size="small" onClick={() => setMode('none')}>
            {t('binary_assets_transcript_cancel', 'Cancel')}
          </Button>
        </Box>
      )}

      {mode === 'link' && (
        <Box display="flex" gap={1} flexWrap="wrap" alignItems="center">
          <TextField
            size="small"
            type="number"
            label={t('binary_assets_transcript_key_id', 'Key id')}
            value={linkKeyId}
            onChange={(e) => setLinkKeyId(e.target.value)}
            data-cy="binary-asset-transcript-key-input"
          />
          <Button
            size="small"
            variant="contained"
            disabled={!linkKeyId || addTranscript.isLoading}
            onClick={() =>
              addTranscript.mutate(
                {
                  path,
                  content: {
                    'application/json': { keyId: Number(linkKeyId) },
                  },
                },
                { onSuccess: done }
              )
            }
            data-cy="binary-asset-transcript-link-save"
          >
            {t('binary_assets_transcript_link_save', 'Link')}
          </Button>
          <Button size="small" onClick={() => setMode('none')}>
            {t('binary_assets_transcript_cancel', 'Cancel')}
          </Button>
        </Box>
      )}
    </Box>
  );
};
