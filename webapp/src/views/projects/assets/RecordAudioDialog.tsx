import { useEffect, useRef, useState } from 'react';
import {
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Typography,
} from '@mui/material';
import { Microphone01, StopCircle } from '@untitled-ui/icons-react';
import { useTranslate } from '@tolgee/react';

import { pickRecordingMime } from './binaryAssetApi';

type Props = {
  open: boolean;
  onClose: () => void;
  /** The finished take; the caller uploads it exactly like a chosen or dropped file. */
  onUse: (file: File) => void;
};

const formatElapsed = (seconds: number) =>
  `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;

/**
 * Records a microphone take in the browser. The mic is only requested when Record is pressed,
 * and closing the dialog always releases it — no half-finished take or open microphone survives.
 */
export const RecordAudioDialog = ({ open, onClose, onUse }: Props) => {
  const { t } = useTranslate();
  const [state, setState] = useState<'idle' | 'recording' | 'done'>('idle');
  const [error, setError] = useState<string | null>(null);
  const [elapsed, setElapsed] = useState(0);
  const [takeUrl, setTakeUrl] = useState<string | null>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const fileRef = useRef<File | null>(null);
  const timerRef = useRef<number | undefined>(undefined);

  const stopTimer = () => {
    if (timerRef.current !== undefined) {
      clearInterval(timerRef.current);
      timerRef.current = undefined;
    }
  };

  const discardTake = () => {
    setTakeUrl((url) => {
      if (url) URL.revokeObjectURL(url);
      return null;
    });
    fileRef.current = null;
  };

  const stopMic = () => {
    stopTimer();
    const recorder = recorderRef.current;
    if (recorder) {
      if (recorder.state !== 'inactive') {
        recorder.stop();
      }
      recorder.stream.getTracks().forEach((track) => track.stop());
      recorderRef.current = null;
    }
  };

  // a close mid-recording throws the take away instead of finishing it
  useEffect(() => {
    if (!open) {
      const recorder = recorderRef.current;
      if (recorder) {
        recorder.onstop = null;
      }
      stopMic();
      discardTake();
      setState('idle');
      setError(null);
      setElapsed(0);
    }
  }, [open]);

  const start = async () => {
    setError(null);
    discardTake();
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const mimeType = pickRecordingMime();
      const recorder = new MediaRecorder(
        stream,
        mimeType ? { mimeType } : undefined
      );
      recorderRef.current = recorder;
      const chunks: Blob[] = [];
      recorder.ondataavailable = (e) => {
        if (e.data.size > 0) {
          chunks.push(e.data);
        }
      };
      recorder.onstop = () => {
        const type = mimeType ?? 'audio/webm';
        const blob = new Blob(chunks, { type });
        // translations are renamed after the asset server-side; this name only shows for source
        fileRef.current = new File(
          [blob],
          `recording.${type.includes('mp4') ? 'm4a' : 'webm'}`,
          { type }
        );
        setTakeUrl(URL.createObjectURL(blob));
        stopMic();
        setState('done');
      };
      recorder.start();
      setElapsed(0);
      timerRef.current = window.setInterval(
        () => setElapsed((s) => s + 1),
        1000
      );
      setState('recording');
    } catch {
      stopMic();
      setError(
        t(
          'binary_assets_record_mic_error',
          'Microphone unavailable — allow access and try again.'
        )
      );
    }
  };

  return (
    <Dialog
      open={open}
      onClose={onClose}
      maxWidth="xs"
      fullWidth
      data-cy="binary-asset-record-dialog"
    >
      <DialogTitle>
        {t('binary_assets_record_title', 'Record audio')}
      </DialogTitle>
      <DialogContent>
        <Box
          display="flex"
          flexDirection="column"
          alignItems="center"
          gap={2}
          mt={1}
          py={1}
        >
          {state === 'done' && takeUrl ? (
            <audio
              controls
              src={takeUrl}
              style={{ width: '100%' }}
              data-cy="binary-asset-record-playback"
            />
          ) : (
            <Typography
              variant="body2"
              color={state === 'recording' ? 'error' : 'text.secondary'}
              data-cy="binary-asset-record-status"
            >
              {state === 'recording'
                ? t('binary_assets_record_recording', 'Recording… {time}', {
                    time: formatElapsed(elapsed),
                  })
                : t(
                    'binary_assets_record_hint',
                    'Press record and speak — the take lands in the row you opened this from.'
                  )}
            </Typography>
          )}
          {state === 'recording' ? (
            <Button
              variant="contained"
              color="error"
              startIcon={<StopCircle />}
              onClick={() => recorderRef.current?.stop()}
              data-cy="binary-asset-record-stop"
            >
              {t('binary_assets_record_stop', 'Stop')}
            </Button>
          ) : (
            <Button
              variant="outlined"
              startIcon={<Microphone01 />}
              onClick={start}
              data-cy="binary-asset-record-start"
            >
              {state === 'done'
                ? t('binary_assets_record_redo', 'Record again')
                : t('binary_assets_record_start', 'Record')}
            </Button>
          )}
          {error && (
            <Typography color="error" variant="body2">
              {error}
            </Typography>
          )}
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>
          {t('asset_translation_cancel', 'Cancel')}
        </Button>
        <Button
          variant="contained"
          disabled={state !== 'done'}
          onClick={() => {
            const file = fileRef.current;
            if (file) {
              onUse(file);
            }
          }}
          data-cy="binary-asset-record-use"
        >
          {t('binary_assets_record_use', 'Use recording')}
        </Button>
      </DialogActions>
    </Dialog>
  );
};
