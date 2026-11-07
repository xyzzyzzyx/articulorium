import { HarvestSource, actionState, anyRunning, elapsed, formatDuration, severity } from './harvest';

const SOURCE: HarvestSource = {
  id: 'repo',
  type: 'oai-pmh',
  url: 'https://repo.test/oai',
  enabled: true,
  schedule: null,
  capabilities: { incremental: true, deletions: true },
  running: null,
  reports: [],
};

const RUNNING = { mode: 'FULL' as const, startedAt: '2026-11-06T10:00:00Z', harvested: 10, failed: 0, cancelled: false };

describe('harvest', () => {
  it('offers starting idle enabled sources to operators only', () => {
    expect(actionState(SOURCE, 'incremental', true)).toEqual({ allowed: true });
    expect(actionState(SOURCE, 'full', true)).toEqual({ allowed: true });
    expect(actionState(SOURCE, 'full', false)).toEqual({ allowed: false, reason: 'permission' });
    expect(actionState(SOURCE, 'cancel', false)).toEqual({ allowed: false, reason: 'permission' });
  });

  it('refuses harvests of disabled or running sources and incremental harvests of sources that cannot list changes', () => {
    expect(actionState({ ...SOURCE, enabled: false }, 'full', true)).toEqual({ allowed: false, reason: 'disabled' });
    expect(actionState({ ...SOURCE, running: RUNNING }, 'incremental', true)).toEqual({ allowed: false, reason: 'running' });
    const vivo = { ...SOURCE, capabilities: { incremental: false, deletions: true } };
    expect(actionState(vivo, 'incremental', true)).toEqual({ allowed: false, reason: 'notIncremental' });
    expect(actionState(vivo, 'full', true)).toEqual({ allowed: true });
  });

  it('offers cancelling running harvests until they are asked to stop', () => {
    expect(actionState(SOURCE, 'cancel', true)).toEqual({ allowed: false, reason: 'notRunning' });
    expect(actionState({ ...SOURCE, running: RUNNING }, 'cancel', true)).toEqual({ allowed: true });
    expect(actionState({ ...SOURCE, running: { ...RUNNING, cancelled: true } }, 'cancel', true)).toEqual({
      allowed: false,
      reason: 'stopping',
    });
    // a disabled source's running harvest can still be stopped
    expect(actionState({ ...SOURCE, enabled: false, running: RUNNING }, 'cancel', true).allowed).toBe(true);
  });

  it('shows only complete harvests as successes', () => {
    expect(severity('COMPLETED')).toBe('success');
    expect(severity('COMPLETED_WITH_ERRORS')).toBe('warning');
    expect(severity('FAILED')).toBe('error');
    expect(severity('CANCELLED')).toBe('neutral');
  });

  it('tells whether a harvest runs', () => {
    expect(anyRunning(undefined)).toBe(false);
    expect(anyRunning([SOURCE])).toBe(false);
    expect(anyRunning([SOURCE, { ...SOURCE, id: 'b', running: RUNNING }])).toBe(true);
  });

  it('measures and formats durations', () => {
    expect(elapsed('2026-11-06T10:00:00Z', '2026-11-06T10:00:42.500Z')).toBe(42_500);
    expect(elapsed('2026-11-06T10:00:01Z', '2026-11-06T10:00:00Z')).toBe(0);
    expect(elapsed('not a date', '2026-11-06T10:00:00Z')).toBeUndefined();
    expect(formatDuration(400, 'en')).toBe('0.4 sec');
    expect(formatDuration(42_500, 'en')).toBe('43 sec');
    expect(formatDuration(125_000, 'en')).toBe('2 min 5 sec');
    expect(formatDuration(3_725_000, 'en')).toBe('1 hr 2 min');
    expect(formatDuration(125_000, 'de')).toContain('2 Min.');
  });
});
