import { DatePipe, DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';

import { LdpService } from '../core/ldp/ldp.service';
import { normalizePath, routeTo } from '../core/ldp/paths';
import { errorMessage } from '../core/ldp/problem';
import { BreadcrumbComponent } from '../shared/breadcrumb.component';

/**
 * The fixity report of a binary (Fedora's `fcr:fixity`): the stored bytes are re-hashed and the
 * OCFL object validated.
 */
@Component({
  selector: 'ui-fixity-page',
  imports: [BreadcrumbComponent, DatePipe, DecimalPipe, MatButtonModule, MatCardModule, MatIconModule, MatProgressBarModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ui-breadcrumb [path]="resourcePath()" suffix="Fixity" />
    <header class="ui-page-header">
      <h1>Fixity</h1>
      <div class="ui-row">
        <button mat-flat-button color="primary" (click)="report.reload()" [disabled]="report.isLoading()">Check again</button>
        <a mat-button [routerLink]="resourceRoute()">Back to resource</a>
      </div>
    </header>
    @if (report.isLoading()) {
      <mat-progress-bar mode="indeterminate" />
    }
    @if (error(); as message) {
      <p class="ui-error-text">{{ message }}</p>
    }
    @if (report.hasValue()) {
      @let r = report.value();
      <mat-card appearance="outlined">
        <mat-card-content>
          <p class="ui-status" [class.ui-status-ok]="r.status === 'SUCCESS'" [class.ui-status-failed]="r.status !== 'SUCCESS'">
            <mat-icon>{{ r.status === 'SUCCESS' ? 'check_circle' : 'report' }}</mat-icon>
            {{ r.status }}
          </p>
          <dl class="ui-facts">
            <dt>Checked</dt>
            <dd>{{ r.checked | date: 'medium' }}</dd>
            <dt>Storage version</dt>
            <dd>{{ r.storageVersion ?? '—' }}</dd>
            <dt>Size</dt>
            <dd>{{ r.actualSize | number }} bytes (expected {{ r.expectedSize === undefined ? '—' : (r.expectedSize | number) }})</dd>
            <dt>SHA-256</dt>
            <dd class="ui-mono">{{ r.actualSha256 ?? '—' }}</dd>
            <dt>Expected SHA-256</dt>
            <dd class="ui-mono">{{ r.expectedSha256 ?? '—' }}</dd>
            <dt>OCFL object</dt>
            <dd>{{ r.storageValid ? 'valid' : 'invalid' }}</dd>
          </dl>
          @if (r.storageErrors.length > 0) {
            <h3>Storage errors</h3>
            <ul>
              @for (problem of r.storageErrors; track $index) {
                <li>{{ problem }}</li>
              }
            </ul>
          }
        </mat-card-content>
      </mat-card>
    }
  `,
})
export class FixityPageComponent {
  private readonly ldp = inject(LdpService);

  /** Path of the binary (bound from the route). */
  readonly path = input('');

  protected readonly resourcePath = computed(() => normalizePath(this.path()));

  protected readonly report = rxResource({
    params: () => this.resourcePath(),
    stream: ({ params }) => this.ldp.fixity(params),
  });

  protected readonly error = computed(() => {
    const error = this.report.error();
    return error ? errorMessage(error) : undefined;
  });

  protected resourceRoute(): string[] {
    return routeTo('browse', this.resourcePath());
  }
}
