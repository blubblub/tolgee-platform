import { act } from 'react';
import { createRoot, Root } from 'react-dom/client';

import {
  BinaryAssetPreview,
  MAX_AUTO_RETICKETS,
  PROGRESS_TO_RESET_SECONDS,
  retryDelayMs,
} from './BinaryAssetPreview';
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

const setTime = (el: HTMLMediaElement, seconds: number) =>
  Object.defineProperty(el, 'currentTime', {
    value: seconds,
    writable: true,
    configurable: true,
  });

const failPlayback = async (
  el: HTMLVideoElement,
  atSecond = 0,
  errorCode?: number
) => {
  setTime(el, atSecond);
  Object.defineProperty(el, 'error', {
    value: errorCode == null ? null : { code: errorCode },
    configurable: true,
  });
  await act(async () => {
    el.dispatchEvent(new Event('error'));
  });
  await flush();
};

const progressTo = async (el: HTMLMediaElement, seconds: number) => {
  setTime(el, seconds);
  await act(async () => {
    el.dispatchEvent(new Event('timeupdate'));
  });
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

  it('real progress past the failure point earns the retry budget back', async () => {
    await render();
    for (let i = 0; i < MAX_AUTO_RETICKETS; i++) {
      await failPlayback(video()!, 10);
    }
    await progressTo(video()!, 10 + PROGRESS_TO_RESET_SECONDS + 1);
    await failPlayback(video()!);
    expect(sourceTicket).toHaveBeenCalledTimes(2 + MAX_AUTO_RETICKETS);
    expect(video()).not.toBeNull();
  });

  it('a file that keeps dying at the same offset does not re-ticket forever', async () => {
    await render();
    for (let i = 0; i < MAX_AUTO_RETICKETS; i++) {
      await failPlayback(video()!, 10);
      // it loads and reaches the same point again, but gets no further
      await progressTo(video()!, 10);
    }
    await failPlayback(video()!, 10);
    expect(sourceTicket).toHaveBeenCalledTimes(1 + MAX_AUTO_RETICKETS);
    expect(video()).toBeNull();
    expect(
      container.querySelector('[data-cy="binary-asset-preview-error"]')
    ).not.toBeNull();
  });

  it('does not re-ticket for a decode error — the file is broken, not the ticket', async () => {
    await render();
    await failPlayback(video()!, 0, 3);
    expect(sourceTicket).toHaveBeenCalledTimes(1);
    expect(video()).toBeNull();
    expect(
      container.querySelector('[data-cy="binary-asset-preview-error"]')
        ?.textContent
    ).toBe('Playback failed');
  });

  it('waits out the refill time a 429 body reports before retrying', () => {
    expect(retryDelayMs({ data: { retryAfter: 7000 } }, 0)).toBe(7000);
    // capped, so a huge refill window cannot park the row for minutes
    expect(retryDelayMs({ data: { retryAfter: 120_000 } }, 0)).toBe(30_000);
    // 444s have no body: exponential backoff instead
    const bodyless = retryDelayMs({}, 2);
    expect(bodyless).toBeGreaterThanOrEqual(4000);
    expect(bodyless).toBeLessThan(4500);
  });

  it('a row unmounted while queued for a slot never sends its request', async () => {
    // hold the 4 slots with tickets that never resolve, queue a 5th, then take the 5th away
    sourceTicket.mockImplementation(() => new Promise(() => undefined));
    const holders = Array.from({ length: 4 }, () => {
      const c = document.createElement('div');
      document.body.appendChild(c);
      const r = createRoot(c);
      return { c, r };
    });
    await act(async () => {
      holders.forEach(({ r }, i) =>
        r.render(
          <BinaryAssetPreview
            projectId={1}
            assetId={100 + i}
            contentType="video/mp4"
            filename="clip.mp4"
          />
        )
      );
    });
    await flush();
    expect(sourceTicket).toHaveBeenCalledTimes(4);

    await render(); // queued: no free slot
    await flush();
    expect(sourceTicket).toHaveBeenCalledTimes(4);
    await act(async () => root.unmount());
    root = createRoot(container); // so afterEach has something to unmount

    // free the slots; the queued, now-dead row must not spend one
    await act(async () => holders.forEach(({ r }) => r.unmount()));
    await flush();
    expect(sourceTicket).toHaveBeenCalledTimes(4);
    holders.forEach(({ c }) => c.remove());
  });
});
