import { describe, expect, it } from 'vitest';
import {
  CREATION_MODES,
  MODE_LABEL,
  normalizeCreationMode,
  upgradeModeForInsert,
  usesAi,
} from './creationMode';

describe('creationMode', () => {
  it('keeps all three authoring modes addressable', () => {
    expect(CREATION_MODES).toEqual(['manual', 'assisted', 'autonomous']);
    expect(CREATION_MODES.map((mode) => MODE_LABEL[mode])).toEqual(['传统写作', 'AI 辅助', '纯 AI']);
  });

  it('only enters the AI workflow for assisted and autonomous modes', () => {
    expect(usesAi('manual')).toBe(false);
    expect(usesAi('assisted')).toBe(true);
    expect(usesAi('autonomous')).toBe(true);
  });

  it('upgrades manual mode when an AI candidate is inserted', () => {
    expect(upgradeModeForInsert('manual')).toBe('assisted');
    expect(upgradeModeForInsert('assisted')).toBe('assisted');
    expect(upgradeModeForInsert('autonomous')).toBe('autonomous');
  });

  it('falls back to manual for corrupted persisted values', () => {
    expect(normalizeCreationMode('assisted')).toBe('assisted');
    expect(normalizeCreationMode('unknown')).toBe('manual');
    expect(normalizeCreationMode(null)).toBe('manual');
  });
});
