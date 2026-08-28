import { act } from 'react';
import { createRoot, Root } from 'react-dom/client';

import { BinaryAssetPreview, MAX_AUTO_RETICKETS } from './BinaryAssetPreview';
import { binaryAssetApi } from './binaryAssetApi';

vi.mock('react-intersection-observer', () => ({
  useInView: () => ({ ref: () => undefined, inView: true }),
}));

vi.mock('./binaryAssetApi', () => ({
  binaryAssetApi: {
    sourceTicket: vi.fn(),
    translationTicket: vi.fn(),
    versionTicket: vi.fn(),
  },
}));

const sourceTicket = vi.mocked(binaryAssetApi.sourceTicket);

let container: HTMLDivElement;
let root: Root;

const flush = async () => {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
};

const render = async () => {
  await act(async () => {
    root.render(
      <BinaryAssetPreview
        projectId={1}
        assetId={2}
        contentType="video/mp4"
        filename="clip.mp4"
      />
    );
  });
  await flush();
};

const video = () =>
  container.querySelector(
    '[data-cy="binary-asset-preview-video"]'
  ) as HTMLVideoElement | null;

const failPlayback = async (el: HTMLVideoElement, atSecond = 0) => {
  Object.defineProperty(el, 'currentTime', {
    value: atSecond,
    writable: true,
    configurable: true,
  });
  await act(async () => {
    el.dispatchEvent(new Event('error'));
  });
  await flush();
};

beforeEach(() => {
  (globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
  let n = 0;
  sourceTicket.mockReset();
  sourceTicket.mockImplementation(async () => ({
    url: `http://localhost/v2/binary-assets/download?token=t${++n}`,
  }));
});

afterEach(async () => {
  await act(async () => root.unmount());
  container.remove();
});

describe('BinaryAssetPreview', () => {
  it('renders the ticket url inline', async () => {
    await render();
    expect(sourceTicket).toHaveBeenCalledTimes(1);
    expect(video()?.getAttribute('src')).toBe(
      'http://localhost/v2/binary-assets/download?token=t1&inline=true'
    );
  });

  it('re-mints the ticket on a media error and resumes where playback was', async () => {
    await render();
    await failPlayback(video()!, 42);

    expect(sourceTicket).toHaveBeenCalledTimes(2);
    const fresh = video()!;
    expect(fresh.getAttribute('src')).toContain('token=t2');

    let resumedAt: number | null = null;
    Object.defineProperty(fresh, 'currentTime', {
      set: (v: number) => {
        resumedAt = v;
      },
      get: () => 0,
      configurable: true,
    });
    await act(async () => {
      fresh.dispatchEvent(new Event('loadedmetadata'));
    });
    expect(resumedAt).toBe(42);
  });

  it('gives up after the automatic retries and offers a manual one', async () => {
    await render();
    for (let i = 0; i < MAX_AUTO_RETICKETS; i++) {
      await failPlayback(video()!);
    }
    expect(sourceTicket).toHaveBeenCalledTimes(1 + MAX_AUTO_RETICKETS);

    await failPlayback(video()!);
    expect(sourceTicket).toHaveBeenCalledTimes(1 + MAX_AUTO_RETICKETS);
    expect(video()).toBeNull();
    expect(
      container.querySelector('[data-cy="binary-asset-preview-error"]')
        ?.textContent
    ).toBe('Playback failed');

    await act(async () => {
      (
        container.querySelector(
          '[data-cy="binary-asset-preview-retry"]'
        ) as HTMLButtonElement
      ).click();
    });
    await flush();
    expect(sourceTicket).toHaveBeenCalledTimes(2 + MAX_AUTO_RETICKETS);
    expect(video()).not.toBeNull();
  });

  it('a successful play resets the retry budget', async () => {
    await render();
    for (let i = 0; i < MAX_AUTO_RETICKETS; i++) {
      await failPlayback(video()!);
    }
    await act(async () => {
      video()!.dispatchEvent(new Event('canplay'));
    });
    await failPlayback(video()!);
    expect(sourceTicket).toHaveBeenCalledTimes(2 + MAX_AUTO_RETICKETS);
    expect(video()).not.toBeNull();
  });
});
