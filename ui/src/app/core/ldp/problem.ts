import { HttpErrorResponse } from '@angular/common/http';

/** Looks up a translated text (see `I18nService.t`). */
export type Translate = (key: string, params?: Record<string, unknown>) => string;

/** How {@link errorMessage} translates its own texts. */
export interface ErrorLocale {
  /** Looks up texts. */
  readonly t: Translate;
  /** The user interface's locale, compared with the response's `Content-Language`. */
  readonly locale: string;
}

/**
 * Builds a readable message from a failed request: the `detail` of an RFC 9457 problem document,
 * a plain text body, or the HTTP status.
 *
 * With a locale, services' messages written in another language (diagnostics that only exist in
 * the default locale, as `Content-Language` tells) are preceded by a summary in the user's
 * language: the problem's translated `title`, or the user interface's text for the status.
 *
 * @param error - what the HTTP client reported
 * @param locale - translation of the message, when available
 * @returns the message
 */
export function errorMessage(error: unknown, locale?: ErrorLocale): string {
  // resources wrap loader failures; report the underlying HTTP error
  if (!(error instanceof HttpErrorResponse) && error instanceof Error && error.cause instanceof HttpErrorResponse) {
    return errorMessage(error.cause, locale);
  }
  if (error instanceof HttpErrorResponse) {
    const { detail, title } = problemOf(error.error);
    const text = detail ?? title;
    if (text) {
      if (locale && !sameLanguage(error.headers?.get('Content-Language'), locale.locale)) {
        const summary = title && title !== text ? title : statusText(error.status, locale.t);
        return summary === text ? text : `${summary} (${text})`;
      }
      return text;
    }
    if (error.status === 0) {
      return locale ? locale.t('error.unreachable') : 'The platform cannot be reached';
    }
    if (locale) {
      return statusText(error.status, locale.t);
    }
    return `${error.status} ${error.statusText || 'Request failed'}`.trim();
  }
  if (error instanceof Error) {
    return error.message;
  }
  return String(error);
}

/**
 * The user interface's summary of an HTTP error status.
 *
 * @param status - the status code
 * @param t - looks up texts
 * @returns for example "Nothing was found at this address."
 */
export function statusText(status: number, t: Translate): string {
  const key = `error.status.${status}`;
  const text = t(key);
  return text === key ? t('error.status.other') : text;
}

/**
 * Whether a response's `Content-Language` is the user interface's language. A response without
 * the header counts as matching: there is nothing better to show.
 *
 * @param contentLanguage - the header, may be missing
 * @param locale - the user interface's locale
 * @returns `true` when the primary languages match or the header is missing
 */
export function sameLanguage(contentLanguage: string | null | undefined, locale: string): boolean {
  if (!contentLanguage) {
    return true;
  }
  const primary = (tag: string) => tag.trim().toLowerCase().split(/[-_]/)[0];
  return contentLanguage.split(',').some((tag) => primary(tag) === primary(locale));
}

function problemOf(body: unknown): { detail?: string; title?: string } {
  if (body && typeof body === 'object') {
    const problem = body as { detail?: unknown; title?: unknown };
    return {
      detail: typeof problem.detail === 'string' && problem.detail ? problem.detail : undefined,
      title: typeof problem.title === 'string' && problem.title ? problem.title : undefined,
    };
  }
  if (typeof body === 'string' && body.trim()) {
    const text = body.trim();
    return parseProblemText(text) ?? { detail: text };
  }
  return {};
}

function parseProblemText(text: string): { detail?: string; title?: string } | undefined {
  if (!text.startsWith('{')) {
    return undefined;
  }
  try {
    const problem = JSON.parse(text) as unknown;
    const parsed = problemOf(problem && typeof problem === 'object' ? problem : undefined);
    return parsed.detail || parsed.title ? parsed : undefined;
  } catch {
    return undefined;
  }
}
