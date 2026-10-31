import { HttpErrorResponse } from '@angular/common/http';

/**
 * Builds a readable message from a failed request: the `detail` of an RFC 9457 problem document,
 * a plain text body, or the HTTP status.
 *
 * @param error - what the HTTP client reported
 * @returns the message
 */
export function errorMessage(error: unknown): string {
  // resources wrap loader failures; report the underlying HTTP error
  if (!(error instanceof HttpErrorResponse) && error instanceof Error && error.cause instanceof HttpErrorResponse) {
    return errorMessage(error.cause);
  }
  if (error instanceof HttpErrorResponse) {
    const body: unknown = error.error;
    if (body && typeof body === 'object') {
      const problem = body as { detail?: unknown; title?: unknown };
      if (typeof problem.detail === 'string' && problem.detail) {
        return problem.detail;
      }
      if (typeof problem.title === 'string' && problem.title) {
        return problem.title;
      }
    }
    if (typeof body === 'string' && body.trim()) {
      return parseProblemText(body.trim()) ?? body.trim();
    }
    if (error.status === 0) {
      return 'The platform cannot be reached';
    }
    return `${error.status} ${error.statusText || 'Request failed'}`.trim();
  }
  if (error instanceof Error) {
    return error.message;
  }
  return String(error);
}

function parseProblemText(text: string): string | undefined {
  if (!text.startsWith('{')) {
    return undefined;
  }
  try {
    const problem = JSON.parse(text) as { detail?: unknown; title?: unknown };
    if (typeof problem.detail === 'string') {
      return problem.detail;
    }
    return typeof problem.title === 'string' ? problem.title : undefined;
  } catch {
    return undefined;
  }
}
