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
    entities: { BinaryAsset: true },
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
    entities: { BinaryAsset: true },
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
    entities: { BinaryAsset: true },
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
    entities: { BinaryAsset: true },
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
    entities: { BinaryAssetTranslation: true },
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
    entities: { BinaryAssetTranslation: true },
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
    entities: { BinaryAsset: true },
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
    entities: { BinaryAsset: true },
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
    entities: { Translation: true },
  },
  BINARY_ASSET_TRANSLATION_REVIEW: {
    label() {
      return (
        <T
          keyName="activity_binary_asset_translation_review"
          defaultValue="Confirmed an asset translation"
        />
      );
    },
    entities: { BinaryAssetTranslation: true },
  },
  BINARY_ASSET_TRANSLATION_VERSION_RUN: {
    label() {
      return (
        <T
          keyName="activity_binary_asset_translation_version_run"
          defaultValue="Ran a tool on an asset translation"
        />
      );
    },
    entities: { BinaryAssetTranslation: true },
  },
  BINARY_ASSET_TRANSLATION_VERSION_CHOOSE: {
    label() {
      return (
        <T
          keyName="activity_binary_asset_translation_version_choose"
          defaultValue="Chose the final asset version"
        />
      );
    },
    entities: { BinaryAssetTranslation: true },
  },
  BINARY_ASSET_TRANSLATION_VERSION_DELETE: {
    label() {
      return (
        <T
          keyName="activity_binary_asset_translation_version_delete"
          defaultValue="Deleted an asset version"
        />
      );
    },
    entities: { BinaryAssetTranslation: true },
  },
  BINARY_ASSET_SCREENSHOT_ADD: {
    label() {
      return (
        <T
          keyName="activity_binary_asset_screenshot_add"
          defaultValue="Added a screenshot to an asset"
        />
      );
    },
    entities: { BinaryAsset: true },
  },
  BINARY_ASSET_SCREENSHOT_DELETE: {
    label() {
      return (
        <T
          keyName="activity_binary_asset_screenshot_delete"
          defaultValue="Removed a screenshot from an asset"
        />
      );
    },
    entities: { BinaryAsset: true },
  },
};
