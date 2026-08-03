import { apiHttpService } from 'tg.service/http/ApiHttpService';
import { BinaryAsset, BinaryAssetPage, DownloadTicket } from './types';

const base = (projectId: number | string) =>
  `v2/projects/${projectId}/binary-assets`;

export const binaryAssetApi = {
  list(projectId: number, page = 0, search?: string) {
    const q = new URLSearchParams({ page: String(page) });
    if (search) q.set('search', search);
    return apiHttpService.get<BinaryAssetPage>(`${base(projectId)}?${q}`);
  },
  get(projectId: number, assetId: number) {
    return apiHttpService.get<BinaryAsset>(`${base(projectId)}/${assetId}`);
  },
  create(
    projectId: number,
    payload: { name: string; description?: string; file: File }
  ) {
    const form = new FormData();
    form.append('name', payload.name);
    if (payload.description) form.append('description', payload.description);
    form.append('file', payload.file);
    return apiHttpService.postMultipart<BinaryAsset>(base(projectId), form);
  },
  update(
    projectId: number,
    assetId: number,
    body: { name: string; description?: string | null }
  ) {
    return apiHttpService.put<BinaryAsset>(
      `${base(projectId)}/${assetId}`,
      body
    );
  },
  replaceSource(projectId: number, assetId: number, file: File) {
    const form = new FormData();
    form.append('file', file);
    return apiHttpService.putMultipart<BinaryAsset>(
      `${base(projectId)}/${assetId}/source`,
      form
    );
  },
  sourceTicket(projectId: number, assetId: number) {
    return apiHttpService.post<DownloadTicket>(
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
    return apiHttpService.putMultipart<BinaryAsset>(
      `${base(projectId)}/${assetId}/translations/${languageId}`,
      form
    );
  },
  translationTicket(projectId: number, assetId: number, languageId: number) {
    return apiHttpService.post<DownloadTicket>(
      `${base(projectId)}/${assetId}/translations/${languageId}/download-ticket`,
      {}
    );
  },
  deleteTranslation(projectId: number, assetId: number, languageId: number) {
    return apiHttpService.delete(
      `${base(projectId)}/${assetId}/translations/${languageId}`
    );
  },
  deleteAsset(projectId: number, assetId: number) {
    return apiHttpService.delete(`${base(projectId)}/${assetId}`);
  },
};
