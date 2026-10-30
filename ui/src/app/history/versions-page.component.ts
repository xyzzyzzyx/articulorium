import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { RouterLink } from '@angular/router';

import { LdpService } from '../core/ldp/ldp.service';
import { VersionEntry } from '../core/ldp/models';
import { normalizePath, routeTo, segmentsOf } from '../core/ldp/paths';
import { errorMessage } from '../core/ldp/problem';
import { BreadcrumbComponent } from '../shared/breadcrumb.component';
import { Notifier } from '../shared/notifier.service';

/**
 * The versions of a resource (Fedora's `fcr:versions` view), newest first, with a button to
 * record a new version.
 */
@Component({
  selector: 'ui-versions-page',
  imports: [BreadcrumbComponent, DatePipe, MatButtonModule, MatIconModule, MatProgressBarModule, MatTableModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ui-breadcrumb [path]="resourcePath()" suffix="Versions" />
    <header class="ui-page-header">
      <h1>Versions</h1>
      <div class="ui-row">
        <button mat-flat-button color="primary" (click)="createVersion()"><mat-icon>add</mat-icon> Create version</button>
        <a mat-button [routerLink]="resourceRoute()">Back to resource</a>
      </div>
    </header>
    @if (versions.isLoading()) {
      <mat-progress-bar mode="indeterminate" />
    }
    @if (error(); as message) {
      <p class="ui-error-text">{{ message }}</p>
    }
    @if (rows(); as list) {
      @if (list.length === 0) {
        <p class="ui-muted">No versions yet. Binaries get a version with every content change; other resources when you create one.</p>
      } @else {
        <table mat-table [dataSource]="list" class="ui-table">
          <ng-container matColumnDef="id">
            <th mat-header-cell *matHeaderCellDef>Version</th>
            <td mat-cell *matCellDef="let version">
              <a [routerLink]="mementoRoute(version)">{{ version.id }}</a>
            </td>
          </ng-container>
          <ng-container matColumnDef="created">
            <th mat-header-cell *matHeaderCellDef>Created</th>
            <td mat-cell *matCellDef="let version">{{ version.created | date: 'medium' }}</td>
          </ng-container>
          <ng-container matColumnDef="agent">
            <th mat-header-cell *matHeaderCellDef>Agent</th>
            <td mat-cell *matCellDef="let version">{{ version.agent ?? '—' }}</td>
          </ng-container>
          <ng-container matColumnDef="message">
            <th mat-header-cell *matHeaderCellDef>Message</th>
            <td mat-cell *matCellDef="let version">{{ version.message ?? '' }}</td>
          </ng-container>
          <ng-container matColumnDef="content">
            <th mat-header-cell *matHeaderCellDef>Content</th>
            <td mat-cell *matCellDef="let version">
              @if (version.hasContent) {
                <a mat-icon-button [href]="version.uri" download aria-label="Download this version">
                  <mat-icon>download</mat-icon>
                </a>
              }
            </td>
          </ng-container>
          <tr mat-header-row *matHeaderRowDef="columns"></tr>
          <tr mat-row *matRowDef="let row; columns: columns"></tr>
        </table>
      }
    }
  `,
})
export class VersionsPageComponent {
  private readonly ldp = inject(LdpService);

  private readonly notifier = inject(Notifier);

  /** Path of the resource (bound from the route). */
  readonly path = input('');

  protected readonly resourcePath = computed(() => normalizePath(this.path()));

  protected readonly versions = rxResource({
    params: () => this.resourcePath(),
    stream: ({ params }) => this.ldp.versions(params),
  });

  /** Versions, newest first. */
  protected readonly rows = computed<VersionEntry[] | undefined>(() =>
    this.versions.hasValue() ? [...this.versions.value().versions].reverse() : undefined,
  );

  protected readonly error = computed(() => {
    const error = this.versions.error();
    return error ? errorMessage(error) : undefined;
  });

  protected readonly columns = ['id', 'created', 'agent', 'message', 'content'];

  protected resourceRoute(): string[] {
    return routeTo('browse', this.resourcePath());
  }

  /**
   * Route of a version's view.
   *
   * @param version - the version
   * @returns the route commands
   */
  protected mementoRoute(version: VersionEntry): string[] {
    return ['/', 'memento', version.id, ...segmentsOf(this.resourcePath())];
  }

  /** Records a new version and refreshes the list. */
  protected createVersion(): void {
    this.ldp.createVersion(this.resourcePath()).subscribe({
      next: () => {
        this.notifier.success('Version created');
        this.versions.reload();
      },
      error: (error: unknown) => this.notifier.error('Create version', error),
    });
  }
}
