import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';

import { LocalizedDatePipe } from '../core/i18n/format.pipes';
import { LdpService } from '../core/ldp/ldp.service';
import { normalizePath, routeTo } from '../core/ldp/paths';
import { BreadcrumbComponent } from '../shared/breadcrumb.component';
import { Notifier } from '../shared/notifier.service';
import { PropertyTableComponent } from '../shared/property-table.component';

/**
 * A historic version of a resource: read-only, with its properties at that time and, for
 * binaries, a download of the bytes of that version.
 */
@Component({
  selector: 'ui-memento-page',
  imports: [
    BreadcrumbComponent,
    LocalizedDatePipe,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatProgressBarModule,
    PropertyTableComponent,
    RouterLink,
    TranslatePipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ui-breadcrumb [path]="resourcePath()" [suffix]="'memento.crumb' | translate: { version: version() }" />
    @if (memento.isLoading()) {
      <mat-progress-bar mode="indeterminate" [attr.aria-label]="'common.loading' | translate" />
    }
    @if (error(); as message) {
      <p class="ui-error-text" role="alert">{{ message }}</p>
    }
    @if (memento.hasValue()) {
      @let m = memento.value();
      <div class="ui-banner" role="status">
        <mat-icon aria-hidden="true">history</mat-icon>
        <span>
          {{
            (m.datetime ? 'memento.bannerDated' : 'memento.banner')
              | translate: { version: m.versionId, date: m.datetime | l10nDate: 'medium' }
          }}
        </span>
      </div>
      <div class="ui-row ui-gap">
        @if (m.contentUrl) {
          <a mat-flat-button color="primary" [href]="m.contentUrl" download>
            <mat-icon aria-hidden="true">download</mat-icon> {{ 'binary.download' | translate }}
          </a>
        }
        <a mat-stroked-button [routerLink]="versionsRoute()">{{ 'memento.allVersions' | translate }}</a>
        <a mat-button [routerLink]="resourceRoute()">{{ 'memento.current' | translate }}</a>
      </div>
      <mat-card appearance="outlined">
        <mat-card-header><mat-card-title>{{ 'resource.properties' | translate }}</mat-card-title></mat-card-header>
        <mat-card-content>
          <ui-property-table [graph]="m.graph" [subject]="m.subject" [base]="base()" />
        </mat-card-content>
      </mat-card>
    }
  `,
})
export class MementoPageComponent {
  private readonly ldp = inject(LdpService);

  private readonly notifier = inject(Notifier);

  /** Path of the resource (bound from the route). */
  readonly path = input('');

  /** The version identifier (bound from the route). */
  readonly version = input.required<string>();

  protected readonly resourcePath = computed(() => normalizePath(this.path()));

  protected readonly base = computed(() => this.ldp.base());

  protected readonly memento = rxResource({
    params: () => ({ path: this.resourcePath(), version: this.version() }),
    stream: ({ params }) => this.ldp.memento(params.path, params.version),
  });

  protected readonly error = computed(() => {
    const error = this.memento.error();
    return error ? this.notifier.message(error) : undefined;
  });

  protected versionsRoute(): string[] {
    return routeTo('versions', this.resourcePath());
  }

  protected resourceRoute(): string[] {
    return routeTo('browse', this.resourcePath());
  }
}
