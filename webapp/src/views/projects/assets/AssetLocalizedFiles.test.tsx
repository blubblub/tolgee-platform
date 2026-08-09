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

const chooseFinal = vi.hoisted(() => ({
  mutate: vi.fn(),
  mutateAsync: vi.fn(),
}));

const uploadVersion = vi.hoisted(() => ({
  mutate: vi.fn(),
  mutateAsync: vi.fn(),
}));

const recordDialog = vi.hoisted(() => ({
  open: false,
  useAsFinal: false,
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
  useApiMutation: ({ url }: { url: string }) =>
    url.endsWith('/versions/chosen-version')
      ? { isLoading: false, ...chooseFinal }
      : url.endsWith('/versions')
      ? { isLoading: false, ...uploadVersion }
      : { isLoading: false, mutate: vi.fn(), mutateAsync: vi.fn() },
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
    useAsFinal,
    onUse,
  }: {
    open: boolean;
    useAsFinal?: boolean;
    onUse: (file: File) => Promise<void>;
  }) => {
    recordDialog.open = open;
    recordDialog.useAsFinal = useAsFinal ?? false;
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
      status: 'CURRENT',
      originalFilename: 'prompt-fr.wav',
      contentType: 'audio/wav',
      byteSize: 90,
      sha256: 'translation',
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

  const render = (value = asset) => {
    act(() => {
      root.render(
        <QueryClientProvider client={queryClient}>
          <MemoryRouter>
            <AssetLocalizedFiles projectId={7} asset={value} />
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

  const openRecorder = (kind: 'preview' | 'final' = 'preview') => {
    const selector =
      kind === 'preview'
        ? '[data-cy="binary-asset-preview-record-audio"]'
        : '[data-cy="binary-asset-final-record-audio"]';
    const button = row('French').querySelector<HTMLButtonElement>(selector);
    expect(button).not.toBeNull();
    act(() => button!.click());
    expect(recordDialog.open).toBe(true);
    expect(recordDialog.useAsFinal).toBe(kind === 'final');
    return recordDialog.onUse!;
  };

  beforeEach(() => {
    permissions.satisfiesPermission.mockImplementation(() => false);
    permissions.satisfiesLanguageAccess.mockImplementation(
      (_permission, languageId) => languageId === 2
    );
    chooseFinal.mutate.mockReset();
    chooseFinal.mutateAsync.mockReset();
    uploadVersion.mutate.mockReset();
    uploadVersion.mutateAsync.mockReset();
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
    uploadVersion.mutateAsync.mockResolvedValue({
      id: 81,
      tool: 'upload',
      toolParams: null,
      originalFilename: 'recording.webm',
      contentType: 'audio/webm',
      byteSize: 5,
      sha256: 'version',
      chosen: false,
      createdById: 1,
      createdAt: '2026-08-09T00:00:00Z',
    });
    chooseFinal.mutateAsync.mockResolvedValue(asset);
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
      row('German').querySelector(
        '[data-cy="binary-asset-preview-record-audio"]'
      )
    ).toBeNull();
    expect(
      row('French').querySelector(
        '[data-cy="binary-asset-preview-record-audio"]'
      )
    ).not.toBeNull();
  });

  it('puts record controls beside the Preview and Final players', () => {
    render();
    const languageRow = row('French');
    const cells = languageRow.children;

    expect(languageRow.querySelector('th[scope="row"]')?.textContent).toContain(
      'French'
    );
    expect(
      cells[2].querySelector('[data-cy="binary-asset-preview-record-audio"]')
    ).not.toBeNull();
    expect(
      cells[3].querySelector('[data-cy="binary-asset-preview-record-audio"]')
    ).toBeNull();
    expect(
      cells[5].querySelector('[data-cy="binary-asset-final-record-audio"]')
    ).not.toBeNull();
  });

  it('requires edit and state-edit access for the Final recorder', () => {
    permissions.satisfiesLanguageAccess.mockImplementation(
      (permission, languageId) =>
        languageId === 2 && permission === 'translations.edit'
    );
    render();

    expect(
      row('French').querySelector(
        '[data-cy="binary-asset-preview-record-audio"]'
      )
    ).not.toBeNull();
    expect(
      row('French').querySelector('[data-cy="binary-asset-final-record-audio"]')
    ).toBeNull();
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

  it('uploads a recorded version and selects it as Final', async () => {
    render();
    const onUse = openRecorder('final');
    const file = new File(['voice'], 'take.webm', { type: 'audio/webm' });

    await act(async () => onUse(file));

    expect(uploadVersion.mutateAsync).toHaveBeenCalledWith({
      path: { projectId: 7, assetId: 42, languageId: 2 },
      content: { 'multipart/form-data': { file } },
    });
    expect(binaryAssetApi.upsertTranslation).not.toHaveBeenCalled();
    expect(chooseFinal.mutateAsync).toHaveBeenCalledWith({
      path: { projectId: 7, assetId: 42, languageId: 2 },
      content: { 'application/json': { versionId: 81 } },
    });
  });

  it('keeps an uploaded version when selecting it as Final fails', async () => {
    chooseFinal.mutateAsync.mockRejectedValueOnce(new Error('choose failed'));
    render();
    const onUse = openRecorder('final');

    await act(async () => onUse(new File(['voice'], 'take.webm')));

    expect(uploadVersion.mutateAsync).toHaveBeenCalledTimes(1);
    expect(container.textContent).toContain(
      'The new version finished, but setting it as the final file failed'
    );
  });

  it('keeps the recorded take when its version upload fails', async () => {
    const error = new Error('version upload failed');
    uploadVersion.mutateAsync.mockRejectedValueOnce(error);
    render();
    const onUse = openRecorder('final');

    await act(async () => {
      await expect(onUse(new File(['voice'], 'take.webm'))).rejects.toBe(error);
    });

    expect(chooseFinal.mutateAsync).not.toHaveBeenCalled();
    expect(
      container.querySelector('[data-cy="binary-asset-regenerating"]')
    ).toBeNull();
  });

  it('blocks another Final recording while one is being saved', async () => {
    permissions.satisfiesLanguageAccess.mockImplementation(() => true);
    const upload = deferred<{ id: number }>();
    uploadVersion.mutateAsync.mockReturnValueOnce(upload.promise);
    render({
      ...asset,
      translations: [
        ...(asset.translations ?? []),
        {
          languageId: 3,
          languageTag: 'es',
          languageName: 'Spanish',
          status: 'CURRENT',
          originalFilename: 'prompt-es.wav',
          contentType: 'audio/wav',
          byteSize: 80,
          sha256: 'spanish',
        },
      ],
    });
    const onUse = openRecorder('final');
    let result!: Promise<void>;

    act(() => {
      result = onUse(new File(['voice'], 'take.webm'));
    });

    expect(
      row('Spanish').querySelector<HTMLButtonElement>(
        '[data-cy="binary-asset-final-record-audio"]'
      )?.disabled
    ).toBe(true);

    await act(async () => {
      upload.resolve({ id: 82 });
      await result;
    });
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
