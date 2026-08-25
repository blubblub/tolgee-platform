import { components } from 'tg.service/apiSchema.generated';

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

export type BinaryAssetMediaType = 'AUDIO' | 'VIDEO' | 'IMAGE';

/** Which parts of the asset workflow apply to an asset — the UI hides the rest. */
export type BinaryAssetCapabilities = {
  /** A transcript key can be attached and edited (AI transcription is also provider-gated). */
  transcript: boolean;
  /** TTS / voice-changer runs may produce versions. */
  pipeline: boolean;
  /** In-browser recording is offered as a way to supply a file. */
  record: boolean;
};

export type BinaryAsset = {
  id: number;
  name: string;
  /** Inferred from the original file; null when there is none or its type is not recognised. */
  mediaType?: BinaryAssetMediaType | null;
  capabilities?: BinaryAssetCapabilities;
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
  /** Screens this asset is used on: the list carries the first few, the detail all of them. */
  screenshots?: Screenshot[];
  screenshotCount?: number;
};

/** The generated shape, so the shared screenshot components accept it as is. */
export type KeyInScreenshot = components['schemas']['KeyInScreenshotModel'];

export type AssetInScreenshot = {
  assetId: number;
  assetName: string;
};

/** A screen of the app; shared by every key and asset shown on it. */
export type Screenshot = {
  id: number;
  fileUrl: string;
  middleSizedUrl?: string | null;
  thumbnailUrl: string;
  width?: number | null;
  height?: number | null;
  location?: string | null;
  createdAt?: string | null;
  keyReferences: KeyInScreenshot[];
  assetReferences?: AssetInScreenshot[];
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
