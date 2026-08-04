import { T } from '@tolgee/react';
import type { ActivityOptions, ActivityTypeEnum } from './types';

export const binaryAssetConfiguration: Partial<
  Record<ActivityTypeEnum, ActivityOptions>
> = {
  BINARY_ASSET_CREATE: {
    label() {
      return (
        <T
          keyName="activity_binary_asset_create"
          defaultValue="Created asset"
        />
      );
    },
  },
  BINARY_ASSET_UPDATE: {
    label() {
      return (
        <T
          keyName="activity_binary_asset_update"
          defaultValue="Updated asset"
        />
      );
    },
  },
  BINARY_ASSET_DELETE: {
    label() {
      return (
        <T
          keyName="activity_binary_asset_delete"
          defaultValue="Deleted asset"
        />
      );
    },
  },
  BINARY_ASSET_SOURCE_REPLACE: {
    label() {
      return (
        <T
          keyName="activity_binary_asset_source_replace"
          defaultValue="Replaced asset source file"
        />
      );
    },
  },
  BINARY_ASSET_TRANSLATION_UPSERT: {
    label() {
      return (
        <T
          keyName="activity_binary_asset_translation_upsert"
          defaultValue="Uploaded asset translation"
        />
      );
    },
  },
  BINARY_ASSET_TRANSLATION_DELETE: {
    label() {
      return (
        <T
          keyName="activity_binary_asset_translation_delete"
          defaultValue="Deleted asset translation"
        />
      );
    },
  },
};
