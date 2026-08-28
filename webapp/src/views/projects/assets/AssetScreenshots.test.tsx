import { act } from 'react';
import { createRoot, Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from 'react-query';

import { AssetScreenshots } from './AssetScreenshots';
import { binaryAssetApi } from './binaryAssetApi';
import { BinaryAsset, Screenshot } from './types';

const permissions = vi.hoisted(() => ({
  satisfiesPermission: vi.fn((_permission: string) => false),
  satisfiesLanguageAccess: vi.fn(() => true),
}));

const thumbnails = vi.hoisted(() => ({
  onDelete: [] as Array<() => void>,
}));

vi.mock('@tolgee/react', () => ({
  useTranslate: () => ({
    t: (key: string, fallback?: string, params?: Record<string, unknown>) =>
      (fallback ?? key).replace(/\{(\w+)\}/g, (_, name) =>
        String(params?.[name] ?? '')
      ),
  }),
}));

vi.mock('tg.hooks/useProjectPermissions', () => ({
  useProjectPermissions: () => permissions,
}));

vi.mock('tg.service/http/useQueryApi', () => ({
  invalidateUrlPrefix: vi.fn(),
}));

// the real thumbnail needs the Tolgee theme tokens; the strip's own logic is what is under test
vi.mock(
  'tg.views/projects/translations/Screenshots/ScreenshotThumbnail',
  () => ({
    ScreenshotThumbnail: ({
      screenshot,
      onDelete,
      onClick,
    }: {
      screenshot: { id: number; src: string; highlightedKeyId: number };
      onDelete: () => void;
      onClick: () => void;
    }) => {
      thumbnails.onDelete.push(onDelete);
      return (
        <button
          data-cy="screenshot-thumbnail"
          data-src={screenshot.src}
          data-highlight={screenshot.highlightedKeyId}
          onClick={onClick}
        />
      );
    },
  })
);

vi.mock('tg.views/projects/translations/Screenshots/ScreenshotDetail', () => ({
  ScreenshotDetail: ({ initialIndex }: { initialIndex: number }) => (
    <div data-cy="screenshot-detail" data-index={initialIndex} />
  ),
}));

const screenshot = (id: number): Screenshot => ({
  id,
  fileUrl: `/screenshots/${id}.png`,
  middleSizedUrl: `/screenshots/${id}_middle.png`,
  thumbnailUrl: `/screenshots/${id}_thumb.png`,
  width: 400,
  height: 300,
  location: `screen-${id}`,
  keyReferences: [],
});

const asset: BinaryAsset = {
  id: 42,
  name: 'vox-intro',
  sourceLanguageId: 1,
  sourceLanguageTag: 'en',
  sourceRevision: 1,
  byteSize: 0,
  currentCount: 0,
  outdatedCount: 0,
  targetLanguageCount: 0,
  screenshots: [screenshot(1), screenshot(2)],
  screenshotCount: 2,
};

describe('AssetScreenshots', () => {
  let root: Root;
  let container: HTMLDivElement;
  let queryClient: QueryClient;

  const render = (value = asset, compact = false) => {
    act(() => {
      root.render(
        <QueryClientProvider client={queryClient}>
          <MemoryRouter>
            <AssetScreenshots projectId={7} asset={value} compact={compact} />
          </MemoryRouter>
        </QueryClientProvider>
      );
    });
  };

  beforeEach(() => {
    permissions.satisfiesPermission.mockImplementation(() => false);
    thumbnails.onDelete.length = 0;
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    queryClient = new QueryClient({
      defaultOptions: {
        mutations: { retry: false },
        queries: { retry: false },
      },
    });
    vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  });

  afterEach(() => {
    act(() => root.unmount());
    queryClient.clear();
    container.remove();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('renders a thumbnail per screenshot with nothing highlighted', () => {
    render();

    const thumbs = container.querySelectorAll(
      '[data-cy="screenshot-thumbnail"]'
    );
    expect(thumbs).toHaveLength(2);
    expect(thumbs[0].getAttribute('data-src')).toBe('/screenshots/1_thumb.png');
    expect(thumbs[0].getAttribute('data-highlight')).toBe('-1');
    expect(
      container.querySelector('[data-cy="binary-asset-screenshot-upload"]')
    ).toBeNull();
  });

  it('links to the detail page for screenshots the list did not carry', () => {
    render({ ...asset, screenshotCount: 9 });

    const more = container.querySelector<HTMLAnchorElement>(
      '[data-cy="binary-asset-screenshots-more"]'
    );
    expect(more?.textContent).toBe('+7 more');
    expect(more?.getAttribute('href')).toContain('/projects/7/assets/42');
  });

  it('renders nothing for an asset without screenshots when the user cannot upload', () => {
    render({ ...asset, screenshots: [], screenshotCount: 0 });

    expect(
      container.querySelector('[data-cy="binary-asset-screenshots"]')
    ).toBeNull();
  });

  it('uploads a chosen image and unlinks through the thumbnail', async () => {
    permissions.satisfiesPermission.mockImplementation(
      (permission) => permission === 'screenshots.upload'
    );
    const uploaded = vi
      .spyOn(binaryAssetApi, 'uploadScreenshot')
      .mockResolvedValue(screenshot(3));
    const unlinked = vi
      .spyOn(binaryAssetApi, 'unlinkScreenshot')
      .mockResolvedValue(undefined);
    render({ ...asset, screenshots: [], screenshotCount: 0 });

    expect(container.textContent).toContain('No screenshots yet');
    const input =
      container.querySelector<HTMLInputElement>('input[type="file"]')!;
    const file = new File(['png'], 'screen.png', { type: 'image/png' });
    await act(async () => {
      Object.defineProperty(input, 'files', { value: [file] });
      input.dispatchEvent(new Event('change', { bubbles: true }));
    });
    expect(uploaded).toHaveBeenCalledWith(7, 42, file);

    render();
    await act(async () => thumbnails.onDelete[0]());
    expect(unlinked).toHaveBeenCalledWith(7, 42, 1);
  });

  it('opens the lightbox at the clicked thumbnail', () => {
    render();

    act(() => {
      container
        .querySelectorAll<HTMLButtonElement>(
          '[data-cy="screenshot-thumbnail"]'
        )[1]
        .click();
    });

    expect(
      container
        .querySelector('[data-cy="screenshot-detail"]')
        ?.getAttribute('data-index')
    ).toBe('1');
  });
});
