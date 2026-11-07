/** How a harvest reads its source: only what changed since the checkpoint, or everything. */
export type HarvestMode = 'INCREMENTAL' | 'FULL';

/** How a harvest ended (the harvest service's `HarvestOutcome`). */
export type HarvestOutcome = 'COMPLETED' | 'COMPLETED_WITH_ERRORS' | 'FAILED' | 'CANCELLED';

/** A record that could not be harvested or ingested. */
export interface RecordFailure {
  /** The record's identifier in its source (or where it was, when unreadable). */
  readonly identifier: string;
  /** Why, as the harvest service reports it (English, no credentials). */
  readonly reason: string;
}

/** What a finished harvest did (the harvest service's `HarvestReport`). */
export interface HarvestReport {
  readonly sourceId: string;
  readonly type: string;
  readonly mode: HarvestMode;
  readonly outcome: HarvestOutcome;
  readonly startedAt: string;
  readonly finishedAt: string;
  /** Start of the incremental window; `null` for a full harvest. */
  readonly from: string | null;
  /** The checkpoint saved for the next incremental harvest; `null` when it did not move. */
  readonly checkpoint: string | null;
  readonly pages: number;
  /** Records the source listed (ingested, skipped or failed). */
  readonly harvested: number;
  readonly created: number;
  readonly updated: number;
  readonly deleted: number;
  /** Deleted records that had no copy. */
  readonly skipped: number;
  readonly failed: number;
  /** The first failures (the service keeps a bounded number). */
  readonly failures: readonly RecordFailure[];
  /** Why the harvest failed or was cancelled. */
  readonly message: string | null;
}

/** A harvest in progress. */
export interface RunningHarvest {
  readonly mode: HarvestMode;
  readonly startedAt: string;
  /** Records listed so far. */
  readonly harvested: number;
  /** Records failed so far. */
  readonly failed: number;
  /** Whether it was asked to stop. */
  readonly cancelled: boolean;
}

/** A configured source and its state (the harvest service's `SourceStatus`). */
export interface HarvestSource {
  readonly id: string;
  /** `oai-pmh`, `dspace`, `fedora` or `vivo`. */
  readonly type: string;
  /** The source's endpoint, without credentials. */
  readonly url: string;
  readonly enabled: boolean;
  /** Cron expression of scheduled incremental harvests; `null` for on-demand only. */
  readonly schedule: string | null;
  readonly capabilities: {
    /** Whether the source lists only changed records; when not, every harvest is a full harvest. */
    readonly incremental: boolean;
    /** Whether the source reports deleted records, so their copies are removed. */
    readonly deletions: boolean;
  };
  readonly running: RunningHarvest | null;
  /** The latest reports, the most recent first. */
  readonly reports: readonly HarvestReport[];
}

/** The answer of `GET sources`. */
export interface HarvestSources {
  readonly sources: readonly HarvestSource[];
  /** Whether the user may start and cancel harvests. */
  readonly operate: boolean;
}

/** An action on a source. */
export type HarvestAction = 'incremental' | 'full' | 'cancel';

/** Whether an action is offered, and why not (a translation key below `harvest.why`). */
export interface ActionState {
  readonly allowed: boolean;
  readonly reason?: 'permission' | 'disabled' | 'running' | 'notRunning' | 'notIncremental' | 'stopping';
}

/**
 * Tells whether an action may be offered for a source. The harvest service decides again (and
 * refuses with 409 when the state changed meanwhile); this only avoids offering what would fail.
 *
 * @param source - the source
 * @param action - the action
 * @param operate - whether the user may start and cancel harvests
 * @returns whether it is offered, with the reason when not
 */
export function actionState(source: HarvestSource, action: HarvestAction, operate: boolean): ActionState {
  if (!operate) {
    return { allowed: false, reason: 'permission' };
  }
  if (action === 'cancel') {
    if (!source.running) {
      return { allowed: false, reason: 'notRunning' };
    }
    return source.running.cancelled ? { allowed: false, reason: 'stopping' } : { allowed: true };
  }
  if (!source.enabled) {
    return { allowed: false, reason: 'disabled' };
  }
  if (source.running) {
    return { allowed: false, reason: 'running' };
  }
  if (action === 'incremental' && !source.capabilities.incremental) {
    return { allowed: false, reason: 'notIncremental' };
  }
  return { allowed: true };
}

/** How an outcome is presented. */
export type Severity = 'success' | 'warning' | 'error' | 'neutral';

/**
 * The presentation of an outcome: only a harvest that ingested every record is a success.
 *
 * @param outcome - the outcome
 * @returns its severity
 */
export function severity(outcome: HarvestOutcome): Severity {
  switch (outcome) {
    case 'COMPLETED':
      return 'success';
    case 'COMPLETED_WITH_ERRORS':
      return 'warning';
    case 'FAILED':
      return 'error';
    default:
      return 'neutral';
  }
}

/** Material icon of each outcome. */
export const OUTCOME_ICONS: Readonly<Record<HarvestOutcome, string>> = {
  COMPLETED: 'check_circle',
  COMPLETED_WITH_ERRORS: 'warning',
  FAILED: 'error',
  CANCELLED: 'cancel',
};

/**
 * Tells whether any source is being harvested.
 *
 * @param sources - the sources
 * @returns `true` while a harvest runs
 */
export function anyRunning(sources: readonly HarvestSource[] | undefined): boolean {
  return (sources ?? []).some((source) => source.running !== null);
}

/**
 * The time between two instants.
 *
 * @param from - the start (ISO 8601)
 * @param to - the end (ISO 8601), now when omitted
 * @returns milliseconds, never negative; `undefined` when an instant is unreadable
 */
export function elapsed(from: string, to?: string): number | undefined {
  const start = Date.parse(from);
  const end = to === undefined ? Date.now() : Date.parse(to);
  if (Number.isNaN(start) || Number.isNaN(end)) {
    return undefined;
  }
  return Math.max(0, end - start);
}

/**
 * Formats a duration with the locale's units, such as "1 hr, 2 min, 5 sec" or "0.4 sec".
 *
 * @param milliseconds - the duration
 * @param locale - the user interface's locale
 * @returns the text
 */
export function formatDuration(milliseconds: number, locale: string): string {
  const unit = (value: number, name: 'hour' | 'minute' | 'second', digits = 0) =>
    new Intl.NumberFormat(locale, { style: 'unit', unit: name, unitDisplay: 'short', maximumFractionDigits: digits }).format(
      value,
    );
  if (milliseconds < 60_000) {
    return unit(milliseconds / 1000, 'second', milliseconds < 10_000 ? 1 : 0);
  }
  const total = Math.round(milliseconds / 1000);
  const parts: string[] = [];
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  if (hours > 0) {
    parts.push(unit(hours, 'hour'));
  }
  if (minutes > 0 || hours > 0) {
    parts.push(unit(minutes, 'minute'));
  }
  if (hours === 0) {
    parts.push(unit(seconds, 'second'));
  }
  return new Intl.ListFormat(locale, { style: 'narrow', type: 'unit' }).format(parts);
}
