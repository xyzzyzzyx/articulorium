import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { Observable, catchError, forkJoin, map, of, switchMap } from 'rxjs';

import { ConfigService } from '../core/config.service';
import { I18nService } from '../core/i18n/i18n.service';
import { LdpService } from '../core/ldp/ldp.service';
import { pathOf } from '../core/ldp/paths';
import { PersonType, displayName, isProfile, readProfile, sortName } from '../core/profile/profile';
import { LDP } from '../core/rdf/vocabulary';
import { allows } from '../core/webac/webac';
import { Notifier } from '../shared/notifier.service';

/** A profile as listed. */
interface Entry {
  readonly id: string;
  readonly name: string;
  readonly sortKey: string;
  readonly preferredTitle: string;
  readonly personType: PersonType;
}

/** The profile container and its profiles. */
interface Listing {
  readonly entries: readonly Entry[];
  readonly canCreate: boolean;
}

/**
 * Lists the academic profiles of the profile container (`/profiles` by default), sorted by
 * family name, and links to creating new ones.
 */
@Component({
  selector: 'ui-profile-list-page',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    RouterLink,
    TranslatePipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="ui-page-header">
      <div>
        <h1>{{ 'profiles.heading' | translate }}</h1>
        <p class="ui-muted">{{ 'profiles.intro' | translate }}</p>
      </div>
      @if (listing.value()?.canCreate) {
        <a mat-flat-button color="primary" class="ui-new-profile" [routerLink]="['/profiles', 'new']">
          <mat-icon aria-hidden="true">person_add</mat-icon> {{ 'profiles.new' | translate }}
        </a>
      }
    </header>
    @if (listing.isLoading()) {
      <mat-progress-bar mode="indeterminate" [attr.aria-label]="'common.loading' | translate" />
    }
    @if (loadError(); as message) {
      <p class="ui-error-text" role="alert">{{ message }}</p>
    }
    @if (listing.value(); as l) {
      @if (l.entries.length > 0) {
        <mat-form-field class="ui-profile-filter">
          <mat-label>{{ 'profiles.filter' | translate }}</mat-label>
          <mat-icon matPrefix aria-hidden="true">search</mat-icon>
          <input matInput name="filter" [ngModel]="filter()" (ngModelChange)="filter.set($event)" />
        </mat-form-field>
      }
      <div class="ui-card-grid ui-profiles-list">
        @for (entry of shown(); track entry.id) {
          <mat-card appearance="outlined" class="ui-profile-card">
            <mat-card-header>
              <mat-icon mat-card-avatar aria-hidden="true">account_circle</mat-icon>
              <mat-card-title><a [routerLink]="['/profiles', entry.id]">{{ entry.name }}</a></mat-card-title>
              <mat-card-subtitle>{{ entry.preferredTitle || ('profiles.personType.' + entry.personType | translate) }}</mat-card-subtitle>
            </mat-card-header>
          </mat-card>
        } @empty {
          <p class="ui-muted">
            {{ l.entries.length === 0 ? ('profiles.none' | translate) : ('profiles.noMatch' | translate: { filter: filter() }) }}
          </p>
        }
      </div>
    }
  `,
})
export class ProfileListPageComponent {
  private readonly ldp = inject(LdpService);

  private readonly config = inject(ConfigService);

  private readonly notifier = inject(Notifier);

  private readonly i18n = inject(I18nService);

  protected readonly filter = signal('');

  protected readonly listing = rxResource({
    params: () => this.config.profilesPath(),
    stream: ({ params }) =>
      this.ldp.load(params).pipe(
        switchMap((view) => {
          const children = view.graph
            .objects(view.subject, LDP.contains)
            .map((term) => pathOf(this.ldp.base(), term.value))
            .filter((path): path is string => path !== undefined);
          const entries$: Observable<Entry[]> =
            children.length === 0
              ? of([])
              : forkJoin(children.map((child) => this.entry(child).pipe(catchError(() => of(undefined))))).pipe(
                  map((entries) => entries.filter((entry): entry is Entry => entry !== undefined)),
                );
          return entries$.pipe(
            map(
              (entries): Listing => ({
                entries: [...entries].sort((a, b) =>
                  a.sortKey.localeCompare(b.sortKey, this.i18n.locale(), { sensitivity: 'base', numeric: true }),
                ),
                canCreate: allows(view.wacAllow, 'append'),
              }),
            ),
          );
        }),
      ),
  });

  protected readonly shown = computed(() => {
    const entries = this.listing.value()?.entries ?? [];
    const wanted = this.filter().trim().toLocaleLowerCase();
    return wanted ? entries.filter((entry) => entry.name.toLocaleLowerCase().includes(wanted)) : entries;
  });

  protected readonly loadError = computed(() => {
    const error = this.listing.error();
    return error ? this.notifier.message(error) : undefined;
  });

  /**
   * Reads one child of the profile container; children that are not profiles are left out.
   *
   * @param path - the child's path
   * @returns the entry, or `undefined`
   */
  private entry(path: string): Observable<Entry | undefined> {
    return this.ldp.load(path).pipe(
      map((view) => {
        if (!isProfile(view.graph, view.subject)) {
          return undefined;
        }
        const { profile } = readProfile(view.graph, view.subject);
        const name = displayName(profile) || view.graph.title(view.subject, this.i18n.locale());
        return {
          id: path.substring(path.lastIndexOf('/') + 1),
          name,
          sortKey: sortName(profile) || name,
          preferredTitle: profile.preferredTitle,
          personType: profile.personType,
        };
      }),
    );
  }
}
