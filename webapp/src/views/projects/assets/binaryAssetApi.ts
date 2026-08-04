import { apiV2HttpService } from 'tg.service/http/ApiV2HttpService';
import { BinaryAsset, BinaryAssetPage, DownloadTicket } from './types';

// apiV2HttpService already prefixes with /v2/
const base = (projectId: number | string) =>
  `projects/${projectId}/binary-assets`;

export const binaryAssetApi = {
  list(
    projectId: number,
    page = 0,
    search?: string,
    mediaTypes?: Array<'AUDIO' | 'VIDEO' | 'IMAGE'>
  ) {
    const q = new URLSearchParams({ page: String(page) });
    if (search) q.set('search', search);
    (mediaTypes || []).forEach((t) => q.append('filterMediaType', t));
    return apiV2HttpService.get<BinaryAssetPage>(`${base(projectId)}?${q}`);
  },
  get(projectId: number, assetId: number) {
    return apiV2HttpService.get<BinaryAsset>(`${base(projectId)}/${assetId}`);
  },
  create(
    projectId: number,
    payload: { name: string; description?: string; file: File }
  ) {
    const form = new FormData();
    form.append('name', payload.name);
    if (payload.description) form.append('description', payload.description);
    form.append('file', payload.file);
    return apiV2HttpService.postMultipart<BinaryAsset>(base(projectId), form);
  },
  update(
    projectId: number,
    assetId: number,
    body: { name: string; description?: string | null }
  ) {
    return apiV2HttpService.put<BinaryAsset>(
      `${base(projectId)}/${assetId}`,
      body
    );
  },
  replaceSource(projectId: number, assetId: number, file: File) {
    const form = new FormData();
    form.append('file', file);
    return apiV2HttpService.putMultipart<BinaryAsset>(
      `${base(projectId)}/${assetId}/source`,
      form
    );
  },
  sourceTicket(projectId: number, assetId: number) {
    return apiV2HttpService.post<DownloadTicket>(
      `${base(projectId)}/${assetId}/source/download-ticket`,
      {}
    );
  },
  upsertTranslation(
    projectId: number,
    assetId: number,
    languageId: number,
    file: File,
    translatedAgainstSourceRevision: number
  ) {
    const form = new FormData();
    form.append('file', file);
    form.append(
      'translatedAgainstSourceRevision',
      String(translatedAgainstSourceRevision)
    );
    return apiV2HttpService.putMultipart<BinaryAsset>(
      `${base(projectId)}/${assetId}/translations/${languageId}`,
      form
    );
  },
  translationTicket(projectId: number, assetId: number, languageId: number) {
    return apiV2HttpService.post<DownloadTicket>(
      `${base(
        projectId
      )}/${assetId}/translations/${languageId}/download-ticket`,
      {}
    );
  },
  deleteTranslation(projectId: number, assetId: number, languageId: number) {
    return apiV2HttpService.delete(
      `${base(projectId)}/${assetId}/translations/${languageId}`
    );
  },
  deleteAsset(projectId: number, assetId: number) {
    return apiV2HttpService.delete(`${base(projectId)}/${assetId}`);
  },
};
