import { DatePipe, JsonPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { RouterLink } from '@angular/router';

import { ConfigService } from '../core/config.service';
import { normalizePath, platformUrl, routeTo } from '../core/ldp/paths';
import { errorMessage } from '../core/ldp/problem';
import { NotificationService } from '../core/notify/notification.service';
import { NOTIFY_PATTERNS, NotificationSummary, buildNotification } from '../core/notify/notify';
import { BreadcrumbComponent } from '../shared/breadcrumb.component';
import { Notifier } from '../shared/notifier.service';

/**
 * The COAR Notify inbox and outbox of a container (community, collection or any container):
 * received notifications with their details, sent notifications with their delivery status, and
 * a form sending a new notification (request a review or endorsement, announce a relationship...).
 */
@Component({
  selector: 'ui-notifications-page',
  imports: [
    BreadcrumbComponent,
    DatePipe,
    FormsModule,
    JsonPipe,
    MatButtonModule,
    MatCardModule,
    MatExpansionModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    RouterLink,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ui-breadcrumb [path]="resourcePath()" suffix="Notifications" />
    <header class="ui-page-header">
      <h1>Notifications</h1>
      <a mat-button [routerLink]="resourceRoute()">Back to resource</a>
    </header>
    <p>
      Inbox (Linked Data Notifications, COAR Notify):
      <a class="ui-inbox-url" [href]="inboxUrl()" target="_blank" rel="noopener">{{ inboxUrl() }}</a>
    </p>
    @if (inbox.isLoading()) {
      <mat-progress-bar mode="indeterminate" />
    }
    @if (inbox.error(); as error) {
      <p class="ui-error-text">{{ message(error) }}</p>
    }

    <h2>Received <span class="ui-badge">{{ inbox.value()?.notifications?.length ?? 0 }}</span></h2>
    <mat-accordion class="ui-inbox">
      @for (item of inbox.value()?.notifications ?? []; track item.id) {
        <mat-expansion-panel (opened)="open(item)">
          <mat-expansion-panel-header>
            <mat-panel-title>{{ item.pattern || item.types.join(' ') }}</mat-panel-title>
            <mat-panel-description>{{ item.origin }} · {{ item.received | date: 'medium' }}</mat-panel-description>
          </mat-expansion-panel-header>
          <dl class="ui-facts">
            <dt>Object</dt><dd>{{ item.object }}</dd>
            <dt>Actor</dt><dd>{{ item.actor ?? '—' }}</dd>
            <dt>Identifier</dt><dd>{{ item.notificationId }}</dd>
          </dl>
          @if (details()[item.id]; as json) {
            <pre class="ui-json">{{ json | json }}</pre>
          }
        </mat-expansion-panel>
      } @empty {
        <p class="ui-muted">No notifications received.</p>
      }
    </mat-accordion>

    <h2>Sent <span class="ui-badge">{{ outbox.value()?.notifications?.length ?? 0 }}</span></h2>
    <ul class="ui-tree ui-outbox">
      @for (item of outbox.value()?.notifications ?? []; track item.id) {
        <li>
          <strong>{{ item.pattern }}</strong> to {{ item.target }} ·
          <span [class.ui-error-text]="item.status === 'FAILED'">{{ item.status }}</span>
          @if (item.statusDetail) {
            <span class="ui-muted"> ({{ item.statusDetail }})</span>
          }
        </li>
      } @empty {
        <li class="ui-muted">No notifications sent.</li>
      }
    </ul>

    <mat-card appearance="outlined">
      <mat-card-header><mat-card-title>Send a notification</mat-card-title></mat-card-header>
      <mat-card-content>
        <form class="ui-form ui-send-notification" (ngSubmit)="send()">
          <mat-form-field>
            <mat-label>Pattern</mat-label>
            <mat-select name="pattern" [(ngModel)]="pattern">
              @for (p of patterns; track p.id) {
                <mat-option [value]="p.id">{{ p.label }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <div class="ui-inline-fields">
            <mat-form-field>
              <mat-label>Target service</mat-label>
              <input matInput name="targetId" required [(ngModel)]="targetId" placeholder="https://review.example.org/" />
            </mat-form-field>
            <mat-form-field>
              <mat-label>Target inbox</mat-label>
              <input matInput name="targetInbox" required [(ngModel)]="targetInbox" placeholder="https://review.example.org/inbox" />
            </mat-form-field>
          </div>
          <div class="ui-inline-fields">
            <mat-form-field>
              <mat-label>Object</mat-label>
              <input matInput name="objectId" [(ngModel)]="objectId" placeholder="(this resource)" />
            </mat-form-field>
            <mat-form-field>
              <mat-label>Cite as (DOI, handle...)</mat-label>
              <input matInput name="citeAs" [(ngModel)]="citeAs" />
            </mat-form-field>
          </div>
          <mat-form-field>
            <mat-label>In reply to (Accept, Reject, Undo)</mat-label>
            <input matInput name="inReplyTo" [(ngModel)]="inReplyTo" />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Summary</mat-label>
            <input matInput name="summary" [(ngModel)]="summary" />
          </mat-form-field>
          @if (busy()) {
            <mat-progress-bar mode="indeterminate" />
          }
          <button mat-flat-button color="primary" type="submit" [disabled]="busy() || !targetId.trim() || !targetInbox.trim()">
            <mat-icon>send</mat-icon> Send
          </button>
        </form>
      </mat-card-content>
    </mat-card>
  `,
})
export class NotificationsPageComponent {
  private readonly notifications = inject(NotificationService);

  private readonly config = inject(ConfigService);

  private readonly notifier = inject(Notifier);

  /** Path of the container (bound from the route). */
  readonly path = input('');

  protected readonly resourcePath = computed(() => normalizePath(this.path()));

  protected readonly resourceRoute = computed(() => routeTo('browse', this.resourcePath()));

  protected readonly inboxUrl = computed(() => platformUrl(this.config.platformBase(), this.resourcePath(), 'fcr:inbox'));

  protected readonly inbox = rxResource({
    params: () => this.resourcePath(),
    stream: ({ params }) => this.notifications.inbox(params),
  });

  protected readonly outbox = rxResource({
    params: () => this.resourcePath(),
    stream: ({ params }) => this.notifications.outbox(params),
  });

  /** Full notifications loaded on demand, by local identifier. */
  protected readonly details = signal<Record<string, unknown>>({});

  protected readonly busy = signal(false);

  protected readonly patterns = NOTIFY_PATTERNS;

  protected pattern = NOTIFY_PATTERNS[0].id;

  protected targetId = '';

  protected targetInbox = '';

  protected objectId = '';

  protected citeAs = '';

  protected inReplyTo = '';

  protected summary = '';

  /**
   * Formats a failure.
   *
   * @param error - the failure
   * @returns the message
   */
  protected message(error: unknown): string {
    return errorMessage(error);
  }

  /**
   * Loads the full notification when its panel opens.
   *
   * @param item - the notification
   */
  protected open(item: NotificationSummary): void {
    if (!item.uri || this.details()[item.id]) {
      return;
    }
    this.notifications.read(item.uri).subscribe({
      next: (json) => this.details.update((details) => ({ ...details, [item.id]: json })),
      error: (error: unknown) => this.notifier.error('Load notification', error),
    });
  }

  /** Sends the notification of the form. */
  protected send(): void {
    const notification = buildNotification(
      {
        pattern: this.pattern,
        targetId: this.targetId,
        targetInbox: this.targetInbox,
        objectId: this.objectId || platformUrl(this.config.platformBase(), this.resourcePath()),
        citeAs: this.citeAs,
        inReplyTo: this.inReplyTo,
        summary: this.summary,
      },
      this.config.config().title,
    );
    this.busy.set(true);
    this.notifications.send(this.resourcePath(), notification).subscribe({
      next: () => {
        this.busy.set(false);
        this.notifier.success('Notification sent');
        this.outbox.reload();
      },
      error: (error: unknown) => {
        this.busy.set(false);
        if (error instanceof HttpErrorResponse && error.status === 502) {
          this.notifier.error('Delivery', 'the target inbox did not accept the notification');
        } else {
          this.notifier.error('Send notification', error);
        }
        this.outbox.reload();
      },
    });
  }
}
