import { transcriptKeyLink } from './AssetTranscript';

describe('transcriptKeyLink', () => {
  it('deep-links to the single-key view by key id', () => {
    const link = transcriptKeyLink(7, 42);
    expect(link).toContain('/projects/7/translations/single');
    // id (not key name) — namespace-proof and branch-aware
    expect(link).toContain('id=42');
  });

  it('does not link by key name', () => {
    expect(transcriptKeyLink(1, 2)).not.toContain('key=');
  });
});
