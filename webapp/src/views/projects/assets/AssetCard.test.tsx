import { act } from 'react';
import { createRoot, Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';

import { AssetCard } from './AssetCard';
import { BinaryAsset } from './types';

vi.mock('@tolgee/react', () => ({
  useTranslate: () => ({
    t: (key: string, fallback?: string) => fallback ?? key,
  }),
}));

// the card's own header is under test; the per-language table and the screenshot strip have their own tests
vi.mock('./AssetLocalizedFiles', () => ({
  AssetLocalizedFiles: () => <div data-testid="localized-files-stub" />,
}));
vi.mock('./AssetScreenshots', () => ({
  AssetScreenshots: () => <div data-testid="screenshots-stub" />,
}));

let container: HTMLDivElement;
let root: Root;

/** Atlas-style source-less asset: no original file at all, typed only by its lane files. */
const sourceless = (overrides: Partial<BinaryAsset> = {}): BinaryAsset =>
  ({
    id: 1000182009,
    name: 'vox_int_exercise_background_autoplay_tapthecow_002',
    sourceLanguageId: 1,
    sourceLanguageTag: 'en',
    sourceRevision: 1,
    originalFilename: null,
    contentType: null,
    byteSize: 0,
    sha256: null,
    mediaType: null,
    capabilities: { transcript: true, pipeline: true, record: true },
    translations: [
      {
        languageId: 2,
        languageTag: 'he',
        status: 'CURRENT',
        originalFilename: 'tapthecow_he.wav',
        contentType: 'audio/x-wav',
      },
      {
        languageId: 3,
        languageTag: 'sl',
        status: 'CURRENT',
        originalFilename: 'tapthecow_sl.m4a',
        contentType: 'audio/mp4',
      },
    ],
    screenshots: [],
    screenshotCount: 0,
    ...overrides,
  } as unknown as BinaryAsset);

const render = async (asset: BinaryAsset) => {
  await act(async () => {
    root.render(
      <MemoryRouter>
        <AssetCard projectId={2} asset={asset} linkToDetail />
      </MemoryRouter>
    );
  });
};

const badge = () =>
  container.querySelector('[data-cy="binary-asset-media-type"]');

beforeEach(() => {
  (globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(async () => {
  await act(async () => root.unmount());
  container.remove();
});

describe('AssetCard media-type badge', () => {
  it('shows the type the server resolved for a source-less asset', async () => {
    await render(sourceless({ mediaType: 'AUDIO' }));
    expect(badge()?.textContent).toBe('AUDIO');
    // the source itself still reads as "not uploaded"
    expect(container.textContent).not.toContain('audio/x-wav');
  });

  it('falls back to the lane files when the server sent no type', async () => {
    await render(sourceless({ mediaType: undefined }));
    expect(badge()?.textContent).toBe('AUDIO');
  });

  it('types a source-less image asset by its image lane', async () => {
    await render(
      sourceless({
        mediaType: undefined,
        translations: [
          {
            languageId: 3,
            languageTag: 'sl',
            status: 'CURRENT',
            originalFilename: 'card_sl.jpg',
            contentType: 'image/jpeg',
          },
        ] as unknown as BinaryAsset['translations'],
      })
    );
    expect(badge()?.textContent).toBe('IMAGE');
  });

  it('shows no badge for an asset with no file yet', async () => {
    await render(sourceless({ mediaType: null, translations: [] }));
    expect(badge()).toBeNull();
  });

  it('lets the original win over a lane of another type', async () => {
    await render(
      sourceless({
        mediaType: undefined,
        originalFilename: 'voice.mp3',
        contentType: 'audio/mpeg',
        translations: [
          {
            languageId: 3,
            languageTag: 'sl',
            status: 'CURRENT',
            originalFilename: 'odd_sl.png',
            contentType: 'image/png',
          },
        ] as unknown as BinaryAsset['translations'],
      })
    );
    expect(badge()?.textContent).toBe('AUDIO');
  });
});
