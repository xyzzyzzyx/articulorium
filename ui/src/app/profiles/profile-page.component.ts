import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Router, RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { filter, map, switchMap } from 'rxjs';

import { ConfigService } from '../core/config.service';
import { I18nService } from '../core/i18n/i18n.service';
import { LdpService } from '../core/ldp/ldp.service';
import { ResourceView } from '../core/ldp/models';
import { childPath, routeTo } from '../core/ldp/paths';
import { DOI_BASE, ORCID_BASE, Profile, displayName, isProfile, readProfile } from '../core/profile/profile';
import { allows } from '../core/webac/webac';
import { ConfirmData, ConfirmDialogComponent } from '../shared/confirm-dialog.component';
import { Notifier } from '../shared/notifier.service';

/** A profile as shown. */
interface Shown {
  readonly view: ResourceView;
  /** The profile, or `undefined` when the resource is not a profile. */
  readonly profile?: Profile;
  readonly name: string;
  readonly canEdit: boolean;
}

/**
 * Shows an academic profile: identity and contact details, overview, research, positions,
 * education and publications, with links to edit and delete it.
 */
@Component({
  selector: 'ui-profile-page',
  imports: [MatButtonModule, MatChipsModule, MatIconModule, MatProgressBarModule, RouterLink, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .ui-profile-section {
      margin-block: 24px 8px;
    }
    .ui-profile-entries {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .ui-profile-entries li {
      margin-block: 8px;
    }
    .ui-profile-overview {
      white-space: pre-line;
      max-width: 72ch;
    }
  `,
  template: `
    <nav class="ui-breadcrumb" [attr.aria-label]="'breadcrumb.label' | translate">
      <a [routerLink]="['/profiles']"><mat-icon inline aria-hidden="true">badge</mat-icon> {{ 'nav.profiles' | translate }}</a> /
      <span aria-current="page">{{ shown.value()?.name ?? id() }}</span>
    </nav>
    @if (shown.isLoading()) {
      <mat-progress-bar mode="indeterminate" [attr.aria-label]="'common.loading' | translate" />
    }
    @if (loadError(); as message) {
      <p class="ui-error-text" role="alert">{{ message }}</p>
    }
    @if (shown.value(); as s) {
      <header class="ui-page-header">
        <div>
          <h1>{{ s.name }}</h1>
          @if (s.profile; as p) {
            @if (p.preferredTitle) {
              <p class="ui-muted ui-profile-title">{{ p.preferredTitle }}</p>
            }
            <span class="ui-pill ui-profile-type">{{ 'profiles.personType.' + p.personType | translate }}</span>
          }
        </div>
        <div class="ui-row">
          @if (s.canEdit && s.profile) {
            <a mat-flat-button color="primary" class="ui-edit-profile" [routerLink]="['/profiles', id(), 'edit']">
              <mat-icon aria-hidden="true">edit</mat-icon> {{ 'profiles.edit' | translate }}
            </a>
          }
          <a mat-stroked-button [routerLink]="routeTo('browse', s.view.path)">
            <mat-icon aria-hidden="true">folder_open</mat-icon> {{ 'profiles.openResource' | translate }}
          </a>
          <a mat-stroked-button [routerLink]="routeTo('versions', s.view.path)">
            <mat-icon aria-hidden="true">history</mat-icon> {{ 'resource.versions' | translate }}
          </a>
          @if (s.canEdit) {
            <button mat-button type="button" color="warn" class="ui-delete-profile" (click)="remove(s)">
              <mat-icon aria-hidden="true">delete</mat-icon> {{ 'common.delete' | translate }}
            </button>
          }
        </div>
      </header>

      @if (s.profile; as p) {
        <dl class="ui-facts ui-profile-contact">
          @if (p.email) {
            <dt>{{ 'profiles.field.email' | translate }}</dt>
            <dd><a [href]="'mailto:' + p.email">{{ p.email }}</a></dd>
          }
          @if (p.phone) {
            <dt>{{ 'profiles.field.phone' | translate }}</dt>
            <dd><a [href]="telUrl(p.phone)">{{ p.phone }}</a></dd>
          }
          @if (p.orcid) {
            <dt>{{ 'profiles.field.orcid' | translate }}</dt>
            <dd><a class="ui-profile-orcid" [href]="orcidBase + p.orcid" target="_blank" rel="noopener">{{ p.orcid }}</a></dd>
          }
          @for (website of p.websites; track $index) {
            <dt>{{ website.label || ('profiles.field.website' | translate) }}</dt>
            <dd><a [href]="safeUrl(website.url)" target="_blank" rel="noopener">{{ website.url }}</a></dd>
          }
        </dl>

        @if (p.overview) {
          <h2 class="ui-profile-section">{{ 'profiles.section.overview' | translate }}</h2>
          <p class="ui-profile-overview">{{ p.overview }}</p>
        }

        @if (p.researchAreas.length > 0 || p.keywords.length > 0) {
          <h2 class="ui-profile-section">{{ 'profiles.section.research' | translate }}</h2>
          @if (p.researchAreas.length > 0) {
            <mat-chip-set class="ui-profile-areas" [attr.aria-label]="'profiles.field.researchAreas' | translate">
              @for (area of p.researchAreas; track area) {
                <mat-chip highlighted>{{ area }}</mat-chip>
              }
            </mat-chip-set>
          }
          @if (p.keywords.length > 0) {
            <mat-chip-set class="ui-profile-keywords" [attr.aria-label]="'profiles.field.keywords' | translate">
              @for (keyword of p.keywords; track keyword) {
                <mat-chip>{{ keyword }}</mat-chip>
              }
            </mat-chip-set>
          }
        }

        @if (p.positions.length > 0) {
          <h2 class="ui-profile-section">{{ 'profiles.section.positions' | translate }}</h2>
          <ul class="ui-profile-entries ui-profile-positions">
            @for (position of p.positions; track $index) {
              <li>
                <strong>{{ position.title }}</strong>, {{ position.organization }}
                <span class="ui-muted">
                  · {{ 'profiles.positionType.' + position.type | translate }}
                  @if (position.start || position.end) {
                    · {{ span(position.start, position.end) }}
                  }
                </span>
              </li>
            }
          </ul>
        }

        @if (p.education.length > 0) {
          <h2 class="ui-profile-section">{{ 'profiles.section.education' | translate }}</h2>
          <ul class="ui-profile-entries ui-profile-education">
            @for (education of p.education; track $index) {
              <li>
                <strong>{{ education.degree }}</strong>@if (education.field) { ({{ education.field }})}, {{ education.organization }}
                @if (education.end) {
                  <span class="ui-muted">· {{ education.end }}</span>
                }
              </li>
            }
          </ul>
        }

        @if (p.publications.length > 0) {
          <h2 class="ui-profile-section">{{ 'profiles.section.publications' | translate }}</h2>
          <ol class="ui-profile-entries ui-profile-publications">
            @for (publication of p.publications; track $index) {
              <li>
                <cite>{{ publication.title }}</cite>
                @if (publication.venue) {
                  — {{ publication.venue }}
                }
                @if (publication.year) {
                  ({{ publication.year }})
                }
                <span class="ui-muted">· {{ 'profiles.publicationType.' + publication.type | translate }}</span>
                @if (publication.doi) {
                  · <a [href]="doiBase + publication.doi" target="_blank" rel="noopener">doi:{{ publication.doi }}</a>
                }
              </li>
            }
          </ol>
        }
      } @else {
        <p class="ui-muted" role="status">{{ 'profiles.notAProfile' | translate }}</p>
      }
    }
  `,
})
export class ProfilePageComponent {
  private readonly ldp = inject(LdpService);

  private readonly config = inject(ConfigService);

  private readonly notifier = inject(Notifier);

  private readonly dialog = inject(MatDialog);

  private readonly router = inject(Router);

  private readonly i18n = inject(I18nService);

  /** The profile's name in the profile container (bound from the route). */
  readonly id = input.required<string>();

  protected readonly routeTo = routeTo;

  protected readonly orcidBase = ORCID_BASE;

  protected readonly doiBase = DOI_BASE;

  protected readonly path = computed(() => childPath(this.config.profilesPath(), this.id()));

  protected readonly shown = rxResource({
    params: () => this.path(),
    stream: ({ params }) =>
      this.ldp.load(params).pipe(
        map((view): Shown => {
          const profile = isProfile(view.graph, view.subject) ? readProfile(view.graph, view.subject).profile : undefined;
          return {
            view,
            profile,
            name: (profile && displayName(profile)) || view.graph.title(view.subject, this.i18n.locale()),
            canEdit: allows(view.wacAllow, 'write'),
          };
        }),
      ),
  });

  protected readonly loadError = computed(() => {
    const error = this.shown.error();
    return error ? this.notifier.message(error) : undefined;
  });

  /**
   * Writes a span of years.
   *
   * @param start - the start year, may be empty
   * @param end - the end year, empty while current
   * @returns for example `1906 – 1934` or `1914 – present`
   */
  protected span(start: string, end: string): string {
    return `${start || '?'} – ${end || this.i18n.t('profiles.current')}`;
  }

  /**
   * The `tel:` link of a telephone number.
   *
   * @param phone - the number as entered
   * @returns the link
   */
  protected telUrl(phone: string): string {
    return `tel:${phone.replace(/[^\d+]/g, '')}`;
  }

  /**
   * Links only web addresses (profiles may hold other values written elsewhere).
   *
   * @param url - the stored address
   * @returns the address, or `null` to render no link target
   */
  protected safeUrl(url: string): string | null {
    return /^https?:\/\//i.test(url) ? url : null;
  }

  /**
   * Deletes the profile after confirmation and returns to the list.
   *
   * @param shown - the profile
   */
  protected remove(shown: Shown): void {
    const data: ConfirmData = {
      title: this.i18n.t('profiles.deleteTitle'),
      message: this.i18n.t('profiles.deleteConfirm', { name: shown.name }),
      confirm: this.i18n.t('common.delete'),
    };
    this.dialog
      .open(ConfirmDialogComponent, { data })
      .afterClosed()
      .pipe(
        filter((confirmed) => confirmed === true),
        switchMap(() => this.ldp.delete(shown.view.path)),
      )
      .subscribe({
        next: () => {
          this.notifier.success(this.i18n.t('profiles.deleted'));
          void this.router.navigate(['/profiles']);
        },
        error: (error: unknown) => this.notifier.error(this.i18n.t('common.delete'), error),
      });
  }
}
