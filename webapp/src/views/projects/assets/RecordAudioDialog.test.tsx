import { act } from 'react';
import { createRoot, Root } from 'react-dom/client';

import { RecordAudioDialog } from './RecordAudioDialog';

vi.mock('@tolgee/react', () => ({
  useTranslate: () => ({
    t: (_key: string, fallback: string) => fallback,
  }),
}));

class FakeMediaRecorder {
  static instances: FakeMediaRecorder[] = [];
  static failOnConstruct = false;
  static isTypeSupported = () => true;

  state: RecordingState = 'inactive';
  ondataavailable: ((event: BlobEvent) => void) | null = null;
  onstop: ((event: Event) => void) | null = null;
  stopCalls = 0;

  constructor(public stream: MediaStream) {
    if (FakeMediaRecorder.failOnConstruct) {
      throw new Error('setup failed');
    }
    FakeMediaRecorder.instances.push(this);
  }

  start() {
    this.state = 'recording';
  }

  stop() {
    if (this.state === 'inactive') {
      throw new DOMException('Recorder is inactive', 'InvalidStateError');
    }
    this.stopCalls += 1;
    this.state = 'inactive';
    queueMicrotask(() => this.onstop?.(new Event('stop')));
  }
}

const deferred = <T,>() => {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
};

const makeStream = () => {
  const stop = vi.fn();
  const stream = {
    getTracks: () => [{ stop }],
  } as unknown as MediaStream;
  return { stream, stop };
};

describe('RecordAudioDialog capture lifecycle', () => {
  let root: Root | null;
  let container: HTMLDivElement;
  let getUserMedia: ReturnType<typeof vi.fn>;

  const renderDialog = (
    open = true,
    onUse: (file: File) => Promise<void> = vi.fn().mockResolvedValue(undefined),
    onClose = vi.fn(),
    useAsFinal = false
  ) => {
    act(() => {
      root?.render(
        <RecordAudioDialog
          open={open}
          useAsFinal={useAsFinal}
          onClose={onClose}
          onUse={onUse}
        />
      );
    });
    return { onClose, onUse };
  };

  const click = (dataCy: string) => {
    const button = document.querySelector<HTMLButtonElement>(
      `[data-cy="${dataCy}"]`
    );
    expect(button).not.toBeNull();
    button!.click();
  };

  beforeEach(() => {
    root = null;
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    getUserMedia = vi.fn();
    const navigatorWithMedia = Object.create(navigator) as Navigator;
    Object.defineProperty(navigatorWithMedia, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia },
    });
    vi.stubGlobal('navigator', navigatorWithMedia);
    vi.stubGlobal(
      'MediaRecorder',
      FakeMediaRecorder as unknown as typeof MediaRecorder
    );
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:take');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    FakeMediaRecorder.instances = [];
    FakeMediaRecorder.failOnConstruct = false;
  });

  afterEach(() => {
    if (root) {
      act(() => root?.unmount());
    }
    container.remove();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('coalesces double-clicks and releases permission granted after close', async () => {
    const permission = deferred<MediaStream>();
    const { stream, stop } = makeStream();
    getUserMedia.mockReturnValue(permission.promise);
    renderDialog();

    act(() => {
      click('binary-asset-record-start');
      click('binary-asset-record-start');
    });
    expect(getUserMedia).toHaveBeenCalledTimes(1);

    renderDialog(false);
    await act(async () => permission.resolve(stream));

    expect(stop).toHaveBeenCalledTimes(1);
    expect(FakeMediaRecorder.instances).toHaveLength(0);
  });

  it('releases permission granted after unmount', async () => {
    const permission = deferred<MediaStream>();
    const { stream, stop } = makeStream();
    getUserMedia.mockReturnValue(permission.promise);
    renderDialog();
    act(() => click('binary-asset-record-start'));

    act(() => root?.unmount());
    root = null;
    await act(async () => permission.resolve(stream));

    expect(stop).toHaveBeenCalledTimes(1);
    expect(FakeMediaRecorder.instances).toHaveLength(0);
  });

  it('stops the stream when recorder setup fails', async () => {
    const { stream, stop } = makeStream();
    getUserMedia.mockResolvedValue(stream);
    FakeMediaRecorder.failOnConstruct = true;
    renderDialog();

    await act(async () => click('binary-asset-record-start'));

    expect(stop).toHaveBeenCalledTimes(1);
    expect(document.querySelector('[role="alert"]')?.textContent).toContain(
      'Microphone unavailable'
    );
  });

  it('stops an active recorder and its stream on unmount', async () => {
    const { stream, stop } = makeStream();
    getUserMedia.mockResolvedValue(stream);
    renderDialog();
    await act(async () => click('binary-asset-record-start'));
    const recorder = FakeMediaRecorder.instances[0];

    act(() => root?.unmount());
    root = null;

    expect(recorder.stopCalls).toBe(1);
    expect(stop).toHaveBeenCalledTimes(1);
  });

  it('ignores a repeated stop click after the recorder becomes inactive', async () => {
    const { stream } = makeStream();
    getUserMedia.mockResolvedValue(stream);
    renderDialog();
    await act(async () => click('binary-asset-record-start'));
    const recorder = FakeMediaRecorder.instances[0];

    await act(async () => {
      click('binary-asset-record-stop');
      click('binary-asset-record-stop');
    });

    expect(recorder.stopCalls).toBe(1);
  });

  it('keeps a finished take available when upload fails', async () => {
    const { stream } = makeStream();
    const upload = deferred<void>();
    const onUse = vi.fn().mockReturnValue(upload.promise);
    const { onClose } = renderDialog(true, onUse);
    getUserMedia.mockResolvedValue(stream);

    await act(async () => click('binary-asset-record-start'));
    await act(async () => click('binary-asset-record-stop'));
    act(() => click('binary-asset-record-use'));

    const useButton = document.querySelector<HTMLButtonElement>(
      '[data-cy="binary-asset-record-use"]'
    );
    expect(useButton?.disabled).toBe(true);
    expect(useButton?.textContent).toContain('Uploading');

    await act(async () => upload.reject(new Error('upload failed')));

    expect(onClose).not.toHaveBeenCalled();
    expect(
      document.querySelector('[data-cy="binary-asset-record-playback"]')
    ).not.toBeNull();
    expect(useButton?.disabled).toBe(false);
    expect(document.querySelector('[role="alert"]')?.textContent).toContain(
      'Upload failed'
    );
  });

  it('labels the final action explicitly', () => {
    renderDialog(true, undefined, undefined, true);

    expect(
      document.querySelector('[data-cy="binary-asset-record-use"]')?.textContent
    ).toContain('Use as final');
  });
});
