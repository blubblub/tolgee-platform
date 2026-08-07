import { useState } from 'react';
import { Box, CircularProgress, TextField, Typography } from '@mui/material';
import { useTranslate } from '@tolgee/react';

type Props = {
  /** Called with the non-empty trimmed text when the user commits (blur or Enter). */
  onCreate: (text: string) => void;
  creating?: boolean;
  placeholder?: string;
  /** Prop names must end in DataCy so the data-cy generator picks up the literal values. */
  placeholderDataCy: string;
  inputDataCy: string;
};

/**
 * "Add transcript" placeholder that turns into a text field on click and commits on blur/Enter.
 * Creating (and seeding) the transcript key is the caller's job — this is only the inline editor.
 */
export const TranscriptAddInline = ({
  onCreate,
  creating,
  placeholder,
  placeholderDataCy,
  inputDataCy,
}: Props) => {
  const { t } = useTranslate();
  const [draft, setDraft] = useState('');
  const [editing, setEditing] = useState(false);

  const commit = () => {
    if (!draft.trim()) {
      setEditing(false);
      return;
    }
    onCreate(draft.trim());
  };

  if (!editing) {
    return (
      <Typography
        variant="body2"
        color="text.secondary"
        onClick={() => setEditing(true)}
        sx={{ fontStyle: 'italic', cursor: 'text', flex: 1 }}
        data-cy={placeholderDataCy}
      >
        {placeholder ??
          t('binary_assets_transcript_add_source', 'Add transcript')}
      </Typography>
    );
  }

  return (
    <Box display="flex" alignItems="center" gap={1}>
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
        disabled={creating}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => {
          // Enter commits, Shift+Enter keeps the newline, Escape abandons the edit
          if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            commit();
          } else if (e.key === 'Escape') {
            e.preventDefault();
            setDraft('');
            setEditing(false);
          }
        }}
        data-cy={inputDataCy}
      />
      {creating && <CircularProgress size={16} />}
    </Box>
  );
};
