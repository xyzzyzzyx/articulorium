import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';

import { LocalizedDatePipe, LocalizedNumberPipe } from '../core/i18n/format.pipes';
import { LdpService } from '../core/ldp/ldp.service';
import { normalizePath, routeTo } from '../core/ldp/paths';
import { BreadcrumbComponent } from '../shared/breadcrumb.component';
import { Notifier } from '../shared/notifier.service';

/**
 * The fixity report of a binary (Fedora's `fcr:fixity`): the stored bytes are re-hashed and the
 * OCFL object validated.
 */
@Component({
  selector: 'ui-fixity-page',
  imports: [
    BreadcrumbComponent,
    LocalizedDatePipe,
    LocalizedNumberPipe,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatProgressBarModule,
    RouterLink,
    TranslatePipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ui-breadcrumb [path]="resourcePath()" [suffix]="'fixity.title' | translate" />
    <header class="ui-page-header">
      <h1>{{ 'fixity.title' | translate }}</h1>
      <div class="ui-row">
        <button mat-flat-button color="primary" (click)="report.reload()" [disabled]="report.isLoading()">
          {{ 'fixity.checkAgain' | translate }}
        </button>
        <a mat-button [routerLink]="resourceRoute()">{{ 'common.backToResource' | translate }}</a>
      </div>
    </header>
    @if (report.isLoading()) {
      <mat-progress-bar mode="indeterminate" [attr.aria-label]="'common.loading' | translate" />
    }
    @if (error(); as message) {
      <p class="ui-error-text" role="alert">{{ message }}</p>
    }
    @if (report.hasValue()) {
      @let r = report.value();
      <mat-card appearance="outlined">
        <mat-card-content>
          <p class="ui-status" [class.ui-status-ok]="r.status === 'SUCCESS'" [class.ui-status-failed]="r.status !== 'SUCCESS'">
            <mat-icon aria-hidden="true">{{ r.status === 'SUCCESS' ? 'check_circle' : 'report' }}</mat-icon>
            {{ (r.status === 'SUCCESS' ? 'fixity.success' : 'fixity.failure') | translate }}
          </p>
          <dl class="ui-facts">
            <dt>{{ 'fixity.checked' | translate }}</dt>
            <dd>{{ r.checked | l10nDate: 'medium' }}</dd>
            <dt>{{ 'fixity.storageVersion' | translate }}</dt>
            <dd>{{ r.storageVersion ?? ('common.noValue' | translate) }}</dd>
            <dt>{{ 'binary.size' | translate }}</dt>
            <dd>
              {{
                'fixity.size'
                  | translate
                    : {
                        actual: r.actualSize | l10nNumber,
                        expected: r.expectedSize === undefined ? ('common.noValue' | translate) : (r.expectedSize | l10nNumber),
                      }
              }}
            </dd>
            <dt>SHA-256</dt>
            <dd class="ui-mono">{{ r.actualSha256 ?? ('common.noValue' | translate) }}</dd>
            <dt>{{ 'fixity.expectedSha256' | translate }}</dt>
            <dd class="ui-mono">{{ r.expectedSha256 ?? ('common.noValue' | translate) }}</dd>
            <dt>{{ 'fixity.ocflObject' | translate }}</dt>
            <dd>{{ (r.storageValid ? 'fixity.valid' : 'fixity.invalid') | translate }}</dd>
          </dl>
          @if (r.storageErrors.length > 0) {
            <h3>{{ 'fixity.storageErrors' | translate }}</h3>
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

  private readonly notifier = inject(Notifier);

  /** Path of the binary (bound from the route). */
  readonly path = input('');

  protected readonly resourcePath = computed(() => normalizePath(this.path()));

  protected readonly report = rxResource({
    params: () => this.resourcePath(),
    stream: ({ params }) => this.ldp.fixity(params),
  });

  protected readonly error = computed(() => {
    const error = this.report.error();
    return error ? this.notifier.message(error) : undefined;
  });

  protected resourceRoute(): string[] {
    return routeTo('browse', this.resourcePath());
  }
}
