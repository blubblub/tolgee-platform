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
  transcriptText?: string | null;
  transcriptState?: string | null;
  transcriptionAvailable?: boolean;
  /** The final file has been confirmed; any change to what final is clears it. */
  reviewed?: boolean;
  /** null/absent means the uploaded original is the final */
  chosenVersionId?: number | null;
  chosenVersionFilename?: string | null;
  chosenVersionTool?: string | null;
  versionCount?: number;
};

export type BinaryAsset = {
  id: number;
  name: string;
  description?: string | null;
  sourceLanguageId: number;
  sourceLanguageTag: string;
  sourceRevision: number;
  /** Null when the asset has no original file; the other three blob fields go with it. */
  originalFilename?: string | null;
  contentType?: string | null;
  byteSize: number;
  sha256?: string | null;
  uploadedById?: number | null;
  createdAt?: string | null;
  updatedAt?: string | null;
  currentCount: number;
  outdatedCount: number;
  targetLanguageCount: number;
  transcriptKeyId?: number | null;
  transcriptKeyName?: string | null;
  transcriptKeyOwned?: boolean;
  transcriptKeyDeleted?: boolean;
  transcriptSourceText?: string | null;
  transcriptionAvailable?: boolean;
  /** Chosen version of the source file; null/absent means the uploaded original is the final. */
  chosenVersionId?: number | null;
  chosenVersionFilename?: string | null;
  chosenVersionTool?: string | null;
  /** Versions of the source file, not of any translation. */
  versionCount?: number;
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
