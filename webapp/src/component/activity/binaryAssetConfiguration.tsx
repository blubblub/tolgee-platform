import { T } from '@tolgee/react';
import type { ActivityOptions, ActivityTypeEnum } from './types';

/**
 * Every BINARY_ASSET_* activity type, extracted from the generated schema. Typing the map as a
 * total Record over this makes omitting a label a compile error — an unlabelled type renders in
 * the activity feed as its raw enum name.
 */
type BinaryAssetActivityType = Extract<
  ActivityTypeEnum,
  `BINARY_ASSET_${string}`
>;

export const binaryAssetConfiguration: Record<
  BinaryAssetActivityType,
  ActivityOptions
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
  BINARY_ASSET_TRANSCRIPT_LINK: {
    label() {
      return (
        <T
          keyName="activity_binary_asset_transcript_link"
          defaultValue="Added asset transcript"
        />
      );
    },
  },
  BINARY_ASSET_TRANSCRIPT_UNLINK: {
    label() {
      return (
        <T
          keyName="activity_binary_asset_transcript_unlink"
          defaultValue="Removed asset transcript"
        />
      );
    },
  },
  BINARY_ASSET_TRANSCRIPT_GENERATE: {
    label() {
      return (
        <T
          keyName="activity_binary_asset_transcript_generate"
          defaultValue="Generated asset transcript with AI"
        />
      );
    },
  },
};
