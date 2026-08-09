import { act, ReactNode } from 'react';
import { createRoot, Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from 'react-query';

import { AssetLocalizedFiles } from './AssetLocalizedFiles';
import { binaryAssetApi } from './binaryAssetApi';
import { BinaryAsset } from './types';

const permissions = vi.hoisted(() => ({
  satisfiesPermission: vi.fn(() => false),
  satisfiesLanguageAccess: vi.fn(
    (_permission: string, languageId: number) => languageId === 2
  ),
}));

const recordDialog = vi.hoisted(() => ({
  open: false,
  onUse: undefined as undefined | ((file: File) => Promise<void>),
}));

vi.mock('@tolgee/react', () => ({
  useTranslate: () => ({
    t: (key: string, fallback?: string) => fallback ?? key,
  }),
}));

vi.mock('tg.hooks/useProjectPermissions', () => ({
  useProjectPermissions: () => permissions,
}));

vi.mock('tg.service/http/useQueryApi', () => ({
  invalidateUrlPrefix: vi.fn(),
  useApiMutation: () => ({ isLoading: false, mutate: vi.fn() }),
}));

vi.mock('react-intersection-observer', () => ({
  useInView: () => ({ ref: vi.fn(), inView: false }),
}));

vi.mock('./BinaryAssetPreview', () => ({ BinaryAssetPreview: () => null }));
vi.mock('./AssetSourceTranscript', () => ({
  AssetSourceTranscript: () => null,
}));
vi.mock('./TranscriptEditor', () => ({ TranscriptEditor: () => null }));
vi.mock('./TranscriptAddInline', () => ({
  TranscriptAddInline: () => null,
}));
vi.mock('./FileDropTableCell', () => ({
  FileDropTableCell: ({ children }: { children: ReactNode }) => (
    <td>{children}</td>
  ),
}));
vi.mock('./RunToolDialog', () => ({ RunToolDialog: () => null }));
vi.mock('./RecordAudioDialog', () => ({
  RecordAudioDialog: ({
    open,
    onUse,
  }: {
    open: boolean;
    onUse: (file: File) => Promise<void>;
  }) => {
    recordDialog.open = open;
    recordDialog.onUse = onUse;
    return null;
  },
}));
vi.mock('./useRunErrorText', () => ({
  useRunErrorText: () => (code?: string) => code ?? 'Run failed',
}));
vi.mock('./useRunTool', () => ({
  useRunTool: () => ({ isLoading: false, mutate: vi.fn() }),
}));

const asset: BinaryAsset = {
  id: 42,
  name: 'Voice prompt',
  sourceLanguageId: 1,
  sourceLanguageTag: 'en',
  sourceRevision: 9,
  originalFilename: 'prompt.wav',
  contentType: 'audio/wav',
  byteSize: 100,
  sha256: 'source',
  currentCount: 0,
  outdatedCount: 0,
  targetLanguageCount: 2,
  translations: [
    {
      languageId: 1,
      languageTag: 'de',
      languageName: 'German',
      status: 'MISSING',
    },
    {
      languageId: 2,
      languageTag: 'fr',
      languageName: 'French',
      status: 'MISSING',
    },
  ],
};

const deferred = <T,>() => {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
};

describe('AssetLocalizedFiles recording integration', () => {
  let root: Root;
  let container: HTMLDivElement;
  let queryClient: QueryClient;

  const render = () => {
    act(() => {
      root.render(
        <QueryClientProvider client={queryClient}>
          <MemoryRouter>
            <AssetLocalizedFiles projectId={7} asset={asset} />
          </MemoryRouter>
        </QueryClientProvider>
      );
    });
  };

  const row = (language: string) => {
    const result = Array.from(container.querySelectorAll('tbody tr')).find(
      (item) => item.textContent?.includes(language)
    );
    expect(result).not.toBeUndefined();
    return result!;
  };

  const openRecorder = () => {
    const button = row('French').querySelector<HTMLButtonElement>(
      '[data-cy="binary-asset-record-audio"]'
    );
    expect(button).not.toBeNull();
    act(() => button!.click());
    expect(recordDialog.open).toBe(true);
    return recordDialog.onUse!;
  };

  beforeEach(() => {
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    queryClient = new QueryClient({
      defaultOptions: {
        mutations: { retry: false },
        queries: { retry: false },
      },
    });
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia: vi.fn() },
    });
    vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
    vi.stubGlobal('MediaRecorder', class {});
    vi.spyOn(binaryAssetApi, 'upsertTranslation').mockResolvedValue(asset);
    recordDialog.open = false;
    recordDialog.onUse = undefined;
  });

  afterEach(() => {
    act(() => root.unmount());
    queryClient.clear();
    container.remove();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('shows recording only for a language the user can edit', () => {
    render();

    expect(
      row('German').querySelector('[data-cy="binary-asset-record-audio"]')
    ).toBeNull();
    expect(
      row('French').querySelector('[data-cy="binary-asset-record-audio"]')
    ).not.toBeNull();
  });

  it('uploads a recorded file against the current source revision', async () => {
    render();
    const onUse = openRecorder();
    const file = new File(['voice'], 'take.webm', { type: 'audio/webm' });

    await act(async () => onUse(file));

    expect(binaryAssetApi.upsertTranslation).toHaveBeenCalledWith(
      7,
      42,
      2,
      file,
      9
    );
  });

  it('rejects a failed recorded upload and clears its row spinner', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const upload = deferred<BinaryAsset>();
    vi.mocked(binaryAssetApi.upsertTranslation).mockReturnValueOnce(
      upload.promise
    );
    render();
    const onUse = openRecorder();
    const error = new Error('upload failed');
    let result!: Promise<void>;

    act(() => {
      result = onUse(new File(['voice'], 'take.webm'));
    });
    const rejected = expect(result).rejects.toBe(error);
    expect(
      container.querySelector('[data-cy="binary-asset-file-uploading"]')
    ).not.toBeNull();

    await act(async () => {
      upload.reject(error);
      await rejected;
    });

    expect(
      container.querySelector('[data-cy="binary-asset-file-uploading"]')
    ).toBeNull();
  });
});
