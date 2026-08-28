import { SyntheticEvent, useEffect, useRef, useState } from 'react';
import { Box, CircularProgress, Link, Typography } from '@mui/material';
import { useInView } from 'react-intersection-observer';
import { binaryAssetApi } from './binaryAssetApi';

type Kind = 'audio' | 'video' | 'image' | 'pdf' | 'unknown';

export function previewKind(
  contentType?: string | null,
  filename?: string | null
): Kind {
  const ct = (contentType || '').toLowerCase();
  const name = (filename || '').toLowerCase();
  if (ct.startsWith('audio/') || /\.(mp3|wav|ogg|m4a|aac)$/.test(name))
    return 'audio';
  if (ct.startsWith('video/') || /\.(mp4|webm|mov|m4v)$/.test(name))
    return 'video';
  if (ct.startsWith('image/') || /\.(png|jpe?g|gif|webp|svg)$/.test(name))
    return 'image';
  if (ct === 'application/pdf' || name.endsWith('.pdf')) return 'pdf';
  return 'unknown';
}

// A page of asset rows entering the viewport at once would fire a ticket
// request per preview; unbounded, that burst trips the server's global
// per-user rate limit (429s, then bodyless 444s once it starts striking).
const MAX_CONCURRENT_TICKETS = 4;
let activeTickets = 0;
const ticketQueue: Array<() => void> = [];

/**
 * Runs `fn` once a slot is free. A caller that was cancelled while queued (row scrolled away,
 * filter changed) gives its slot straight back instead of spending a request on a dead row.
 */
async function withTicketSlot<T>(
  fn: () => Promise<T>,
  isCancelled: () => boolean
): Promise<T | undefined> {
  if (activeTickets >= MAX_CONCURRENT_TICKETS) {
    await new Promise<void>((resolve) => ticketQueue.push(resolve));
  }
  activeTickets++;
  try {
    if (isCancelled()) return undefined;
    return await fn();
  } finally {
    activeTickets--;
    ticketQueue.shift()?.();
  }
}

const TICKET_RETRIES = 3;

// A download ticket lives 5 minutes, but the browser keeps issuing Range
// requests against the same URL for as long as the media plays — pause a long
// video and resume later and the next request 404s, which surfaces as a media
// error. Re-mint a fresh ticket a bounded number of times before giving up.
export const MAX_AUTO_RETICKETS = 2;
// Playback has to get this far past the point of failure before the retry budget is earned
// back — a file that always dies at the same offset must not re-ticket forever.
export const PROGRESS_TO_RESET_SECONDS = 2;
// HTMLMediaElement.error.code: the file itself cannot be decoded, a fresh URL will not help
const MEDIA_ERR_DECODE = 3;

export function retryDelayMs(e: unknown, attempt: number): number {
  // a 429 body is { message, retryAfter, global } with retryAfter in ms — wait exactly that long
  // rather than knocking again early and collecting a strike; 444 arrives with no body at all
  const retryAfter = Number(
    (e as { data?: { retryAfter?: unknown } })?.data?.retryAfter
  );
  if (Number.isFinite(retryAfter) && retryAfter > 0) {
    return Math.min(retryAfter, 30_000);
  }
  return 1000 * 2 ** attempt + Math.random() * 500;
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

function withInline(url: string): string {
  let u = url;
  // Ticket builder may emit http behind reverse proxy; upgrade when page is https.
  if (
    typeof window !== 'undefined' &&
    window.location.protocol === 'https:' &&
    u.startsWith('http://')
  ) {
    u = `https://${u.slice('http://'.length)}`;
  }
  if (u.includes('inline=')) return u;
  return u.includes('?') ? `${u}&inline=true` : `${u}?inline=true`;
}

type Props = {
  projectId: number;
  assetId: number;
  languageId?: number | null;
  /** Preview a pipeline version instead of the uploaded file. Needs languageId. */
  versionId?: number | null;
  contentType?: string | null;
  filename?: string | null;
  /** Compact player for list rows */
  compact?: boolean;
  /** When false, only load ticket after user interaction is not needed — always auto */
  enabled?: boolean;
};

/**
 * Loads a short-lived download ticket and renders an inline media preview
 * (audio / video / image / pdf). Falls back to a short message for other types.
 */
export const BinaryAssetPreview = ({
  projectId,
  assetId,
  languageId,
  versionId,
  contentType,
  filename,
  compact = false,
  enabled = true,
}: Props) => {
  const kind = previewKind(contentType, filename);
  const [src, setSrc] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [retryNonce, setRetryNonce] = useState(0);
  const autoReticketsRef = useRef(0);
  const resumeAtRef = useRef(0);
  const failedAtRef = useRef(0);

  const handleMediaError = (e: SyntheticEvent<HTMLMediaElement>) => {
    const el = e.currentTarget;
    if (
      el.error?.code === MEDIA_ERR_DECODE ||
      autoReticketsRef.current >= MAX_AUTO_RETICKETS
    ) {
      setError('Playback failed');
      return;
    }
    autoReticketsRef.current += 1;
    failedAtRef.current = el.currentTime || 0;
    resumeAtRef.current = failedAtRef.current;
    setRetryNonce((n) => n + 1);
  };

  const handleLoadedMetadata = (e: SyntheticEvent<HTMLMediaElement>) => {
    if (resumeAtRef.current > 0) {
      e.currentTarget.currentTime = resumeAtRef.current;
      resumeAtRef.current = 0;
    }
  };

  // Real progress past the failure point means the file plays; a later expiry gets its own retries.
  const handleTimeUpdate = (e: SyntheticEvent<HTMLMediaElement>) => {
    if (
      e.currentTarget.currentTime >
      failedAtRef.current + PROGRESS_TO_RESET_SECONDS
    ) {
      autoReticketsRef.current = 0;
    }
  };

  // Each preview waits for its own visibility, so a tall page only tickets
  // the rows actually on screen (parents may gate coarser via `enabled`).
  const { ref: inViewRef, inView } = useInView({
    rootMargin: '100px',
    triggerOnce: true,
  });

  useEffect(() => {
    if (!enabled || !inView || kind === 'unknown') {
      setSrc(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    setSrc(null);

    const fetchTicket = () =>
      languageId == null
        ? binaryAssetApi.sourceTicket(projectId, assetId, { silent: true })
        : versionId != null
        ? binaryAssetApi.versionTicket(
            projectId,
            assetId,
            languageId,
            versionId,
            { silent: true }
          )
        : binaryAssetApi.translationTicket(projectId, assetId, languageId, {
            silent: true,
          });

    const load = async () => {
      try {
        for (let attempt = 0; ; attempt++) {
          try {
            const ticket = await withTicketSlot(fetchTicket, () => cancelled);
            if (ticket && !cancelled) {
              setSrc(withInline(ticket.url));
            }
            return;
          } catch (e) {
            if (cancelled || attempt >= TICKET_RETRIES) throw e;
            await sleep(retryDelayMs(e, attempt));
            if (cancelled) return;
          }
        }
      } catch (e: any) {
        if (!cancelled) {
          setError(e?.message || 'Preview failed');
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    load();
    return () => {
      cancelled = true;
    };
  }, [
    projectId,
    assetId,
    languageId,
    versionId,
    kind,
    enabled,
    inView,
    contentType,
    filename,
    retryNonce,
  ]);

  if (kind === 'unknown') {
    return (
      <Typography
        variant="caption"
        color="text.secondary"
        data-cy="binary-asset-preview-unsupported"
      >
        No inline preview for this file type
      </Typography>
    );
  }

  if (loading) {
    return (
      <Box ref={inViewRef} display="flex" alignItems="center" gap={1} py={0.5}>
        <CircularProgress size={16} />
        <Typography variant="caption" color="text.secondary">
          Loading preview…
        </Typography>
      </Box>
    );
  }

  if (error) {
    return (
      <Box ref={inViewRef} display="flex" alignItems="center" gap={1}>
        <Typography
          variant="caption"
          color="error"
          data-cy="binary-asset-preview-error"
        >
          {error}
        </Typography>
        <Link
          component="button"
          type="button"
          variant="caption"
          onClick={(e) => {
            e.stopPropagation();
            autoReticketsRef.current = 0;
            setRetryNonce((n) => n + 1);
          }}
          data-cy="binary-asset-preview-retry"
        >
          Retry
        </Link>
      </Box>
    );
  }

  if (!src) {
    // Invisible placeholder so the intersection observer has something to
    // measure before the ticket is requested.
    return <Box ref={inViewRef} sx={{ minWidth: 1, minHeight: 1 }} />;
  }

  if (kind === 'audio') {
    return (
      <Box
        component="audio"
        controls
        preload="metadata"
        src={src}
        // narrow enough to keep the table readable, wide enough that Chrome still
        // renders the overflow menu — that menu is the only download affordance
        sx={{ width: compact ? 200 : '100%', maxWidth: 480, height: 36 }}
        onClick={(e) => e.stopPropagation()}
        onError={handleMediaError}
        onLoadedMetadata={handleLoadedMetadata}
        onTimeUpdate={handleTimeUpdate}
        data-cy="binary-asset-preview-audio"
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
        sx={{
          width: '100%',
          maxWidth: compact ? 240 : 480,
          maxHeight: compact ? 140 : 320,
          borderRadius: 1,
          bgcolor: 'common.black',
        }}
        onClick={(e) => e.stopPropagation()}
        onError={handleMediaError}
        onLoadedMetadata={handleLoadedMetadata}
        onTimeUpdate={handleTimeUpdate}
        data-cy="binary-asset-preview-video"
      />
    );
  }

  if (kind === 'image') {
    return (
      <Box
        component="img"
        src={src}
        alt={filename || 'asset preview'}
        sx={{
          maxWidth: compact ? 120 : 360,
          maxHeight: compact ? 80 : 280,
          objectFit: 'contain',
          borderRadius: 1,
          border: 1,
          borderColor: 'divider',
        }}
        onClick={(e) => e.stopPropagation()}
        data-cy="binary-asset-preview-image"
      />
    );
  }

  // pdf
  return (
    <Box
      component="iframe"
      src={src}
      title={filename || 'PDF preview'}
      sx={{
        width: '100%',
        maxWidth: compact ? 320 : 560,
        height: compact ? 180 : 360,
        border: 1,
        borderColor: 'divider',
        borderRadius: 1,
      }}
      onClick={(e) => e.stopPropagation()}
      data-cy="binary-asset-preview-pdf"
    />
  );
};
