import { useEffect, useState } from 'react';
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

async function withTicketSlot<T>(fn: () => Promise<T>): Promise<T> {
  if (activeTickets >= MAX_CONCURRENT_TICKETS) {
    await new Promise<void>((resolve) => ticketQueue.push(resolve));
  }
  activeTickets++;
  try {
    return await fn();
  } finally {
    activeTickets--;
    ticketQueue.shift()?.();
  }
}

const TICKET_RETRIES = 3;

function retryDelayMs(e: unknown, attempt: number): number {
  // 429 bodies carry the bucket refill time; 444 arrives with no body at all
  const err = e as { code?: string; params?: unknown[] };
  if (err?.code === 'rate_limited') {
    const retryAfter = Number(err.params?.[0]);
    if (Number.isFinite(retryAfter) && retryAfter > 0) {
      return Math.min(retryAfter, 30_000);
    }
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
            const ticket = await withTicketSlot(fetchTicket);
            if (!cancelled) {
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
