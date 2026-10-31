import { DatePipe } from '@angular/common';
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
import { PropertyTableComponent } from '../shared/property-table.component';

/**
 * A historic version of a resource: read-only, with its properties at that time and, for
 * binaries, a download of the bytes of that version.
 */
@Component({
  selector: 'ui-memento-page',
  imports: [
    BreadcrumbComponent,
    DatePipe,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatProgressBarModule,
    PropertyTableComponent,
    RouterLink,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ui-breadcrumb [path]="resourcePath()" [suffix]="'Version ' + version()" />
    @if (memento.isLoading()) {
      <mat-progress-bar mode="indeterminate" />
    }
    @if (error(); as message) {
      <p class="ui-error-text">{{ message }}</p>
    }
    @if (memento.hasValue()) {
      @let m = memento.value();
      <div class="ui-banner" role="status">
        <mat-icon>history</mat-icon>
        <span>
          This is a <strong>historic version</strong>
          ({{ m.versionId }}{{ m.datetime ? ', ' + (m.datetime | date: 'medium') : '' }})
          and cannot be modified.
        </span>
      </div>
      <div class="ui-row ui-gap">
        @if (m.contentUrl) {
          <a mat-flat-button color="primary" [href]="m.contentUrl" download><mat-icon>download</mat-icon> Download content</a>
        }
        <a mat-stroked-button [routerLink]="versionsRoute()">All versions</a>
        <a mat-button [routerLink]="resourceRoute()">Current version</a>
      </div>
      <mat-card appearance="outlined">
        <mat-card-header><mat-card-title>Properties</mat-card-title></mat-card-header>
        <mat-card-content>
          <ui-property-table [graph]="m.graph" [subject]="m.subject" [base]="base()" />
        </mat-card-content>
      </mat-card>
    }
  `,
})
export class MementoPageComponent {
  private readonly ldp = inject(LdpService);

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
    return error ? errorMessage(error) : undefined;
  });

  protected versionsRoute(): string[] {
    return routeTo('versions', this.resourcePath());
  }

  protected resourceRoute(): string[] {
    return routeTo('browse', this.resourcePath());
  }
}
