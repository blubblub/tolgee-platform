import { act, ReactNode } from 'react';
import { createRoot, Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from 'react-query';

import { AssetLocalizedFiles } from './AssetLocalizedFiles';
import {
  binaryAssetApi,
  BinaryAssetTranslationVersionModel,
  RunPayload,
} from './binaryAssetApi';
import { BinaryAsset } from './types';

const permissions = vi.hoisted(() => ({
  satisfiesPermission: vi.fn((_permission: string) => false),
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

const generateLanguage = vi.hoisted(() => ({
  mutate: vi.fn(),
  mutateAsync: vi.fn(),
}));

const confirmationMock = vi.hoisted(() => vi.fn());

const runDialog = vi.hoisted(() => ({
  languageId: undefined as number | undefined,
  onSubmit: undefined as ((payload: RunPayload) => void) | undefined,
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

vi.mock('tg.hooks/confirmation', () => ({
  confirmation: confirmationMock,
}));

vi.mock('tg.service/http/useQueryApi', () => ({
  invalidateUrlPrefix: vi.fn(),
  useApiMutation: ({ url }: { url: string }) =>
    url.endsWith('/transcript/generate/{languageId}')
      ? { isLoading: false, ...generateLanguage }
      : url.endsWith('/versions/chosen-version')
      ? { isLoading: false, ...chooseFinal }
      : url.endsWith('/versions')
      ? { isLoading: false, ...uploadVersion }
      : { isLoading: false, mutate: vi.fn(), mutateAsync: vi.fn() },
}));

vi.mock('react-intersection-observer', () => ({
  useInView: () => ({ ref: vi.fn(), inView: false }),
}));

vi.mock('./BinaryAssetPreview', () => ({
  BinaryAssetPreview: ({
    languageId,
    versionId,
  }: {
    languageId?: number | null;
    versionId?: number | null;
  }) => (
    <span
      data-cy="binary-asset-preview-audio"
      data-language={String(languageId ?? '')}
      data-version={String(versionId ?? '')}
    />
  ),
}));
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
vi.mock('./RunToolDialog', () => ({
  RunToolDialog: ({
    languageId,
    onSubmit,
  }: {
    languageId: number;
    onSubmit: (payload: RunPayload) => void;
  }) => {
    runDialog.languageId = languageId;
    runDialog.onSubmit = onSubmit;
    return null;
  },
}));
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

const concurrentAsset: BinaryAsset = {
  ...asset,
  targetLanguageCount: 3,
  translations: [
    ...(asset.translations ?? []).map((translation) =>
      translation.languageId === 2
        ? { ...translation, transcriptionAvailable: true }
        : translation
    ),
    {
      languageId: 3,
      languageTag: 'es',
      languageName: 'Spanish',
      status: 'CURRENT',
      originalFilename: 'prompt-es.wav',
      contentType: 'audio/wav',
      byteSize: 80,
      sha256: 'spanish',
      transcriptionAvailable: true,
    },
  ],
};

const version = (id: number): BinaryAssetTranslationVersionModel => ({
  id,
  tool: 'tts',
  toolParams: null,
  originalFilename: `generated-${id}.wav`,
  contentType: 'audio/wav',
  byteSize: 5,
  sha256: `version-${id}`,
  chosen: false,
  createdById: 1,
  createdAt: '2026-08-09T00:00:00Z',
});

const deferred = <T,>() => {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
};

describe('AssetLocalizedFiles', () => {
  let root: Root;
  let container: HTMLDivElement;
  let queryClient: QueryClient;

  const render = (value = asset, sourceLanguageName?: string) => {
    act(() => {
      root.render(
        <QueryClientProvider client={queryClient}>
          <MemoryRouter>
            <AssetLocalizedFiles
              projectId={7}
              asset={value}
              sourceLanguageName={sourceLanguageName}
            />
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
    generateLanguage.mutate.mockReset();
    generateLanguage.mutateAsync.mockReset();
    confirmationMock.mockReset();
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
    runDialog.languageId = undefined;
    runDialog.onSubmit = undefined;
  });

  afterEach(() => {
    act(() => root.unmount());
    queryClient.clear();
    container.remove();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('renders the source row for an asset with no original file', () => {
    // truncateMiddle/formatBytes would throw on undefined and blank the whole page
    render(
      {
        ...asset,
        originalFilename: undefined,
        contentType: undefined,
        sha256: undefined,
        byteSize: 0,
      },
      'English'
    );

    const sourceRow = container.querySelector(
      '[data-cy="binary-asset-source-row"]'
    )!;
    expect(sourceRow).not.toBeNull();
    expect(sourceRow.textContent).toContain('Not uploaded');
    expect(sourceRow.textContent).not.toContain('prompt.wav');
  });

  it('strips transcript, pipeline and recording from an image asset', () => {
    permissions.satisfiesPermission.mockImplementation(() => true);
    permissions.satisfiesLanguageAccess.mockImplementation(() => true);
    render(
      {
        ...asset,
        originalFilename: 'splash.png',
        contentType: 'image/png',
        mediaType: 'IMAGE',
        capabilities: { transcript: false, pipeline: false, record: false },
        translations: asset.translations?.map((translation) =>
          translation.languageId === 2
            ? {
                ...translation,
                originalFilename: 'splash-fr.png',
                contentType: 'image/png',
              }
            : translation
        ),
      },
      'English'
    );

    const headers = Array.from(container.querySelectorAll('thead th')).map(
      (th) => th.textContent
    );
    expect(headers).toEqual([
      'Language',
      'Status',
      'Preview',
      'File',
      'Actions',
    ]);
    expect(
      container.querySelector('[data-cy="binary-asset-transcript-cell"]')
    ).toBeNull();
    expect(
      container.querySelector('[data-cy="binary-asset-final-cell"]')
    ).toBeNull();
    expect(
      container.querySelector('[data-cy="binary-asset-run-tool"]')
    ).toBeNull();
    expect(
      container.querySelector('[data-cy="binary-asset-preview-record-audio"]')
    ).toBeNull();
    // the file itself is still fully editable
    expect(
      row('French').querySelector('[data-cy="binary-asset-upload-translation"]')
    ).not.toBeNull();
    expect(
      row('French').querySelector('[data-cy="binary-asset-review-toggle"]')
    ).not.toBeNull();
  });

  it('keeps the transcript but drops the audio tools for a video', () => {
    permissions.satisfiesPermission.mockImplementation(() => true);
    permissions.satisfiesLanguageAccess.mockImplementation(() => true);
    render(
      {
        ...asset,
        originalFilename: 'clip.mp4',
        contentType: 'video/mp4',
        mediaType: 'VIDEO',
        capabilities: { transcript: true, pipeline: false, record: false },
      },
      'English'
    );

    expect(
      container.querySelector('[data-cy="binary-asset-transcript-cell"]')
    ).not.toBeNull();
    expect(
      container.querySelector('[data-cy="binary-asset-final-cell"]')
    ).toBeNull();
    expect(
      container.querySelector('[data-cy="binary-asset-run-tool"]')
    ).toBeNull();
    expect(
      container.querySelector('[data-cy="binary-asset-preview-record-audio"]')
    ).toBeNull();
  });

  it('keeps showing a transcript an asset already has, whatever its file became', () => {
    permissions.satisfiesPermission.mockImplementation(() => true);
    render({
      ...asset,
      originalFilename: 'splash.png',
      contentType: 'image/png',
      capabilities: { transcript: false, pipeline: false, record: false },
      transcriptKeyName: 'transcript.Voice prompt',
    });

    expect(
      container.querySelector('[data-cy="binary-asset-transcript-cell"]')
    ).not.toBeNull();
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
    permissions.satisfiesPermission.mockImplementation(
      (permission) => permission === 'keys.edit'
    );
    render(asset, 'English');
    const languageRow = row('French');
    const cells = languageRow.children;
    const previewPlayer = cells[2].querySelector(
      '[data-cy="binary-asset-preview-audio"]'
    )!;
    const previewRecorder = cells[2].querySelector(
      '[data-cy="binary-asset-preview-record-audio"]'
    )!;
    const finalPlayer = cells[5].querySelector(
      '[data-cy="binary-asset-preview-audio"]'
    )!;
    const finalRecorder = cells[5].querySelector(
      '[data-cy="binary-asset-final-record-audio"]'
    )!;
    const sourcePreviewCell = container.querySelector(
      '[data-cy="binary-asset-source-row"]'
    )!.children[2];
    const sourcePlayer = sourcePreviewCell.querySelector(
      '[data-cy="binary-asset-preview-audio"]'
    )!;
    const sourceRecorder = sourcePreviewCell.querySelector(
      '[data-cy="binary-asset-preview-record-audio"]'
    )!;

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
    expect(
      previewPlayer.compareDocumentPosition(previewRecorder) &
        Node.DOCUMENT_POSITION_FOLLOWING
    ).toBeTruthy();
    expect(
      finalPlayer.compareDocumentPosition(finalRecorder) &
        Node.DOCUMENT_POSITION_FOLLOWING
    ).toBeTruthy();
    expect(
      sourcePlayer.compareDocumentPosition(sourceRecorder) &
        Node.DOCUMENT_POSITION_FOLLOWING
    ).toBeTruthy();
    expect(previewRecorder.getAttribute('aria-label')).toBe('Record audio');
    expect(finalRecorder.getAttribute('aria-label')).toBe(
      'Record a new final version'
    );
  });

  it('shows current files as needing review until they are confirmed', () => {
    render();

    let status = row('French').querySelector(
      '[data-cy="binary-asset-status"]'
    )!;
    let reviewToggle = row('French').querySelector(
      '[data-cy="binary-asset-review-toggle"]'
    )!;
    expect(status.textContent).toBe('Needs Review');
    expect(status.classList).toContain('MuiChip-colorWarning');
    expect(reviewToggle.getAttribute('aria-label')).toBe(
      'Confirm this final file'
    );

    render({
      ...asset,
      translations: asset.translations?.map((translation) =>
        translation.languageId === 2
          ? { ...translation, reviewed: true }
          : translation
      ),
    });

    status = row('French').querySelector('[data-cy="binary-asset-status"]')!;
    reviewToggle = row('French').querySelector(
      '[data-cy="binary-asset-review-toggle"]'
    )!;
    expect(status.textContent).toBe('Reviewed');
    expect(status.classList).toContain('MuiChip-colorSuccess');
    expect(reviewToggle.getAttribute('aria-label')).toBe(
      'Confirmed — click to reopen'
    );

    render({
      ...asset,
      translations: asset.translations?.map((translation) =>
        translation.languageId === 2
          ? { ...translation, status: 'OUTDATED' }
          : translation
      ),
    });

    expect(
      row('French').querySelector('[data-cy="binary-asset-review-toggle"]')
    ).toBeNull();
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
    expect(
      row('French').querySelector('[data-cy="binary-asset-review-toggle"]')
    ).toBeNull();
  });

  it('asks for confirmation before deleting a localized file', async () => {
    permissions.satisfiesPermission.mockImplementation(
      (permission) => permission === 'translations.edit'
    );
    vi.spyOn(binaryAssetApi, 'deleteTranslation').mockResolvedValue(undefined);
    render();

    act(() => {
      row('French')
        .querySelector<HTMLButtonElement>(
          '[data-cy="binary-asset-delete-translation"]'
        )!
        .click();
    });

    expect(binaryAssetApi.deleteTranslation).not.toHaveBeenCalled();
    expect(confirmationMock).toHaveBeenCalledTimes(1);

    const options = confirmationMock.mock.calls[0][0] as {
      onConfirm: () => void;
    };
    act(() => options.onConfirm());

    await vi.waitFor(() =>
      expect(binaryAssetApi.deleteTranslation).toHaveBeenCalledWith(7, 42, 2)
    );
  });

  it('handles each transcription error and keeps the other spinner', async () => {
    permissions.satisfiesLanguageAccess.mockImplementation(() => true);
    const french = deferred<void>();
    const spanish = deferred<void>();
    const error = { handleError: vi.fn() };
    generateLanguage.mutateAsync.mockImplementation(
      ({ path }: { path: { languageId: number } }) =>
        path.languageId === 2 ? french.promise : spanish.promise
    );
    render(concurrentAsset);

    act(() => {
      row('French')
        .querySelector<HTMLButtonElement>(
          '[data-cy="binary-asset-transcript-generate-language"]'
        )!
        .click();
      row('Spanish')
        .querySelector<HTMLButtonElement>(
          '[data-cy="binary-asset-transcript-generate-language"]'
        )!
        .click();
    });

    expect(row('French').querySelector('[role="progressbar"]')).not.toBeNull();
    expect(row('Spanish').querySelector('[role="progressbar"]')).not.toBeNull();

    await act(async () => {
      french.reject(error);
      await expect(french.promise).rejects.toBe(error);
    });

    expect(error.handleError).toHaveBeenCalledTimes(1);
    expect(row('French').querySelector('[role="progressbar"]')).toBeNull();
    expect(row('Spanish').querySelector('[role="progressbar"]')).not.toBeNull();

    await act(async () => {
      spanish.resolve();
      await spanish.promise;
    });
  });

  it('keeps concurrent generation spinners keyed to their language', async () => {
    permissions.satisfiesPermission.mockImplementation(
      (permission) => permission === 'translations.edit'
    );
    const french = deferred<BinaryAssetTranslationVersionModel>();
    const spanish = deferred<BinaryAssetTranslationVersionModel>();
    vi.spyOn(binaryAssetApi, 'runTool').mockImplementation(
      (_projectId, _assetId, languageId) =>
        languageId === 2 ? french.promise : spanish.promise
    );
    render(concurrentAsset);

    act(() => {
      row('French')
        .querySelector<HTMLButtonElement>('[data-cy="binary-asset-run-tool"]')!
        .click();
    });
    expect(runDialog.languageId).toBe(2);
    act(() => runDialog.onSubmit!({ tool: 'tts', params: {} }));

    const spanishButton = row('Spanish').querySelector<HTMLButtonElement>(
      '[data-cy="binary-asset-run-tool"]'
    )!;
    expect(spanishButton.disabled).toBe(false);
    act(() => spanishButton.click());
    expect(runDialog.languageId).toBe(3);
    act(() => runDialog.onSubmit!({ tool: 'tts', params: {} }));

    expect(
      row('French').querySelector('[data-cy="binary-asset-regenerating"]')
    ).not.toBeNull();
    expect(
      row('Spanish').querySelector('[data-cy="binary-asset-regenerating"]')
    ).not.toBeNull();

    await act(async () => {
      french.resolve(version(82));
      await french.promise;
    });

    expect(chooseFinal.mutateAsync).toHaveBeenCalledWith({
      path: { projectId: 7, assetId: 42, languageId: 2 },
      content: { 'application/json': { versionId: 82 } },
    });
    expect(
      row('French').querySelector('[data-cy="binary-asset-regenerating"]')
    ).toBeNull();
    expect(
      row('Spanish').querySelector('[data-cy="binary-asset-regenerating"]')
    ).not.toBeNull();

    await act(async () => {
      spanish.resolve(version(83));
      await spanish.promise;
    });

    expect(chooseFinal.mutateAsync).toHaveBeenCalledWith({
      path: { projectId: 7, assetId: 42, languageId: 3 },
      content: { 'application/json': { versionId: 83 } },
    });
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

  it('runs the pipeline on the source file under the source language', () => {
    permissions.satisfiesPermission.mockImplementation(
      (permission) => permission === 'translations.edit'
    );
    permissions.satisfiesLanguageAccess.mockImplementation(
      (_permission, languageId) => languageId === asset.sourceLanguageId
    );
    render(asset, 'English');

    const button = row('English').querySelector<HTMLButtonElement>(
      '[data-cy="binary-asset-run-tool"]'
    );
    expect(button).not.toBeNull();
    act(() => button!.click());

    expect(runDialog.languageId).toBe(asset.sourceLanguageId);
  });

  it('shows the chosen source version as the final source file', () => {
    render(
      {
        ...asset,
        chosenVersionId: 5,
        chosenVersionFilename: 'prompt-voice.mp3',
      },
      'English'
    );

    const preview = row('English')
      .querySelector('[data-cy="binary-asset-final-cell"]')
      ?.querySelector('[data-cy="binary-asset-preview-audio"]');
    expect(preview?.getAttribute('data-version')).toBe('5');
    expect(preview?.getAttribute('data-language')).toBe(
      String(asset.sourceLanguageId)
    );
  });

  it('falls back to the uploaded source when no version is chosen', () => {
    render(asset, 'English');

    const preview = row('English')
      .querySelector('[data-cy="binary-asset-final-cell"]')
      ?.querySelector('[data-cy="binary-asset-preview-audio"]');
    // no chosen version -> the source file, which tickets without a language
    expect(preview?.getAttribute('data-version')).toBe('');
    expect(preview?.getAttribute('data-language')).toBe('');
  });
});
