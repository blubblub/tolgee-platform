import { useMutation, useQueryClient } from 'react-query';

import { binaryAssetApi, RunPayload } from './binaryAssetApi';

type Options = {
  projectId: number;
  assetId: number;
  languageId: number;
  onSuccess?: () => void;
  /** `code` is the backend Message code — feed it to useRunErrorText. */
  onError?: (code: string | undefined, payload: RunPayload) => void;
};

/**
 * Runs a pipeline tool and refreshes everything a new version affects. Shared so the asset page and
 * the pipeline page cannot drift on which queries go stale.
 */
export const useRunTool = ({
  projectId,
  assetId,
  languageId,
  onSuccess,
  onError,
}: Options) => {
  const queryClient = useQueryClient();

  return useMutation(
    (payload: RunPayload) =>
      binaryAssetApi.runTool(projectId, assetId, languageId, payload),
    {
      onSuccess: () => {
        queryClient.invalidateQueries(['binary-asset', projectId, assetId]);
        queryClient.invalidateQueries([
          'binary-asset-versions',
          projectId,
          assetId,
          languageId,
        ]);
        queryClient.invalidateQueries(['binary-assets', projectId]);
        queryClient.invalidateQueries([
          '/v2/projects/{projectId}/binary-assets',
          { projectId },
        ]);
        onSuccess?.();
      },
      onError: (e: any, payload) => onError?.(e?.code, payload),
    }
  );
};
