import { useEffect, useState } from 'react';
import { Box, CircularProgress, Typography } from '@mui/material';
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

  useEffect(() => {
    if (!enabled || kind === 'unknown') {
      setSrc(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    setSrc(null);

    const load = async () => {
      try {
        const ticket =
          languageId == null
            ? await binaryAssetApi.sourceTicket(projectId, assetId)
            : versionId != null
            ? await binaryAssetApi.versionTicket(
                projectId,
                assetId,
                languageId,
                versionId
              )
            : await binaryAssetApi.translationTicket(
                projectId,
                assetId,
                languageId
              );
        if (!cancelled) {
          setSrc(withInline(ticket.url));
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
    contentType,
    filename,
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
      <Box display="flex" alignItems="center" gap={1} py={0.5}>
        <CircularProgress size={16} />
        <Typography variant="caption" color="text.secondary">
          Loading preview…
        </Typography>
      </Box>
    );
  }

  if (error) {
    return (
      <Typography
        variant="caption"
        color="error"
        data-cy="binary-asset-preview-error"
      >
        {error}
      </Typography>
    );
  }

  if (!src) return null;

  if (kind === 'audio') {
    return (
      <Box
        component="audio"
        controls
        preload="metadata"
        src={src}
        // narrow enough to keep the table readable, wide enough that Chrome still
        // renders the overflow menu — that menu is the only download affordance
        sx={{ width: compact ? 230 : '100%', maxWidth: 480, height: 36 }}
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
