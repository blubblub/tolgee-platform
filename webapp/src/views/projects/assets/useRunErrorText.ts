import { useTranslate } from '@tolgee/react';

/**
 * Backend `Message` codes a pipeline run can fail with, as sentences.
 *
 * `ApiError.message` is always the literal "Api error" — the reason lives in `code`, and the shared
 * `useErrorTranslation` has no cases for the fork's codes, so it would echo the raw code back.
 */
export const useRunErrorText = () => {
  const { t } = useTranslate();

  return (code?: string) => {
    switch (code) {
      case 'transcription_not_configured':
        return t(
          'asset_run_error_not_configured',
          'ElevenLabs is not configured on this server.'
        );
      case 'transcription_unauthorized':
        return t(
          'asset_run_error_unauthorized',
          'ElevenLabs rejected the API key.'
        );
      case 'transcription_rate_limited':
        return t(
          'asset_run_error_rate_limited',
          'ElevenLabs rate limit reached. Retry in a moment.'
        );
      case 'transcription_file_too_large':
        return t(
          'asset_run_error_file_too_large',
          'The input file is too large to send to ElevenLabs.'
        );
      case 'transcription_failed':
        return t(
          'asset_run_error_failed',
          'The ElevenLabs call failed. Retrying often works.'
        );
      case 'binary_asset_tts_no_transcript':
        return t(
          'asset_run_error_no_transcript',
          'This language has no transcript text to speak. Add one on the asset page first.'
        );
      case 'binary_asset_tts_voice_id_required':
        return t(
          'asset_run_error_no_voice',
          'No voice ID. Type one, or set a default under Languages → Voices.'
        );
      case 'binary_asset_tool_unknown':
        return t('asset_run_error_unknown_tool', 'Unknown pipeline tool.');
      case 'binary_asset_tool_not_supported':
        return t(
          'asset_run_error_tool_not_supported',
          'The audio tools do not apply to this kind of asset.'
        );
      case undefined:
        return t('asset_translation_tool_failed', 'Tool failed.');
      default:
        return t('asset_run_error_generic', 'Run failed: {code}', { code });
    }
  };
};
