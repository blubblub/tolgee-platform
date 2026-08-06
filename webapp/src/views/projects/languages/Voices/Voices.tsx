import { useState } from 'react';
import { Box, TextField, Typography } from '@mui/material';
import { useTranslate } from '@tolgee/react';
import { useMutation, useQuery, useQueryClient } from 'react-query';

import { useProject } from 'tg.hooks/useProject';
import { useProjectPermissions } from 'tg.hooks/useProjectPermissions';
import { useProjectLanguages } from 'tg.hooks/useProjectLanguages';
import { ProjectLanguagesProvider } from 'tg.hooks/ProjectLanguagesProvider';
import { BoxLoading } from 'tg.component/common/BoxLoading';
import { useMessageService } from 'tg.globalContext/useMessageService';
import { FlagImage } from '@tginternal/library/components/languages/FlagImage';
import { binaryAssetApi } from 'tg.views/projects/assets/binaryAssetApi';

const VoicesContent = () => {
  const project = useProject();
  const { t } = useTranslate();
  const { satisfiesPermission } = useProjectPermissions();
  const languages = useProjectLanguages();
  const queryClient = useQueryClient();
  const { actions } = useMessageService();
  const canEdit = satisfiesPermission('languages.edit');

  // only rows the user is editing right now; everything else renders from the server
  const [edited, setEdited] = useState<Record<string, string>>({});

  const voicesQuery = useQuery(['binary-asset-voices', project.id], () =>
    binaryAssetApi.listVoices(project.id)
  );

  const setVoice = useMutation(
    (body: { languageId: number | null; voiceId: string | null }) =>
      binaryAssetApi.setVoice(project.id, body),
    {
      onSuccess: () => {
        queryClient.invalidateQueries(['binary-asset-voices', project.id]);
        actions.showMessage({
          text: t('voices_saved', 'Default voice saved.'),
          variant: 'success',
        });
      },
      onError: (e: any) => {
        actions.showMessage({
          text: e?.message || t('voices_save_failed', 'Failed to save voice.'),
          variant: 'error',
        });
      },
    }
  );

  if (voicesQuery.isLoading) {
    return <BoxLoading />;
  }

  const voices = voicesQuery.data ?? [];
  const projectDefault =
    voices.find((v) => v.languageId === null)?.voiceId ?? '';
  const stored = (languageId: number | null) =>
    voices.find((v) => v.languageId === languageId)?.voiceId ?? '';

  const rowKey = (languageId: number | null) => String(languageId ?? 'project');

  const commit = (languageId: number | null) => {
    const key = rowKey(languageId);
    const next = edited[key];
    if (next === undefined || next === stored(languageId)) {
      return;
    }
    setVoice.mutate({ languageId, voiceId: next.trim() || null });
    setEdited((prev) => {
      const { [key]: _dropped, ...rest } = prev;
      return rest;
    });
  };

  const field = (languageId: number | null, label: React.ReactNode) => {
    const key = rowKey(languageId);
    const value = edited[key] ?? stored(languageId);
    return (
      <Box
        key={key}
        display="flex"
        alignItems="center"
        gap={2}
        py={1}
        borderBottom={1}
        borderColor="divider"
      >
        <Box flex="1 1 40%" display="flex" alignItems="center" gap={1}>
          {label}
        </Box>
        <TextField
          size="small"
          sx={{ flex: '1 1 60%' }}
          value={value}
          disabled={!canEdit}
          placeholder={
            languageId === null
              ? t('voices_project_placeholder', 'ElevenLabs voice ID')
              : projectDefault || t('voices_language_placeholder', 'No default')
          }
          helperText={
            languageId !== null && !value && projectDefault
              ? t('voices_inherited', 'Inherits the project default')
              : undefined
          }
          onChange={(e) =>
            setEdited((prev) => ({ ...prev, [key]: e.target.value }))
          }
          onBlur={() => commit(languageId)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              (e.target as HTMLInputElement).blur();
            }
          }}
          data-cy={
            languageId === null
              ? 'voices-project-default'
              : 'voices-language-voice'
          }
        />
      </Box>
    );
  };

  return (
    <Box mt={2} data-cy="voices-settings">
      <Typography variant="body2" color="text.secondary" mb={2}>
        {t(
          'voices_help',
          'Default ElevenLabs voice for the asset pipeline. A language overrides the project default, and a voice typed into the run dialog overrides both.'
        )}
      </Typography>

      {field(
        null,
        <Typography fontWeight={600}>
          {t('voices_project_default', 'All languages (project default)')}
        </Typography>
      )}

      {languages.map((lang) =>
        field(
          lang.id,
          <>
            {lang.flagEmoji && (
              <FlagImage flagEmoji={lang.flagEmoji} height={18} />
            )}
            <Typography>
              {lang.name} ({lang.tag})
            </Typography>
          </>
        )
      )}
    </Box>
  );
};

export const Voices = () => (
  <ProjectLanguagesProvider>
    <VoicesContent />
  </ProjectLanguagesProvider>
);
