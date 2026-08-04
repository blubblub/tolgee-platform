export type BinaryAssetTranslationStatus = 'MISSING' | 'CURRENT' | 'OUTDATED';

export type BinaryAssetTranslation = {
  languageId: number;
  languageTag: string;
  languageName: string;
  status: BinaryAssetTranslationStatus;
  sourceRevision?: number | null;
  originalFilename?: string | null;
  contentType?: string | null;
  byteSize?: number | null;
  sha256?: string | null;
  uploadedById?: number | null;
  updatedAt?: string | null;
};

export type BinaryAsset = {
  id: number;
  name: string;
  description?: string | null;
  sourceLanguageId: number;
  sourceLanguageTag: string;
  sourceRevision: number;
  originalFilename: string;
  contentType: string;
  byteSize: number;
  sha256: string;
  uploadedById?: number | null;
  createdAt?: string | null;
  updatedAt?: string | null;
  currentCount: number;
  outdatedCount: number;
  targetLanguageCount: number;
  translations?: BinaryAssetTranslation[] | null;
};

export type BinaryAssetPage = {
  _embedded?: { binaryAssets?: BinaryAsset[] };
  page?: {
    totalElements?: number;
    totalPages?: number;
    number?: number;
    size?: number;
  };
};

export type DownloadTicket = { url: string };
