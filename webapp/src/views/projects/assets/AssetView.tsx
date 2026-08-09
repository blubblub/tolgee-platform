import { IconButton, Tooltip } from '@mui/material';
import { Download01, Trash01 } from '@untitled-ui/icons-react';
import { useTranslate } from '@tolgee/react';
import { useMutation, useQuery } from 'react-query';
import { useRouteMatch } from 'react-router-dom';

import { BaseProjectView } from 'tg.views/projects/BaseProjectView';
import { useProject } from 'tg.hooks/useProject';
import { useProjectLanguages } from 'tg.hooks/useProjectLanguages';
import { ProjectLanguagesProvider } from 'tg.hooks/ProjectLanguagesProvider';
import { useProjectPermissions } from 'tg.hooks/useProjectPermissions';
import { confirmation } from 'tg.hooks/confirmation';
import { LINKS, PARAMS } from 'tg.constants/links';
import { BoxLoading } from 'tg.component/common/BoxLoading';
import { binaryAssetApi } from './binaryAssetApi';
import { AssetCard } from './AssetCard';

const AssetViewContent = () => {
  const project = useProject();
  const projectLanguages = useProjectLanguages();
  const match = useRouteMatch();
  const assetId = Number(match.params[PARAMS.ASSET_ID]);
  const { t } = useTranslate();
  const { satisfiesPermission } = useProjectPermissions();

  const canDelete = satisfiesPermission('keys.delete');

  const detailQuery = useQuery(['binary-asset', project.id, assetId], () =>
    binaryAssetApi.get(project.id, assetId)
  );

  const deleteAsset = useMutation(
    () => binaryAssetApi.deleteAsset(project.id, assetId),
    {
      onSuccess: () => {
        window.location.href = LINKS.PROJECT_ASSETS.build({
          [PARAMS.PROJECT_ID]: project.id,
        });
      },
    }
  );

  const confirmDelete = () =>
    confirmation({
      title: t('binary_assets_delete', 'Delete asset'),
      message: t(
        'binary_assets_delete_message',
        'Delete this asset and all localized files?'
      ),
      confirmButtonText: t('confirmation_dialog_delete', 'Delete'),
      onConfirm: () => deleteAsset.mutate(),
    });

  const downloadSource = async () => {
    const ticket = await binaryAssetApi.sourceTicket(project.id, assetId);
    window.location.href = ticket.url;
  };

  if (detailQuery.isLoading || !detailQuery.data) {
    return (
      <BaseProjectView windowTitle="Asset" title="Asset">
        <BoxLoading />
      </BaseProjectView>
    );
  }

  const asset = detailQuery.data;

  return (
    <BaseProjectView
      windowTitle={asset.name}
      title={asset.name}
      navigation={[
        [
          t('binary_assets_title', 'Assets'),
          LINKS.PROJECT_ASSETS.build({ [PARAMS.PROJECT_ID]: project.id }),
        ],
        [
          asset.name,
          LINKS.PROJECT_ASSET.build({
            [PARAMS.PROJECT_ID]: project.id,
            [PARAMS.ASSET_ID]: asset.id,
          }),
        ],
      ]}
    >
      {/* same card as the assets list, just filtered down to this one asset */}
      <AssetCard
        projectId={project.id}
        asset={asset}
        sourceLanguageName={
          projectLanguages.find((l) => l.tag === asset.sourceLanguageTag)
            ?.name ?? asset.sourceLanguageTag
        }
        actions={
          <>
            <Tooltip title={t('binary_assets_download', 'Download')}>
              <IconButton
                size="small"
                onClick={downloadSource}
                data-cy="binary-asset-download-source"
              >
                <Download01 width={16} height={16} />
              </IconButton>
            </Tooltip>
            {canDelete && (
              <Tooltip title={t('binary_assets_delete', 'Delete asset')}>
                <IconButton
                  size="small"
                  color="error"
                  onClick={confirmDelete}
                  data-cy="binary-asset-delete"
                >
                  <Trash01 width={16} height={16} />
                </IconButton>
              </Tooltip>
            )}
          </>
        }
      />
    </BaseProjectView>
  );
};

export const AssetView = () => (
  <ProjectLanguagesProvider>
    <AssetViewContent />
  </ProjectLanguagesProvider>
);
