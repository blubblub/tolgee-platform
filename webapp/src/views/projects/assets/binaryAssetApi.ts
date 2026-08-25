import { apiV2HttpService } from 'tg.service/http/ApiV2HttpService';
import {
  BinaryAsset,
  BinaryAssetCapabilities,
  BinaryAssetMediaType,
  BinaryAssetPage,
  BinaryAssetTranslation,
  DownloadTicket,
  Screenshot,
} from './types';

// apiV2HttpService already prefixes with /v2/
const base = (projectId: number | string) =>
  `projects/${projectId}/binary-assets`;

export type TicketOptions = {
  /**
   * Skip the global error toast — for callers that handle failures themselves
   * (BinaryAssetPreview retries tickets with backoff).
   */
  silent?: boolean;
};

const postTicket = async (
  url: string,
  opts?: TicketOptions
): Promise<DownloadTicket> => {
  const r = await apiV2HttpService.fetch(
    url,
    {
      method: 'POST',
      body: '{}',
      headers: { 'Content-Type': 'application/json' },
    },
    { disableAutoErrorHandle: opts?.silent === true }
  );
  return r.json();
};

export type BinaryAssetTranslationVersionModel = {
  id: number;
  tool: string;
  toolParams: string | null;
  originalFilename: string;
  contentType: string;
  byteSize: number;
  sha256: string;
  chosen: boolean;
  createdById: number | null;
  createdAt: string;
};

export type BinaryAssetTranslationWithVersions = BinaryAssetTranslation & {
  chosenVersionId: number | null;
  versionCount: number;
};

export type Tool = 'tts' | 'voice-changer';

export type RunPayload = {
  tool: Tool;
  params: Record<string, unknown>;
  baseVersionId?: number;
};

export const TOOL_LABELS: Record<Tool, string> = {
  tts: 'Text-to-speech',
  'voice-changer': 'Voice changer',
};

export type BinaryAssetVoiceModel = {
  /** null = the project-wide default */
  languageId: number | null;
  languageTag: string | null;
  voiceId: string;
};

/** Language default beats project default; a per-run voiceId beats both (resolved on the server). */
export const resolveDefaultVoice = (
  voices: BinaryAssetVoiceModel[] | undefined,
  languageId: number
) =>
  voices?.find((v) => v.languageId === languageId)?.voiceId ??
  voices?.find((v) => v.languageId === null)?.voiceId ??
  '';

/**
 * Elide the middle of a filename so the tail — which carries the distinguishing part and the
 * extension — stays readable. CSS ellipsis can only cut the end, which is the useless half here.
 */
export const truncateMiddle = (name: string, max = 26) => {
  if (name.length <= max) return name;
  const tail = Math.ceil((max - 1) / 2);
  const head = max - 1 - tail;
  return `${name.slice(0, head)}…${name.slice(-tail)}`;
};

export const formatBytes = (n: number) => {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(2)} MB`;
};

/** The asset's localized files narrowed to `languageTags`; empty or undefined means all of them. */
export const visibleTranslations = (
  asset: BinaryAsset,
  languageTags?: string[]
) => {
  const all = asset.translations ?? [];
  return languageTags?.length
    ? all.filter((row) => languageTags.includes(row.languageTag))
    : all;
};

const AUDIO_FILE_EXTENSIONS = [
  '.mp3',
  '.wav',
  '.ogg',
  '.m4a',
  '.aac',
  '.flac',
  '.webm',
  '.opus',
];

/**
 * Whether the asset holds audio. Uploads often arrive as application/octet-stream, so fall
 * back to the filename extension, like the backend's transcribable check does.
 */
export const isAudioAsset = (
  contentType?: string | null,
  filename?: string | null
) => {
  const type = (contentType ?? '').toLowerCase();
  if (type.startsWith('audio/')) return true;
  if (type && type !== 'application/octet-stream') return false;
  const name = (filename ?? '').toLowerCase();
  return AUDIO_FILE_EXTENSIONS.some((ext) => name.endsWith(ext));
};

const VIDEO_FILE_EXTENSIONS = ['.mp4', '.mov', '.m4v', '.mkv', '.avi'];
const IMAGE_FILE_EXTENSIONS = [
  '.png',
  '.jpg',
  '.jpeg',
  '.gif',
  '.webp',
  '.svg',
  '.bmp',
];

const ALL_CAPABILITIES: BinaryAssetCapabilities = {
  transcript: true,
  pipeline: true,
  record: true,
};

/** Same rules as the backend's BinaryAssetMediaType.infer, for assets that predate the field. */
export const inferMediaType = (
  contentType?: string | null,
  filename?: string | null
): BinaryAssetMediaType | null => {
  const type = (contentType ?? '').split(';')[0].trim().toLowerCase();
  if (type.startsWith('audio/')) return 'AUDIO';
  if (type.startsWith('video/')) return 'VIDEO';
  if (type.startsWith('image/')) return 'IMAGE';
  const name = (filename ?? '').toLowerCase();
  if (AUDIO_FILE_EXTENSIONS.some((ext) => name.endsWith(ext))) return 'AUDIO';
  if (VIDEO_FILE_EXTENSIONS.some((ext) => name.endsWith(ext))) return 'VIDEO';
  if (IMAGE_FILE_EXTENSIONS.some((ext) => name.endsWith(ext))) return 'IMAGE';
  return null;
};

/**
 * What the asset's row should offer. The server is the source of truth; the fallback only covers
 * responses without the field. An unknown type keeps everything — a transcript may be all a
 * source-less asset has, and recording is one way to give it a first file.
 */
export const getCapabilities = (
  asset: Pick<BinaryAsset, 'capabilities' | 'contentType' | 'originalFilename'>
): BinaryAssetCapabilities => {
  if (asset.capabilities) return asset.capabilities;
  switch (inferMediaType(asset.contentType, asset.originalFilename)) {
    case 'VIDEO':
      return { transcript: true, pipeline: false, record: false };
    case 'IMAGE':
      return { transcript: false, pipeline: false, record: false };
    default:
      return ALL_CAPABILITIES;
  }
};

/** In-browser recording needs both getUserMedia and MediaRecorder. */
export const canRecordAudio = () =>
  typeof navigator !== 'undefined' &&
  !!navigator.mediaDevices?.getUserMedia &&
  typeof MediaRecorder !== 'undefined';

/** Best supported take format — opus-in-webm almost everywhere, Safari wants mp4. */
export const pickRecordingMime = (): string | undefined =>
  ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4'].find(
    (m) => canRecordAudio() && MediaRecorder.isTypeSupported(m)
  );

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
    payload: { name: string; description?: string; file?: File | null }
  ) {
    const form = new FormData();
    form.append('name', payload.name);
    if (payload.description) form.append('description', payload.description);
    // Omitted entirely for an asset with no original — an empty part would be rejected as an
    // empty file rather than understood as "no source".
    if (payload.file) form.append('file', payload.file);
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
  sourceTicket(projectId: number, assetId: number, opts?: TicketOptions) {
    return postTicket(
      `${base(projectId)}/${assetId}/source/download-ticket`,
      opts
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
  translationTicket(
    projectId: number,
    assetId: number,
    languageId: number,
    opts?: TicketOptions
  ) {
    return postTicket(
      `${base(
        projectId
      )}/${assetId}/translations/${languageId}/download-ticket`,
      opts
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
  listVersions(
    projectId: number,
    assetId: number,
    languageId: number
  ): Promise<BinaryAssetTranslationVersionModel[]> {
    return apiV2HttpService.get(
      `${base(projectId)}/${assetId}/translations/${languageId}/versions`
    );
  },
  runTool(
    projectId: number,
    assetId: number,
    languageId: number,
    body: {
      tool: string;
      params?: Record<string, unknown>;
      baseVersionId?: number;
    }
  ): Promise<BinaryAssetTranslationVersionModel> {
    return apiV2HttpService.post(
      `${base(projectId)}/${assetId}/translations/${languageId}/versions/run`,
      body
    );
  },
  setChosenVersion(
    projectId: number,
    assetId: number,
    languageId: number,
    body: { versionId: number | null }
  ): Promise<BinaryAssetTranslationWithVersions> {
    return apiV2HttpService.put(
      `${base(
        projectId
      )}/${assetId}/translations/${languageId}/versions/chosen-version`,
      body
    );
  },
  deleteVersion(
    projectId: number,
    assetId: number,
    languageId: number,
    versionId: number
  ) {
    return apiV2HttpService.delete(
      `${base(
        projectId
      )}/${assetId}/translations/${languageId}/versions/${versionId}`
    );
  },
  listVoices(projectId: number): Promise<BinaryAssetVoiceModel[]> {
    return apiV2HttpService.get(`projects/${projectId}/binary-asset-voices`);
  },
  setVoice(
    projectId: number,
    body: { languageId: number | null; voiceId: string | null }
  ): Promise<BinaryAssetVoiceModel[]> {
    return apiV2HttpService.put(
      `projects/${projectId}/binary-asset-voices`,
      body
    );
  },
  listScreenshots(projectId: number, assetId: number): Promise<Screenshot[]> {
    return apiV2HttpService
      .get<{ _embedded?: { screenshots?: Screenshot[] } }>(
        `${base(projectId)}/${assetId}/screenshots`
      )
      .then((r) => r._embedded?.screenshots ?? []);
  },
  uploadScreenshot(
    projectId: number,
    assetId: number,
    file: File,
    location?: string
  ): Promise<Screenshot> {
    const form = new FormData();
    form.append('screenshot', file);
    if (location) {
      form.append(
        'info',
        new Blob([JSON.stringify({ location })], { type: 'application/json' })
      );
    }
    return apiV2HttpService.postMultipart<Screenshot>(
      `${base(projectId)}/${assetId}/screenshots`,
      form
    );
  },
  linkScreenshot(
    projectId: number,
    assetId: number,
    screenshotId: number
  ): Promise<Screenshot> {
    return apiV2HttpService.post(
      `${base(projectId)}/${assetId}/screenshots/link`,
      { screenshotId }
    );
  },
  unlinkScreenshot(projectId: number, assetId: number, screenshotId: number) {
    return apiV2HttpService.delete(
      `${base(projectId)}/${assetId}/screenshots/${screenshotId}`
    );
  },
  versionTicket(
    projectId: number,
    assetId: number,
    languageId: number,
    versionId: number,
    opts?: TicketOptions
  ): Promise<DownloadTicket> {
    return postTicket(
      `${base(
        projectId
      )}/${assetId}/translations/${languageId}/versions/${versionId}/download-ticket`,
      opts
    );
  },
};
