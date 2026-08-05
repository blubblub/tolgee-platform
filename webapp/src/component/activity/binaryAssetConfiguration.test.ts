import { actionsConfiguration } from './configuration';
import { binaryAssetConfiguration } from './binaryAssetConfiguration';
import type { ActivityTypeEnum } from './types';

/**
 * Completeness is enforced by the compiler — binaryAssetConfiguration is a total Record over every
 * BINARY_ASSET_* activity type, so a missing label fails tsc. These tests cover what the type
 * cannot: that the map is actually reachable from actionsConfiguration, which is what the activity
 * feed reads. When it is not, rows render as the raw enum name.
 */
describe('binary asset activity labels', () => {
  const types = Object.keys(binaryAssetConfiguration) as ActivityTypeEnum[];

  it('covers every binary asset activity type', () => {
    expect(types.length).toBeGreaterThanOrEqual(9);
    expect(types.every((t) => t.startsWith('BINARY_ASSET_'))).toBe(true);
  });

  it.each(types)('%s is reachable from actionsConfiguration', (type) => {
    const config = actionsConfiguration[type];
    expect(config).toBeDefined();
    expect(config?.label).toBeInstanceOf(Function);
  });

  it('includes the AI generation type that previously rendered raw', () => {
    expect(
      actionsConfiguration['BINARY_ASSET_TRANSCRIPT_GENERATE']
    ).toBeDefined();
  });
});
