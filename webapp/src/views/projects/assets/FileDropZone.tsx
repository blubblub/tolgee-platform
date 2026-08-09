import { DragEvent, ReactNode, useState } from 'react';
import { Box, SxProps, Theme } from '@mui/material';

type Props = {
  /** Same permission gate as the upload buttons — inactive zones stay plain. */
  active: boolean;
  onFile: (file: File) => void;
  children: ReactNode;
  dataCy?: string;
  sx?: SxProps<Theme>;
};

/**
 * Box that accepts a dragged-in file — the same interaction as FileDropTableCell, but sized for
 * cards and drop areas instead of a table cell. The highlight mirrors DragDropArea.
 */
export const FileDropZone = ({
  active,
  onFile,
  children,
  dataCy,
  sx,
}: Props) => {
  const [dragOver, setDragOver] = useState(false);
  // dragleave fires when the pointer crosses into a child element; only the leave matching
  // the last dragenter target really means the pointer left the zone
  const [dragEnterTarget, setDragEnterTarget] = useState<EventTarget | null>(
    null
  );

  const reset = () => {
    setDragOver(false);
    setDragEnterTarget(null);
  };

  const handleDragEnter = (e: DragEvent) => {
    if (!active) return;
    e.preventDefault();
    e.stopPropagation();
    // only file drags highlight — dragging selected text or a link must not tease a drop
    if (Array.from(e.dataTransfer?.types ?? []).includes('Files')) {
      setDragEnterTarget(e.target);
      setDragOver(true);
    }
  };

  const handleDragLeave = (e: DragEvent) => {
    if (!active) return;
    e.preventDefault();
    e.stopPropagation();
    if (e.target === dragEnterTarget) {
      reset();
    }
  };

  const handleDragOver = (e: DragEvent) => {
    if (!active) return;
    // required, or the browser never fires drop
    e.preventDefault();
    e.stopPropagation();
  };

  const handleDrop = (e: DragEvent) => {
    if (!active) return;
    e.preventDefault();
    e.stopPropagation();
    reset();
    // the upload inputs are single-file too, so extra dropped files are ignored
    const file = e.dataTransfer?.files?.[0];
    if (file) {
      onFile(file);
    }
  };

  return (
    <Box
      onDragEnter={handleDragEnter}
      onDragLeave={handleDragLeave}
      onDragOver={handleDragOver}
      onDrop={handleDrop}
      data-cy={dataCy}
      sx={[
        (theme) => ({
          outline: dragOver ? '1px dashed' : undefined,
          outlineColor: 'secondary.main',
          outlineOffset: -2,
          backgroundColor: dragOver
            ? theme.palette.tokens._components.dropzone.active
            : undefined,
        }),
        ...(Array.isArray(sx) ? sx : [sx]),
      ]}
    >
      {children}
    </Box>
  );
};
