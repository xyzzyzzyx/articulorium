import { Injectable, RESPONSE_INIT, inject } from '@angular/core';

/**
 * Sets the HTTP status of server-rendered pages, so search engines see `404` for missing
 * resources and `503` while the collections are unavailable, not a successful empty page. Does
 * nothing in browsers.
 */
@Injectable({ providedIn: 'root' })
export class ResponseStatusService {
  private readonly response = inject(RESPONSE_INIT, { optional: true });

  /**
   * Sets the status of the response being rendered.
   *
   * @param status - the HTTP status
   */
  set(status: number): void {
    if (this.response) {
      this.response.status = status;
      if (status >= 500) {
        // a short outage must not be cached as the page
        this.response.headers = { ...(this.response.headers as Record<string, string>), 'Cache-Control': 'no-store' };
      }
    }
  }
}
