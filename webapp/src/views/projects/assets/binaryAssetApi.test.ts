import { binaryAssetApi } from './binaryAssetApi';

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
