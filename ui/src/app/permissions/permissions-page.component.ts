import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { RouterLink } from '@angular/router';

import { ConfigService } from '../core/config.service';
import { normalizePath, pathOf, platformUrl, routeTo } from '../core/ldp/paths';
import { errorMessage } from '../core/ldp/problem';
import {
  ACCESS_MODES,
  AccessMode,
  AuthorizationDraft,
  GranteeKind,
  granteeLabel,
  toDrafts,
} from '../core/webac/webac';
import { WebAcService } from '../core/webac/webac.service';
import { BreadcrumbComponent } from '../shared/breadcrumb.component';
import { Notifier } from '../shared/notifier.service';

/**
 * The permissions of a resource (its WebAC access control list, `fcr:acl`).
 *
 * <p>Shows the signed-in agent and its access, where the effective authorizations come from (the
 * resource's own ACL, an ancestor's inherited through `acl:default`, or the repository default)
 * and, for agents with `acl:Control`, lets them grant and revoke access. Saving writes the
 * resource's own ACL; resources that inherited start from a copy of the inherited
 * authorizations, so nobody loses access by accident. Removing the ACL restores inheritance.</p>
 */
@Component({
  selector: 'ui-permissions-page',
  imports: [
    BreadcrumbComponent,
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    RouterLink,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ui-breadcrumb [path]="resourcePath()" suffix="Permissions" />
    <header class="ui-page-header">
      <h1>Permissions</h1>
      <a mat-button [routerLink]="resourceRoute()">Back to resource</a>
    </header>
    @if (access.isLoading()) {
      <mat-progress-bar mode="indeterminate" />
    }
    @if (loadError(); as message) {
      <p class="ui-error-text">{{ message }}</p>
    }
    @if (access.value(); as info) {
      <mat-card appearance="outlined">
        <mat-card-content>
          <dl class="ui-facts">
            <dt>You</dt>
            <dd class="ui-permissions-agent">{{ info.agent.webId ?? 'Anonymous' }}</dd>
            <dt>Your access</dt>
            <dd class="ui-permissions-user">{{ info.userModes.join(', ') || 'none' }}</dd>
            <dt>Everyone</dt>
            <dd class="ui-permissions-public">{{ info.publicModes.join(', ') || 'none' }}</dd>
            <dt>Rules</dt>
            <dd class="ui-permissions-source">
              @if (!info.enforced) {
                Access control is disabled on this repository.
              } @else if (!info.aclResource) {
                Repository default (no access control list on this resource or its ancestors)
              } @else if (info.inherited) {
                Inherited from <a [routerLink]="routeOfIri(info.aclResource)">{{ info.aclResource }}</a>
              } @else {
                This resource's own access control list
              }
            </dd>
          </dl>
        </mat-card-content>
      </mat-card>

      @if (info.enforced && info.userModes.includes('control')) {
        <h2>Authorizations</h2>
        @if (drafts().length === 0) {
          <p class="ui-muted">No authorizations: nobody can access this resource.</p>
        }
        <ul class="ui-authorizations">
          @for (draft of drafts(); track $index) {
            <li class="ui-authorization">
              <strong>{{ label(draft) }}</strong>: {{ draft.modes.join(', ') }}
              @if (draft.inherit) {
                <span class="ui-pill">inherited by children</span>
              }
              <button mat-icon-button type="button" [attr.aria-label]="'Remove ' + label(draft)" (click)="remove($index)">
                <mat-icon>delete</mat-icon>
              </button>
            </li>
          }
        </ul>

        <mat-card appearance="outlined">
          <mat-card-header><mat-card-title>Grant access</mat-card-title></mat-card-header>
          <mat-card-content>
            <form class="ui-form ui-grant-form" (ngSubmit)="add()">
              <mat-form-field>
                <mat-label>Who</mat-label>
                <mat-select name="kind" [(ngModel)]="kind">
                  <mat-option value="everyone">Everyone</mat-option>
                  <mat-option value="authenticated">Authenticated agents</mat-option>
                  <mat-option value="agent">Agent</mat-option>
                  <mat-option value="group">Group</mat-option>
                </mat-select>
              </mat-form-field>
              @if (kind === 'agent' || kind === 'group') {
                <mat-form-field>
                  <mat-label>{{ kind === 'agent' ? 'Agent IRI' : 'Group IRI' }}</mat-label>
                  <input matInput name="iri" [(ngModel)]="iri" required
                    [placeholder]="kind === 'agent' ? 'info:consul/agent/jane' : 'info:consul/role/editors'" />
                </mat-form-field>
              }
              <div class="ui-row">
                @for (mode of modes; track mode) {
                  <mat-checkbox [name]="'mode-' + mode" [(ngModel)]="selected[mode]">{{ mode }}</mat-checkbox>
                }
              </div>
              <mat-checkbox name="inherit" [(ngModel)]="inherit">Children inherit this authorization</mat-checkbox>
              <button mat-stroked-button type="submit" [disabled]="!canAdd()"><mat-icon>add</mat-icon> Add</button>
            </form>
          </mat-card-content>
        </mat-card>

        @if (busy()) {
          <mat-progress-bar mode="indeterminate" />
        }
        <div class="ui-row ui-permissions-actions">
          <button mat-flat-button color="primary" type="button" [disabled]="busy() || !dirty()" (click)="save()">
            Save permissions
          </button>
          @if (info.aclResource && !info.inherited) {
            <button mat-button type="button" [disabled]="busy()" (click)="restoreInheritance()">Remove access control list</button>
          }
        </div>
      } @else if (info.enforced) {
        <p class="ui-muted">You need control access (acl:Control) to see and change the authorizations.</p>
      }
    }
  `,
})
export class PermissionsPageComponent {
  private readonly webac = inject(WebAcService);

  private readonly config = inject(ConfigService);

  private readonly notifier = inject(Notifier);

  /** Path of the resource (bound from the route). */
  readonly path = input('');

  protected readonly resourcePath = computed(() => normalizePath(this.path()));

  protected readonly resourceRoute = computed(() => routeTo('browse', this.resourcePath()));

  protected readonly access = rxResource({
    params: () => this.resourcePath(),
    stream: ({ params }) => this.webac.access(params),
  });

  protected readonly loadError = computed(() => {
    const error = this.access.error();
    return error ? errorMessage(error) : undefined;
  });

  /** The authorizations being edited. */
  protected readonly drafts = signal<AuthorizationDraft[]>([]);

  protected readonly dirty = signal(false);

  protected readonly busy = signal(false);

  protected readonly modes = ACCESS_MODES;

  protected kind: GranteeKind = 'agent';

  protected iri = '';

  protected selected: Record<AccessMode, boolean> = { read: true, write: false, append: false, control: false };

  protected inherit = true;

  constructor() {
    effect(() => {
      const info = this.access.value();
      this.drafts.set(info ? info.authorizations.flatMap(toDrafts) : []);
      this.dirty.set(false);
    });
  }

  /**
   * Describes who a draft is for.
   *
   * @param draft - the draft
   * @returns the label
   */
  protected label(draft: AuthorizationDraft): string {
    return granteeLabel(draft);
  }

  /**
   * Returns the browse route of a server-relative IRI reported by the WebAC endpoint.
   *
   * @param iri - for example `/books`
   * @returns the route
   */
  protected routeOfIri(iri: string): string[] {
    return routeTo('browse', pathOf(this.config.platformBase(), `${this.config.platformBase()}${normalizePath(iri)}`) ?? '');
  }

  /** Whether the grant form is complete. */
  protected canAdd(): boolean {
    const needsIri = this.kind === 'agent' || this.kind === 'group';
    return (!needsIri || this.iri.trim().length > 0) && ACCESS_MODES.some((mode) => this.selected[mode]);
  }

  /** Adds the authorization of the grant form. */
  protected add(): void {
    if (!this.canAdd()) {
      return;
    }
    const draft: AuthorizationDraft = {
      kind: this.kind,
      iri: this.kind === 'agent' || this.kind === 'group' ? this.iri.trim() : undefined,
      modes: ACCESS_MODES.filter((mode) => this.selected[mode]),
      inherit: this.inherit,
    };
    this.drafts.update((drafts) => [...drafts, draft]);
    this.dirty.set(true);
    this.iri = '';
  }

  /**
   * Removes an authorization.
   *
   * @param index - its position
   */
  protected remove(index: number): void {
    this.drafts.update((drafts) => drafts.filter((_, i) => i !== index));
    this.dirty.set(true);
  }

  /** Stores the authorizations as the resource's own ACL. */
  protected save(): void {
    const path = this.resourcePath();
    this.busy.set(true);
    this.webac.save(path, platformUrl(this.config.platformBase(), path), this.drafts()).subscribe({
      next: () => {
        this.busy.set(false);
        this.notifier.success('Permissions saved');
        this.access.reload();
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.notifier.error('Save permissions', error);
      },
    });
  }

  /** Deletes the resource's own ACL so that it inherits again. */
  protected restoreInheritance(): void {
    this.busy.set(true);
    this.webac.remove(this.resourcePath()).subscribe({
      next: () => {
        this.busy.set(false);
        this.notifier.success('Access control list removed');
        this.access.reload();
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.notifier.error('Remove access control list', error);
      },
    });
  }
}
