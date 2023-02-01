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
import { TranslatePipe } from '@ngx-translate/core';

import { ConfigService } from '../core/config.service';
import { I18nService } from '../core/i18n/i18n.service';
import { normalizePath, pathOf, platformUrl, routeTo } from '../core/ldp/paths';
import {
  ACCESS_MODES,
  AccessMode,
  AuthorizationDraft,
  GranteeKind,
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
    TranslatePipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ui-breadcrumb [path]="resourcePath()" [suffix]="'resource.permissions' | translate" />
    <header class="ui-page-header">
      <h1>{{ 'resource.permissions' | translate }}</h1>
      <a mat-button [routerLink]="resourceRoute()">{{ 'common.backToResource' | translate }}</a>
    </header>
    @if (access.isLoading()) {
      <mat-progress-bar mode="indeterminate" [attr.aria-label]="'common.loading' | translate" />
    }
    @if (loadError(); as message) {
      <p class="ui-error-text" role="alert">{{ message }}</p>
    }
    @if (access.value(); as info) {
      <mat-card appearance="outlined">
        <mat-card-content>
          <dl class="ui-facts">
            <dt>{{ 'permissions.you' | translate }}</dt>
            <dd class="ui-permissions-agent">{{ info.agent.webId ?? ('permissions.anonymous' | translate) }}</dd>
            <dt>{{ 'permissions.yourAccess' | translate }}</dt>
            <dd class="ui-permissions-user">{{ modeList(info.userModes) }}</dd>
            <dt>{{ 'permissions.everyone' | translate }}</dt>
            <dd class="ui-permissions-public">{{ modeList(info.publicModes) }}</dd>
            <dt>{{ 'permissions.rules' | translate }}</dt>
            <dd class="ui-permissions-source">
              @if (!info.enforced) {
                {{ 'permissions.disabled' | translate }}
              } @else if (!info.aclResource) {
                {{ 'permissions.repositoryDefault' | translate }}
              } @else if (info.inherited) {
                {{ 'permissions.inheritedFrom' | translate }}
                <a [routerLink]="routeOfIri(info.aclResource)">{{ info.aclResource }}</a>
              } @else {
                {{ 'permissions.own' | translate }}
              }
            </dd>
          </dl>
        </mat-card-content>
      </mat-card>

      @if (info.enforced && info.userModes.includes('control')) {
        <h2>{{ 'permissions.authorizations' | translate }}</h2>
        @if (drafts().length === 0) {
          <p class="ui-muted">{{ 'permissions.noAuthorizations' | translate }}</p>
        }
        <ul class="ui-authorizations">
          @for (draft of drafts(); track $index) {
            <li class="ui-authorization">
              <strong>{{ label(draft) }}</strong>: {{ modeList(draft.modes) }}
              @if (draft.inherit) {
                <span class="ui-pill">{{ 'permissions.inheritedByChildren' | translate }}</span>
              }
              <button
                mat-icon-button
                type="button"
                [attr.aria-label]="'permissions.removeGrant' | translate: { grantee: label(draft) }"
                (click)="remove($index)"
              >
                <mat-icon aria-hidden="true">delete</mat-icon>
              </button>
            </li>
          }
        </ul>

        <mat-card appearance="outlined">
          <mat-card-header><mat-card-title>{{ 'permissions.grant' | translate }}</mat-card-title></mat-card-header>
          <mat-card-content>
            <form class="ui-form ui-grant-form" (ngSubmit)="add()">
              <mat-form-field>
                <mat-label>{{ 'permissions.who' | translate }}</mat-label>
                <mat-select name="kind" [(ngModel)]="kind">
                  <mat-option value="everyone">{{ 'permissions.grantee.everyone' | translate }}</mat-option>
                  <mat-option value="authenticated">{{ 'permissions.grantee.authenticated' | translate }}</mat-option>
                  <mat-option value="agent">{{ 'permissions.grantee.agentKind' | translate }}</mat-option>
                  <mat-option value="group">{{ 'permissions.grantee.groupKind' | translate }}</mat-option>
                </mat-select>
              </mat-form-field>
              @if (kind === 'agent' || kind === 'group') {
                <mat-form-field>
                  <mat-label>{{ (kind === 'agent' ? 'permissions.agentIri' : 'permissions.groupIri') | translate }}</mat-label>
                  <input matInput name="iri" [(ngModel)]="iri" required
                    [placeholder]="kind === 'agent' ? 'info:consul/agent/jane' : 'info:consul/role/editors'" />
                </mat-form-field>
              }
              <div class="ui-row">
                @for (mode of modes; track mode) {
                  <mat-checkbox [name]="'mode-' + mode" [(ngModel)]="selected[mode]">{{ 'access.mode.' + mode | translate }}</mat-checkbox>
                }
              </div>
              <mat-checkbox name="inherit" [(ngModel)]="inherit">{{ 'permissions.inherit' | translate }}</mat-checkbox>
              <button mat-stroked-button type="submit" [disabled]="!canAdd()">
                <mat-icon aria-hidden="true">add</mat-icon> {{ 'common.add' | translate }}
              </button>
            </form>
          </mat-card-content>
        </mat-card>

        @if (busy()) {
          <mat-progress-bar mode="indeterminate" [attr.aria-label]="'common.loading' | translate" />
        }
        <div class="ui-row ui-permissions-actions">
          <button mat-flat-button color="primary" type="button" [disabled]="busy() || !dirty()" (click)="save()">
            {{ 'permissions.save' | translate }}
          </button>
          @if (info.aclResource && !info.inherited) {
            <button mat-button type="button" [disabled]="busy()" (click)="restoreInheritance()">
              {{ 'permissions.removeAcl' | translate }}
            </button>
          }
        </div>
      } @else if (info.enforced) {
        <p class="ui-muted">{{ 'permissions.needControl' | translate }}</p>
      }
    }
  `,
})
export class PermissionsPageComponent {
  private readonly webac = inject(WebAcService);

  private readonly config = inject(ConfigService);

  private readonly notifier = inject(Notifier);

  private readonly i18n = inject(I18nService);

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
    return error ? this.notifier.message(error) : undefined;
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
    switch (draft.kind) {
      case 'everyone':
        return this.i18n.t('permissions.grantee.everyone');
      case 'authenticated':
        return this.i18n.t('permissions.grantee.authenticated');
      case 'agent':
        return this.i18n.t('permissions.grantee.agent', { iri: draft.iri ?? '' });
      case 'group':
        return this.i18n.t('permissions.grantee.group', { iri: draft.iri ?? '' });
    }
  }

  /**
   * Lists access modes in the interface language.
   *
   * @param modes - the modes
   * @returns for example "read, write", or the text for none
   */
  protected modeList(modes: readonly string[]): string {
    return modes.length
      ? modes.map((mode) => this.i18n.t(`access.mode.${mode}`)).join(', ')
      : this.i18n.t('permissions.noModes');
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
        this.notifier.success(this.i18n.t('permissions.saved'));
        this.access.reload();
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.notifier.error(this.i18n.t('permissions.save'), error);
      },
    });
  }

  /** Deletes the resource's own ACL so that it inherits again. */
  protected restoreInheritance(): void {
    this.busy.set(true);
    this.webac.remove(this.resourcePath()).subscribe({
      next: () => {
        this.busy.set(false);
        this.notifier.success(this.i18n.t('permissions.aclRemoved'));
        this.access.reload();
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.notifier.error(this.i18n.t('permissions.removeAcl'), error);
      },
    });
  }
}
