import { actionsConfiguration } from './configuration';
import type { ActivityTypeEnum } from './types';

describe('binary asset activity labels', () => {
  const binaryAssetTypes: ActivityTypeEnum[] = [
    'BINARY_ASSET_CREATE',
    'BINARY_ASSET_UPDATE',
    'BINARY_ASSET_DELETE',
    'BINARY_ASSET_SOURCE_REPLACE',
    'BINARY_ASSET_TRANSLATION_UPSERT',
    'BINARY_ASSET_TRANSLATION_DELETE',
  ];

  it.each(binaryAssetTypes)('has a label configuration for %s', (type) => {
    const config = actionsConfiguration[type];
    expect(config).toBeDefined();
    expect(config?.label).toBeDefined();
  });
});
