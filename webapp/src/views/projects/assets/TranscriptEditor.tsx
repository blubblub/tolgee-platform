import { useEffect, useRef, useState } from 'react';
import { Box, CircularProgress, TextField, Typography } from '@mui/material';
import { useTranslate } from '@tolgee/react';

import { useApiMutation } from 'tg.service/http/useQueryApi';

type Props = {
  projectId: number;
  /** Key holding the transcript — the same key for every language. */
  keyName: string;
  languageTag: string;
  value?: string | null;
  canEdit: boolean;
  placeholder?: string;
  onSaved: () => void;
  dataCy?: string;
};

/**
 * Inline editor for one language's transcript. Writes to the transcript key through the normal
 * translation endpoint, so an edit here is indistinguishable from one made in the translations
 * view — same key, same states, same activity.
 */
export const TranscriptEditor = ({
  projectId,
  keyName,
  languageTag,
  value,
  canEdit,
  placeholder,
  onSaved,
  dataCy,
}: Props) => {
  const { t } = useTranslate();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(value ?? '');
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!editing) {
      setDraft(value ?? '');
    }
  }, [value, editing]);

  const setTranslation = useApiMutation({
    url: '/v2/projects/{projectId}/translations',
    method: 'put',
  });

  const save = () => {
    const next = draft.trim();
    if (next === (value ?? '')) {
      setEditing(false);
      return;
    }
    setTranslation.mutate(
      {
        path: { projectId },
        content: {
          'application/json': {
            key: keyName,
            translations: { [languageTag]: next },
          },
        },
      },
      {
        onSuccess: () => {
          setEditing(false);
          onSaved();
        },
      }
    );
  };

  if (!editing) {
    return (
      <Box
        onClick={canEdit ? () => setEditing(true) : undefined}
        sx={{
          cursor: canEdit ? 'text' : 'default',
          minHeight: 24,
          borderRadius: 1,
          px: canEdit ? 0.5 : 0,
          mx: canEdit ? -0.5 : 0,
          '&:hover': canEdit ? { backgroundColor: 'action.hover' } : undefined,
        }}
        data-cy={dataCy}
      >
        {value ? (
          <Typography variant="body2" component="span">
            {value}
          </Typography>
        ) : (
          <Typography variant="body2" component="span" color="text.secondary">
            {canEdit ? placeholder ?? '—' : '—'}
          </Typography>
        )}
      </Box>
    );
  }

  return (
    <Box display="flex" alignItems="center" gap={1}>
      <TextField
        inputRef={inputRef}
        autoFocus
        fullWidth
        multiline
        size="small"
        value={draft}
        disabled={setTranslation.isLoading}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={save}
        onKeyDown={(e) => {
          // Enter saves, Shift+Enter keeps the newline, Escape abandons the edit
          if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            save();
          } else if (e.key === 'Escape') {
            e.preventDefault();
            setDraft(value ?? '');
            setEditing(false);
          }
        }}
        placeholder={t(
          'binary_assets_transcript_cell_placeholder',
          'Transcript'
        )}
        data-cy="binary-asset-transcript-input"
      />
      {setTranslation.isLoading && <CircularProgress size={16} />}
    </Box>
  );
};
