import { useRef, useState } from 'react';
import { Box, IconButton, Link, Tooltip, Typography } from '@mui/material';
import { Image01, Plus } from '@untitled-ui/icons-react';
import { useTranslate } from '@tolgee/react';
import { useMutation, useQueryClient } from 'react-query';
import { Link as RouterLink } from 'react-router-dom';

import { LINKS, PARAMS } from 'tg.constants/links';
import { useProjectPermissions } from 'tg.hooks/useProjectPermissions';
import { invalidateUrlPrefix } from 'tg.service/http/useQueryApi';
import { ScreenshotThumbnail } from 'tg.views/projects/translations/Screenshots/ScreenshotThumbnail';
import { ScreenshotDetail } from 'tg.views/projects/translations/Screenshots/ScreenshotDetail';
import { binaryAssetApi } from './binaryAssetApi';
import { BinaryAsset } from './types';

const THUMB_WIDTH = 96;
const THUMB_HEIGHT = 64;

/** Nothing is highlighted on an asset's screenshot — no key is "this one". */
const NO_HIGHLIGHT = -1;

type Props = {
  projectId: number;
  asset: BinaryAsset;
  /** The list shows the first few and links to the detail for the rest. */
  compact?: boolean;
};

/**
 * The screens an asset is used on, as a strip of thumbnails. Screenshots are shared with keys, so
 * the thumbnails and the lightbox are the translation view's own components.
 */
export const AssetScreenshots = ({ projectId, asset, compact }: Props) => {
  const { t } = useTranslate();
  const { satisfiesPermission } = useProjectPermissions();
  const queryClient = useQueryClient();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [detailIndex, setDetailIndex] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  const screenshots = asset.screenshots ?? [];
  const count = asset.screenshotCount ?? screenshots.length;
  const notShown = Math.max(0, count - screenshots.length);
  const canUpload = satisfiesPermission('screenshots.upload');

  const invalidate = () => {
    queryClient.invalidateQueries(['binary-asset', projectId, asset.id]);
    invalidateUrlPrefix(queryClient, '/v2/projects/{projectId}/binary-assets');
  };

  const upload = useMutation(
    (file: File) => binaryAssetApi.uploadScreenshot(projectId, asset.id, file),
    {
      onSuccess: () => {
        setError(null);
        invalidate();
      },
      onError: (e: any) => setError(e?.message || 'Upload failed'),
    }
  );

  const unlink = useMutation(
    (screenshotId: number) =>
      binaryAssetApi.unlinkScreenshot(projectId, asset.id, screenshotId),
    { onSuccess: invalidate }
  );

  if (count === 0 && !canUpload) {
    return null;
  }

  const detailScreenshots = screenshots.map((sc) => ({
    id: sc.id,
    src: sc.middleSizedUrl ?? sc.fileUrl,
    width: sc.width ?? undefined,
    height: sc.height ?? undefined,
    highlightedKeyId: NO_HIGHLIGHT,
    keyReferences: sc.keyReferences,
  }));

  return (
    <Box
      display="flex"
      alignItems="center"
      gap={1}
      flexWrap="wrap"
      data-cy="binary-asset-screenshots"
    >
      <Tooltip
        title={t('binary_assets_screenshots', 'Screens this asset is used on')}
      >
        <Box display="flex" alignItems="center" color="text.secondary">
          <Image01 width={16} height={16} />
        </Box>
      </Tooltip>

      {screenshots.map((sc, index) => (
        <ScreenshotThumbnail
          key={sc.id}
          screenshot={{
            id: sc.id,
            src: sc.thumbnailUrl,
            width: sc.width ?? undefined,
            height: sc.height ?? undefined,
            highlightedKeyId: NO_HIGHLIGHT,
            keyReferences: sc.keyReferences,
          }}
          objectFit="contain"
          onSrcExpired={invalidate}
          onClick={() => setDetailIndex(index)}
          onDelete={() => unlink.mutate(sc.id)}
          sx={{ width: THUMB_WIDTH, height: THUMB_HEIGHT }}
        />
      ))}

      {notShown > 0 && (
        <Link
          component={RouterLink}
          to={LINKS.PROJECT_ASSET.build({
            [PARAMS.PROJECT_ID]: projectId,
            [PARAMS.ASSET_ID]: asset.id,
          })}
          variant="body2"
          data-cy="binary-asset-screenshots-more"
        >
          {t('binary_assets_screenshots_more', '+{count} more', {
            count: notShown,
          })}
        </Link>
      )}

      {/* the list stays quiet about empties; the detail page says so */}
      {count === 0 && !compact && (
        <Typography variant="caption" color="text.secondary">
          {t('binary_assets_screenshots_empty', 'No screenshots yet')}
        </Typography>
      )}

      {canUpload && (
        <Tooltip title={t('binary_assets_screenshot_add', 'Add a screenshot')}>
          <span>
            <IconButton
              size="small"
              disabled={upload.isLoading}
              onClick={() => fileInputRef.current?.click()}
              data-cy="binary-asset-screenshot-upload"
            >
              <Plus width={16} height={16} />
            </IconButton>
          </span>
        </Tooltip>
      )}

      {error && (
        <Typography variant="caption" color="error" role="alert">
          {error}
        </Typography>
      )}

      <input
        ref={fileInputRef}
        type="file"
        accept="image/png,image/jpeg,image/gif"
        hidden
        onChange={(e) => {
          const file = e.target.files?.[0];
          if (file) upload.mutate(file);
          e.target.value = '';
        }}
      />

      {detailIndex !== null && detailScreenshots[detailIndex] && (
        <ScreenshotDetail
          screenshots={detailScreenshots}
          initialIndex={detailIndex}
          onClose={() => setDetailIndex(null)}
          onSrcExpired={invalidate}
        />
      )}
    </Box>
  );
};
