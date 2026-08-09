import {
  binaryAssetApi,
  isAudioAsset,
  pickRecordingMime,
  visibleTranslations,
} from './binaryAssetApi';
import { BinaryAsset } from './types';

const calls: string[] = [];

vi.mock('tg.service/http/ApiV2HttpService', () => {
  const record = (url: string) => {
    calls.push(url);
    return Promise.resolve({});
  };
  return {
    apiV2HttpService: {
      get: record,
      post: record,
      put: record,
      delete: record,
    },
  };
});

// Every pipeline endpoint hangs off …/versions — omitting it makes Spring answer
// 405 (some other mapping owns the shorter path), which reaches the user as a
// bare "Api error". That shipped once; this pins the paths.
describe('binaryAssetApi pipeline paths', () => {
  beforeEach(() => {
    calls.length = 0;
  });

  it('nests every version endpoint under /versions', async () => {
    const prefix = 'projects/1/binary-assets/2/translations/3/versions';

    await binaryAssetApi.listVersions(1, 2, 3);
    await binaryAssetApi.runTool(1, 2, 3, { tool: 'tts' });
    await binaryAssetApi.setChosenVersion(1, 2, 3, { versionId: 4 });
    await binaryAssetApi.deleteVersion(1, 2, 3, 4);
    await binaryAssetApi.versionTicket(1, 2, 3, 4);

    expect(calls).toEqual([
      prefix,
      `${prefix}/run`,
      `${prefix}/chosen-version`,
      `${prefix}/4`,
      `${prefix}/4/download-ticket`,
    ]);
  });
});

describe('visibleTranslations', () => {
  const asset = {
    translations: [
      { languageId: 1, languageTag: 'de', languageName: 'German' },
      { languageId: 2, languageTag: 'fr', languageName: 'French' },
    ],
  } as BinaryAsset;

  it('keeps only the selected languages', () => {
    expect(
      visibleTranslations(asset, ['fr']).map((r) => r.languageTag)
    ).toEqual(['fr']);
  });

  it('shows every language when the selection is empty', () => {
    expect(visibleTranslations(asset, []).map((r) => r.languageTag)).toEqual([
      'de',
      'fr',
    ]);
    expect(visibleTranslations(asset).map((r) => r.languageTag)).toEqual([
      'de',
      'fr',
    ]);
  });

  it('survives an asset with no localized files', () => {
    expect(visibleTranslations({} as BinaryAsset, ['de'])).toEqual([]);
  });
});

describe('isAudioAsset', () => {
  it('detects audio by content type', () => {
    expect(isAudioAsset('audio/mpeg', 'voice.bin')).toBe(true);
  });

  it('falls back to the filename extension for blank or generic types', () => {
    expect(isAudioAsset('', 'line.wav')).toBe(true);
    expect(isAudioAsset('application/octet-stream', 'line.wav')).toBe(true);
  });

  it('does not override a known non-audio content type with the extension', () => {
    expect(isAudioAsset('video/webm', 'clip.webm')).toBe(false);
    expect(isAudioAsset('image/png', 'recording.wav')).toBe(false);
  });

  it('rejects non-audio and missing data', () => {
    expect(isAudioAsset('image/png', 'shot.png')).toBe(false);
    expect(isAudioAsset('video/mp4', 'clip.mp4')).toBe(false);
    expect(isAudioAsset(undefined, undefined)).toBe(false);
  });
});

describe('pickRecordingMime', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('prefers opus webm when the browser supports it', () => {
    vi.stubGlobal('navigator', { mediaDevices: { getUserMedia: vi.fn() } });
    vi.stubGlobal('MediaRecorder', { isTypeSupported: () => true });
    expect(pickRecordingMime()).toBe('audio/webm;codecs=opus');
  });

  it('falls back to the first supported container (Safari: mp4)', () => {
    vi.stubGlobal('navigator', { mediaDevices: { getUserMedia: vi.fn() } });
    vi.stubGlobal('MediaRecorder', {
      isTypeSupported: (m: string) => m === 'audio/mp4',
    });
    expect(pickRecordingMime()).toBe('audio/mp4');
  });

  it('is undefined where recording is unsupported', () => {
    vi.stubGlobal('MediaRecorder', undefined);
    expect(pickRecordingMime()).toBeUndefined();
  });
});
