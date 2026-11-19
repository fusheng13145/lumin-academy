import { describe, expect, it } from 'vitest';
import {
  canRestore,
  cumulativeReadSeconds,
  scrollRatioToBasisPoints,
  shouldSave,
} from './readingProgressSync';

describe('readingProgressSync', () => {
  it('saves on force regardless of delta (chapter leave / reader close)', () => {
    expect(shouldSave(5000, 5010, true)).toBe('save');
    expect(shouldSave(null, 0, true)).toBe('save');
  });

  it('skips sub-1% scroll noise and saves at 1% delta', () => {
    expect(shouldSave(5000, 5090, false)).toBe('skip');
    expect(shouldSave(5000, 5100, false)).toBe('save');
    expect(shouldSave(5000, 4900, false)).toBe('save');
  });

  it('first save only when position is meaningful', () => {
    expect(shouldSave(null, 0, false)).toBe('skip');
    expect(shouldSave(null, 120, false)).toBe('save');
  });

  it('cumulates readSeconds and floors negatives (server replaces whole value)', () => {
    expect(cumulativeReadSeconds(300, 40)).toBe(340);
    expect(cumulativeReadSeconds(0, 25)).toBe(25);
    expect(cumulativeReadSeconds(-5, 40)).toBe(40);
    expect(cumulativeReadSeconds(300, -999)).toBe(300);
  });

  it('restores position only within the same published version', () => {
    expect(canRestore('v1', 'v1')).toBe(true);
    expect(canRestore('v1', 'v2')).toBe(false);
    expect(canRestore(null, 'v1')).toBe(false);
    expect(canRestore('v1', null)).toBe(false);
    expect(canRestore(undefined, undefined)).toBe(false);
  });

  it('maps scroll ratio to clamped basis points', () => {
    expect(scrollRatioToBasisPoints(0)).toBe(0);
    expect(scrollRatioToBasisPoints(0.4321)).toBe(4321);
    expect(scrollRatioToBasisPoints(1.5)).toBe(10000);
    expect(scrollRatioToBasisPoints(-1)).toBe(0);
    expect(scrollRatioToBasisPoints(Number.NaN)).toBe(0);
  });
});
