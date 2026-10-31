import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ConfigService } from '../config.service';
import { platformUrl } from '../ldp/paths';
import { NotificationList, NotificationSummary } from './notify';

/** Media type of notifications. */
export const JSON_LD = 'application/ld+json';

/**
 * COAR Notify inboxes and outboxes of containers (`fcr:inbox`, `fcr:outbox`).
 */
@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly http = inject(HttpClient);

  private readonly config = inject(ConfigService);

  /**
   * Lists the notifications a container received.
   *
   * @param path - the container path
   * @returns the inbox
   */
  inbox(path: string): Observable<NotificationList> {
    return this.http.get<NotificationList>(platformUrl(this.config.platformBase(), path, 'fcr:inbox'), {
      headers: { Accept: 'application/json' },
    });
  }

  /**
   * Lists the notifications sent on behalf of a container.
   *
   * @param path - the container path
   * @returns the outbox
   */
  outbox(path: string): Observable<NotificationList> {
    return this.http.get<NotificationList>(platformUrl(this.config.platformBase(), path, 'fcr:outbox'), {
      headers: { Accept: 'application/json' },
    });
  }

  /**
   * Reads a received notification as JSON-LD.
   *
   * @param uri - its IRI in the inbox
   * @returns the notification
   */
  read(uri: string): Observable<Record<string, unknown>> {
    return this.http.get<Record<string, unknown>>(uri, { headers: { Accept: JSON_LD } });
  }

  /**
   * Sends a notification on behalf of a container.
   *
   * @param path - the container path
   * @param notification - the notification
   * @returns the recorded delivery (a failed delivery is an error response carrying it)
   */
  send(path: string, notification: Record<string, unknown>): Observable<NotificationSummary> {
    return this.http.post<NotificationSummary>(platformUrl(this.config.platformBase(), path, 'fcr:outbox'), notification, {
      headers: { 'Content-Type': JSON_LD },
    });
  }
}
