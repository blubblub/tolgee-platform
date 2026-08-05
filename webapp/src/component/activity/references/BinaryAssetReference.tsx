import React from 'react';
import clsx from 'clsx';
import { Link } from 'react-router-dom';
import { Link as MuiLink } from '@mui/material';

import { LINKS, PARAMS } from 'tg.constants/links';
import { useProject } from 'tg.hooks/useProject';
import { BinaryAssetReferenceData } from '../types';

type Props = {
  data: BinaryAssetReferenceData;
};

export const BinaryAssetReference: React.FC<React.PropsWithChildren<Props>> = ({
  data,
}) => {
  const project = useProject();

  // a deleted asset has no page to open, so render the name without a link
  const href =
    data.exists === false
      ? undefined
      : LINKS.PROJECT_ASSET.build({
          [PARAMS.PROJECT_ID]: project.id,
          [PARAMS.ASSET_ID]: data.id,
        });

  const content = <span className="referenceText">{data.name}</span>;
  const classes = ['reference', 'referenceComposed'];

  return href ? (
    <MuiLink
      component={Link}
      to={href}
      className={clsx(classes, 'referenceLink')}
    >
      {content}
    </MuiLink>
  ) : (
    <span className={clsx(classes)}>{content}</span>
  );
};
