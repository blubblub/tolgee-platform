import { DragEvent, ReactNode, useState } from 'react';
import { TableCell } from '@mui/material';

type Props = {
  /** Same permission gate as the row's upload button — inactive cells stay plain. */
  active: boolean;
  onFile: (file: File) => void;
  children: ReactNode;
};

/**
 * File column cell that also accepts a dragged-in file — dropping uploads/replaces exactly
 * like the row's upload button, without having to aim at the small icon. The highlight mirrors
 * DragDropArea, but sized for a table cell instead of its full-size icon overlay.
 */
export const FileDropTableCell = ({ active, onFile, children }: Props) => {
  const [dragOver, setDragOver] = useState(false);
  // dragleave fires when the pointer crosses into a child element; only the leave matching
  // the last dragenter target really means the pointer left the cell
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
    // the upload input is single-file too, so extra dropped files are ignored
    const file = e.dataTransfer?.files?.[0];
    if (file) {
      onFile(file);
    }
  };

  return (
    <TableCell
      onDragEnter={handleDragEnter}
      onDragLeave={handleDragLeave}
      onDragOver={handleDragOver}
      onDrop={handleDrop}
      data-cy="binary-asset-file-drop"
      sx={(theme) => ({
        outline: dragOver ? '1px dashed' : undefined,
        outlineColor: 'secondary.main',
        outlineOffset: -2,
        backgroundColor: dragOver
          ? theme.palette.tokens._components.dropzone.active
          : undefined,
      })}
    >
      {children}
    </TableCell>
  );
};
