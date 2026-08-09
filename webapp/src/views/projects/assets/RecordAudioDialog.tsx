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
  onUse: (file: File) => Promise<void>;
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
  const [isUploading, setIsUploading] = useState(false);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const fileRef = useRef<File | null>(null);
  const timerRef = useRef<number | undefined>(undefined);
  const takeUrlRef = useRef<string | null>(null);
  const captureRef = useRef(0);
  const startingRef = useRef(false);
  const uploadingRef = useRef(false);
  const mountedRef = useRef(false);
  const openRef = useRef(open);
  openRef.current = open;

  const stopTimer = () => {
    if (timerRef.current !== undefined) {
      clearInterval(timerRef.current);
      timerRef.current = undefined;
    }
  };

  const discardTake = () => {
    if (takeUrlRef.current) {
      URL.revokeObjectURL(takeUrlRef.current);
      takeUrlRef.current = null;
    }
    setTakeUrl(null);
    fileRef.current = null;
  };

  const stopMic = () => {
    stopTimer();
    const recorder = recorderRef.current;
    if (recorder) {
      if (recorder.state !== 'inactive') {
        recorder.stop();
      }
    }
    (streamRef.current ?? recorder?.stream)
      ?.getTracks()
      .forEach((track) => track.stop());
    recorderRef.current = null;
    streamRef.current = null;
  };

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      captureRef.current += 1;
      startingRef.current = false;
      uploadingRef.current = false;
      if (recorderRef.current) {
        recorderRef.current.onstop = null;
      }
      stopMic();
      if (takeUrlRef.current) {
        URL.revokeObjectURL(takeUrlRef.current);
      }
    };
  }, []);

  // a close mid-recording throws the take away instead of finishing it
  useEffect(() => {
    if (!open) {
      captureRef.current += 1;
      startingRef.current = false;
      uploadingRef.current = false;
      const recorder = recorderRef.current;
      if (recorder) {
        recorder.onstop = null;
      }
      stopMic();
      discardTake();
      setState('idle');
      setError(null);
      setElapsed(0);
      setIsUploading(false);
    }
  }, [open]);

  const start = async () => {
    if (startingRef.current || recorderRef.current) {
      return;
    }
    startingRef.current = true;
    const capture = ++captureRef.current;
    setError(null);
    discardTake();
    let stream: MediaStream | null = null;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      if (
        capture !== captureRef.current ||
        !mountedRef.current ||
        !openRef.current
      ) {
        stream.getTracks().forEach((track) => track.stop());
        return;
      }
      streamRef.current = stream;
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
        // The server keeps the source stem and uses this extension for translations.
        fileRef.current = new File(
          [blob],
          `recording.${type.includes('mp4') ? 'm4a' : 'webm'}`,
          { type }
        );
        takeUrlRef.current = URL.createObjectURL(blob);
        setTakeUrl(takeUrlRef.current);
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
      if (capture === captureRef.current) {
        stopMic();
        if (mountedRef.current && openRef.current) {
          setError(
            t(
              'binary_assets_record_mic_error',
              'Microphone unavailable — allow access and try again.'
            )
          );
        }
      } else {
        stream?.getTracks().forEach((track) => track.stop());
      }
    } finally {
      if (capture === captureRef.current) {
        startingRef.current = false;
      }
    }
  };

  const useTake = async () => {
    const file = fileRef.current;
    if (!file || uploadingRef.current) {
      return;
    }
    uploadingRef.current = true;
    const capture = captureRef.current;
    setIsUploading(true);
    setError(null);
    try {
      await onUse(file);
      if (
        capture === captureRef.current &&
        mountedRef.current &&
        openRef.current
      ) {
        onClose();
      }
    } catch {
      if (
        capture === captureRef.current &&
        mountedRef.current &&
        openRef.current
      ) {
        setError(
          t(
            'binary_assets_record_upload_error',
            'Upload failed — try using the recording again.'
          )
        );
      }
    } finally {
      if (capture === captureRef.current) {
        uploadingRef.current = false;
        if (mountedRef.current && openRef.current) {
          setIsUploading(false);
        }
      }
    }
  };

  return (
    <Dialog
      open={open}
      onClose={isUploading ? undefined : onClose}
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
              {state === 'recording' ? (
                <>
                  <span aria-live="polite">
                    {t('binary_assets_record_active', 'Recording…')}
                  </span>{' '}
                  <span aria-hidden="true">{formatElapsed(elapsed)}</span>
                </>
              ) : (
                t(
                  'binary_assets_record_hint',
                  'Press record and speak — the take lands in the row you opened this from.'
                )
              )}
            </Typography>
          )}
          {state === 'recording' ? (
            <Button
              variant="contained"
              color="error"
              startIcon={<StopCircle />}
              onClick={() => {
                const recorder = recorderRef.current;
                if (recorder && recorder.state !== 'inactive') {
                  recorder.stop();
                }
              }}
              data-cy="binary-asset-record-stop"
            >
              {t('binary_assets_record_stop', 'Stop')}
            </Button>
          ) : (
            <Button
              variant="outlined"
              startIcon={<Microphone01 />}
              onClick={start}
              disabled={isUploading}
              data-cy="binary-asset-record-start"
            >
              {state === 'done'
                ? t('binary_assets_record_redo', 'Record again')
                : t('binary_assets_record_start', 'Record')}
            </Button>
          )}
          {error && (
            <Typography color="error" variant="body2" role="alert">
              {error}
            </Typography>
          )}
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={isUploading}>
          {t('asset_translation_cancel', 'Cancel')}
        </Button>
        <Button
          variant="contained"
          disabled={state !== 'done' || isUploading}
          onClick={useTake}
          aria-busy={isUploading}
          data-cy="binary-asset-record-use"
        >
          {isUploading
            ? t('binary_assets_record_uploading', 'Uploading…')
            : t('binary_assets_record_use', 'Use recording')}
        </Button>
      </DialogActions>
    </Dialog>
  );
};
