import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { AbstractControl, FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatChipInputEvent, MatChipsModule } from '@angular/material/chips';
import { ErrorStateMatcher } from '@angular/material/core';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { Router, RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { map, of } from 'rxjs';

import { slugOf } from '../communities/communities';
import { ConfigService } from '../core/config.service';
import { I18nService } from '../core/i18n/i18n.service';
import { LdpService } from '../core/ldp/ldp.service';
import { ResourceView } from '../core/ldp/models';
import { childPath, pathOf } from '../core/ldp/paths';
import {
  MAX_OVERVIEW_LENGTH,
  PERSON_TYPES,
  POSITION_TYPES,
  PUBLICATION_TYPES,
  Profile,
  ProfileDocument,
  ProfileIssue,
  PublicationType,
  displayName,
  emptyEducation,
  emptyPosition,
  emptyProfile,
  emptyPublication,
  emptyWebsite,
  isProfile,
  profileTurtle,
  readProfile,
  validateProfile,
} from '../core/profile/profile';
import { Notifier } from '../shared/notifier.service';

/** The profile being edited, as read. */
interface Source {
  readonly view: ResourceView;
  readonly document?: ProfileDocument;
}

/** Translation keys of the labels of the fields, by the last segment of the field path. */
const FIELD_LABELS: Record<string, string> = {
  givenName: 'profiles.field.givenName',
  familyName: 'profiles.field.familyName',
  email: 'profiles.field.email',
  phone: 'profiles.field.phone',
  orcid: 'profiles.field.orcid',
  overview: 'profiles.field.overview',
  url: 'profiles.field.url',
  'positions.title': 'profiles.field.positionTitle',
  'positions.organization': 'profiles.field.organization',
  'positions.start': 'profiles.field.startYear',
  'positions.end': 'profiles.field.endYear',
  'education.degree': 'profiles.field.degree',
  'education.organization': 'profiles.field.organization',
  'education.end': 'profiles.field.yearAwarded',
  'publications.title': 'profiles.field.publicationTitle',
  'publications.year': 'profiles.field.year',
  'publications.doi': 'profiles.field.doi',
};

/** Translation keys of the repeatable sections, by the first segment of the field path. */
const SECTION_ITEMS: Record<string, string> = {
  websites: 'profiles.item.website',
  positions: 'profiles.item.position',
  education: 'profiles.item.education',
  publications: 'profiles.item.publication',
};

/**
 * Creates (`/profiles/new`) or edits (`/profiles/<id>/edit`) an academic profile described with
 * the VIVO ontology.
 *
 * The form validates with {@link validateProfile}: problems are shown next to their fields once a
 * field was left or a save attempted, and summarised above the form on save. A profile is saved
 * as a whole: created with `POST` in the profile container, replaced with `PUT` guarded by the
 * entity tag it was read with, so changes made meanwhile by someone else are never overwritten.
 */
@Component({
  selector: 'ui-profile-editor-page',
  imports: [
    FormsModule,
    MatButtonModule,
    MatChipsModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    RouterLink,
    TranslatePipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .ui-profile-editor fieldset {
      border: 1px solid var(--ui-muted, #9e9e9e);
      border-radius: 8px;
      margin: 8px 0;
      padding: 8px 16px 16px;
    }
    .ui-profile-editor legend {
      font-weight: 500;
      padding-inline: 4px;
    }
    .ui-profile-row {
      border-inline-start: 3px solid #90a4ae;
      margin-block: 8px;
      padding-inline-start: 12px;
    }
    .ui-profile-wide {
      width: 100%;
    }
    .ui-inline-fields .ui-profile-year {
      flex: 0 1 180px;
    }
    .ui-profile-actions {
      position: sticky;
      bottom: 0;
      padding-block: 8px;
      background: var(--mat-sys-surface, #fff);
    }
  `,
  template: `
    <nav class="ui-breadcrumb" [attr.aria-label]="'breadcrumb.label' | translate">
      <a [routerLink]="['/profiles']"><mat-icon inline aria-hidden="true">badge</mat-icon> {{ 'nav.profiles' | translate }}</a> /
      @if (id(); as profileId) {
        <a [routerLink]="['/profiles', profileId]">{{ heading() }}</a> /
        <span aria-current="page">{{ 'profiles.edit' | translate }}</span>
      } @else {
        <span aria-current="page">{{ 'profiles.new' | translate }}</span>
      }
    </nav>
    @if (source.isLoading()) {
      <mat-progress-bar mode="indeterminate" [attr.aria-label]="'common.loading' | translate" />
    }
    @if (loadError(); as message) {
      <p class="ui-error-text" role="alert">{{ message }}</p>
    }
    @if (notAProfile()) {
      <p class="ui-muted" role="status">{{ 'profiles.notAProfile' | translate }}</p>
    }
    @if (draft(); as d) {
      <form class="ui-form ui-profile-editor" novalidate (ngSubmit)="save(d)">
        <header class="ui-page-header">
          <h1>{{ id() ? heading() : ('profiles.new' | translate) }}</h1>
        </header>

        @if (submitted() && issues().length > 0) {
          <div class="ui-banner ui-error-text ui-profile-issues" role="alert">
            <mat-icon aria-hidden="true">error</mat-icon>
            <div>
              <p>{{ 'profiles.validation.summary' | translate }}</p>
              <ul>
                @for (issue of issues(); track issue.field) {
                  <li>{{ fieldLabel(issue.field) }}: {{ message(issue) }}</li>
                }
              </ul>
            </div>
          </div>
        }

        <fieldset>
          <legend>{{ 'profiles.section.identity' | translate }}</legend>
          <div class="ui-inline-fields">
            <mat-form-field>
              <mat-label>{{ 'profiles.field.givenName' | translate }}</mat-label>
              <input matInput name="givenName" required autocomplete="given-name" [(ngModel)]="d.givenName" [errorStateMatcher]="matcher('givenName')" />
              <mat-error>{{ issueText('givenName') }}</mat-error>
            </mat-form-field>
            <mat-form-field>
              <mat-label>{{ 'profiles.field.middleName' | translate }}</mat-label>
              <input matInput name="middleName" autocomplete="additional-name" [(ngModel)]="d.middleName" />
            </mat-form-field>
            <mat-form-field>
              <mat-label>{{ 'profiles.field.familyName' | translate }}</mat-label>
              <input matInput name="familyName" required autocomplete="family-name" [(ngModel)]="d.familyName" [errorStateMatcher]="matcher('familyName')" />
              <mat-error>{{ issueText('familyName') }}</mat-error>
            </mat-form-field>
          </div>
          <div class="ui-inline-fields">
            <mat-form-field>
              <mat-label>{{ 'profiles.field.personType' | translate }}</mat-label>
              <mat-select name="personType" [(ngModel)]="d.personType">
                @for (type of personTypes; track type) {
                  <mat-option [value]="type">{{ 'profiles.personType.' + type | translate }}</mat-option>
                }
              </mat-select>
            </mat-form-field>
            <mat-form-field>
              <mat-label>{{ 'profiles.field.preferredTitle' | translate }}</mat-label>
              <input matInput name="preferredTitle" autocomplete="organization-title" [(ngModel)]="d.preferredTitle" />
            </mat-form-field>
          </div>
        </fieldset>

        <fieldset>
          <legend>{{ 'profiles.section.contact' | translate }}</legend>
          <div class="ui-inline-fields">
            <mat-form-field>
              <mat-label>{{ 'profiles.field.email' | translate }}</mat-label>
              <input matInput name="email" type="email" autocomplete="email" [(ngModel)]="d.email" [errorStateMatcher]="matcher('email')" />
              <mat-error>{{ issueText('email') }}</mat-error>
            </mat-form-field>
            <mat-form-field>
              <mat-label>{{ 'profiles.field.phone' | translate }}</mat-label>
              <input matInput name="phone" type="tel" autocomplete="tel" [(ngModel)]="d.phone" [errorStateMatcher]="matcher('phone')" />
              <mat-error>{{ issueText('phone') }}</mat-error>
            </mat-form-field>
            <mat-form-field>
              <mat-label>{{ 'profiles.field.orcid' | translate }}</mat-label>
              <input matInput name="orcid" spellcheck="false" [(ngModel)]="d.orcid" [errorStateMatcher]="matcher('orcid')" />
              <mat-hint>{{ 'profiles.hint.orcid' | translate }}</mat-hint>
              <mat-error>{{ issueText('orcid') }}</mat-error>
            </mat-form-field>
          </div>
          <h3>{{ 'profiles.section.websites' | translate }}</h3>
          @for (website of d.websites; track $index; let i = $index) {
            <div class="ui-inline-fields ui-profile-row ui-profile-website">
              <mat-form-field>
                <mat-label>{{ 'profiles.field.url' | translate }}</mat-label>
                <input matInput type="url" [name]="'website-url-' + i" [(ngModel)]="website.url" [errorStateMatcher]="matcher('websites.' + i + '.url')" />
                <mat-error>{{ issueText('websites.' + i + '.url') }}</mat-error>
              </mat-form-field>
              <mat-form-field>
                <mat-label>{{ 'profiles.field.label' | translate }}</mat-label>
                <input matInput [name]="'website-label-' + i" [(ngModel)]="website.label" />
              </mat-form-field>
              <button mat-icon-button type="button" (click)="removeRow(d.websites, i)" [attr.aria-label]="removeLabel('websites', i)">
                <mat-icon aria-hidden="true">delete</mat-icon>
              </button>
            </div>
          }
          <button mat-stroked-button type="button" class="ui-add-website" (click)="d.websites.push(newWebsite())">
            <mat-icon aria-hidden="true">add</mat-icon> {{ 'profiles.add.website' | translate }}
          </button>
        </fieldset>

        <fieldset>
          <legend>{{ 'profiles.section.overview' | translate }}</legend>
          <mat-form-field class="ui-profile-wide">
            <mat-label>{{ 'profiles.field.overview' | translate }}</mat-label>
            <textarea matInput name="overview" rows="5" [(ngModel)]="d.overview" [errorStateMatcher]="matcher('overview')"></textarea>
            <mat-hint align="end">{{ d.overview.length }} / {{ maxOverview }}</mat-hint>
            <mat-error>{{ issueText('overview') }}</mat-error>
          </mat-form-field>
        </fieldset>

        <fieldset>
          <legend>{{ 'profiles.section.research' | translate }}</legend>
          <mat-form-field class="ui-profile-wide ui-profile-areas-field">
            <mat-label>{{ 'profiles.field.researchAreas' | translate }}</mat-label>
            <mat-chip-grid #areaGrid [attr.aria-label]="'profiles.field.researchAreas' | translate">
              @for (area of d.researchAreas; track area) {
                <mat-chip-row (removed)="removeValue(d.researchAreas, area)">
                  {{ area }}
                  <button matChipRemove type="button" [attr.aria-label]="'profiles.removeValue' | translate: { value: area }">
                    <mat-icon aria-hidden="true">cancel</mat-icon>
                  </button>
                </mat-chip-row>
              }
              <input name="researchAreaInput" [matChipInputFor]="areaGrid" (matChipInputTokenEnd)="addValue(d.researchAreas, $event)" />
            </mat-chip-grid>
            <mat-hint>{{ 'profiles.hint.addValue' | translate }}</mat-hint>
          </mat-form-field>
          <mat-form-field class="ui-profile-wide ui-profile-keywords-field">
            <mat-label>{{ 'profiles.field.keywords' | translate }}</mat-label>
            <mat-chip-grid #keywordGrid [attr.aria-label]="'profiles.field.keywords' | translate">
              @for (keyword of d.keywords; track keyword) {
                <mat-chip-row (removed)="removeValue(d.keywords, keyword)">
                  {{ keyword }}
                  <button matChipRemove type="button" [attr.aria-label]="'profiles.removeValue' | translate: { value: keyword }">
                    <mat-icon aria-hidden="true">cancel</mat-icon>
                  </button>
                </mat-chip-row>
              }
              <input name="keywordInput" [matChipInputFor]="keywordGrid" (matChipInputTokenEnd)="addValue(d.keywords, $event)" />
            </mat-chip-grid>
            <mat-hint>{{ 'profiles.hint.addValue' | translate }}</mat-hint>
          </mat-form-field>
        </fieldset>

        <fieldset>
          <legend>{{ 'profiles.section.positions' | translate }}</legend>
          @for (position of d.positions; track $index; let i = $index) {
            <div class="ui-profile-row ui-profile-position">
              <div class="ui-inline-fields">
                <mat-form-field>
                  <mat-label>{{ 'profiles.field.positionTitle' | translate }}</mat-label>
                  <input matInput required [name]="'position-title-' + i" [(ngModel)]="position.title" [errorStateMatcher]="matcher('positions.' + i + '.title')" />
                  <mat-error>{{ issueText('positions.' + i + '.title') }}</mat-error>
                </mat-form-field>
                <mat-form-field>
                  <mat-label>{{ 'profiles.field.positionType' | translate }}</mat-label>
                  <mat-select [name]="'position-type-' + i" [(ngModel)]="position.type">
                    @for (type of positionTypes; track type) {
                      <mat-option [value]="type">{{ 'profiles.positionType.' + type | translate }}</mat-option>
                    }
                  </mat-select>
                </mat-form-field>
              </div>
              <div class="ui-inline-fields">
                <mat-form-field>
                  <mat-label>{{ 'profiles.field.organization' | translate }}</mat-label>
                  <input matInput required [name]="'position-organization-' + i" [(ngModel)]="position.organization" [errorStateMatcher]="matcher('positions.' + i + '.organization')" />
                  <mat-error>{{ issueText('positions.' + i + '.organization') }}</mat-error>
                </mat-form-field>
                <mat-form-field class="ui-profile-year">
                  <mat-label>{{ 'profiles.field.startYear' | translate }}</mat-label>
                  <input matInput inputmode="numeric" maxlength="4" [name]="'position-start-' + i" [(ngModel)]="position.start" [errorStateMatcher]="matcher('positions.' + i + '.start')" />
                  <mat-error>{{ issueText('positions.' + i + '.start') }}</mat-error>
                </mat-form-field>
                <mat-form-field class="ui-profile-year">
                  <mat-label>{{ 'profiles.field.endYear' | translate }}</mat-label>
                  <input matInput inputmode="numeric" maxlength="4" [name]="'position-end-' + i" [(ngModel)]="position.end" [errorStateMatcher]="matcher('positions.' + i + '.end')" />
                  <mat-hint>{{ 'profiles.hint.endYear' | translate }}</mat-hint>
                  <mat-error>{{ issueText('positions.' + i + '.end') }}</mat-error>
                </mat-form-field>
                <button mat-icon-button type="button" (click)="removeRow(d.positions, i)" [attr.aria-label]="removeLabel('positions', i)">
                  <mat-icon aria-hidden="true">delete</mat-icon>
                </button>
              </div>
            </div>
          }
          <button mat-stroked-button type="button" class="ui-add-position" (click)="d.positions.push(newPosition())">
            <mat-icon aria-hidden="true">add</mat-icon> {{ 'profiles.add.position' | translate }}
          </button>
        </fieldset>

        <fieldset>
          <legend>{{ 'profiles.section.education' | translate }}</legend>
          @for (education of d.education; track $index; let i = $index) {
            <div class="ui-inline-fields ui-profile-row ui-profile-degree">
              <mat-form-field>
                <mat-label>{{ 'profiles.field.degree' | translate }}</mat-label>
                <input matInput required [name]="'education-degree-' + i" [(ngModel)]="education.degree" [errorStateMatcher]="matcher('education.' + i + '.degree')" />
                <mat-error>{{ issueText('education.' + i + '.degree') }}</mat-error>
              </mat-form-field>
              <mat-form-field>
                <mat-label>{{ 'profiles.field.majorField' | translate }}</mat-label>
                <input matInput [name]="'education-field-' + i" [(ngModel)]="education.field" />
              </mat-form-field>
              <mat-form-field>
                <mat-label>{{ 'profiles.field.organization' | translate }}</mat-label>
                <input matInput required [name]="'education-organization-' + i" [(ngModel)]="education.organization" [errorStateMatcher]="matcher('education.' + i + '.organization')" />
                <mat-error>{{ issueText('education.' + i + '.organization') }}</mat-error>
              </mat-form-field>
              <mat-form-field class="ui-profile-year">
                <mat-label>{{ 'profiles.field.yearAwarded' | translate }}</mat-label>
                <input matInput inputmode="numeric" maxlength="4" [name]="'education-end-' + i" [(ngModel)]="education.end" [errorStateMatcher]="matcher('education.' + i + '.end')" />
                <mat-error>{{ issueText('education.' + i + '.end') }}</mat-error>
              </mat-form-field>
              <button mat-icon-button type="button" (click)="removeRow(d.education, i)" [attr.aria-label]="removeLabel('education', i)">
                <mat-icon aria-hidden="true">delete</mat-icon>
              </button>
            </div>
          }
          <button mat-stroked-button type="button" class="ui-add-degree" (click)="d.education.push(newEducation())">
            <mat-icon aria-hidden="true">add</mat-icon> {{ 'profiles.add.education' | translate }}
          </button>
        </fieldset>

        <fieldset>
          <legend>{{ 'profiles.section.publications' | translate }}</legend>
          @for (publication of d.publications; track $index; let i = $index) {
            <div class="ui-profile-row ui-profile-publication">
              <div class="ui-inline-fields">
                <mat-form-field>
                  <mat-label>{{ 'profiles.field.publicationTitle' | translate }}</mat-label>
                  <input matInput required [name]="'publication-title-' + i" [(ngModel)]="publication.title" [errorStateMatcher]="matcher('publications.' + i + '.title')" />
                  <mat-error>{{ issueText('publications.' + i + '.title') }}</mat-error>
                </mat-form-field>
                <mat-form-field>
                  <mat-label>{{ 'profiles.field.publicationType' | translate }}</mat-label>
                  <mat-select [name]="'publication-type-' + i" [(ngModel)]="publication.type">
                    @for (type of publicationTypes; track type) {
                      <mat-option [value]="type">{{ 'profiles.publicationType.' + type | translate }}</mat-option>
                    }
                  </mat-select>
                </mat-form-field>
              </div>
              <div class="ui-inline-fields">
                <mat-form-field>
                  <mat-label>{{ 'profiles.field.venue' | translate }}</mat-label>
                  <input matInput [name]="'publication-venue-' + i" [(ngModel)]="publication.venue" />
                </mat-form-field>
                <mat-form-field class="ui-profile-year">
                  <mat-label>{{ 'profiles.field.year' | translate }}</mat-label>
                  <input matInput inputmode="numeric" maxlength="4" [name]="'publication-year-' + i" [(ngModel)]="publication.year" [errorStateMatcher]="matcher('publications.' + i + '.year')" />
                  <mat-error>{{ issueText('publications.' + i + '.year') }}</mat-error>
                </mat-form-field>
                <mat-form-field>
                  <mat-label>{{ 'profiles.field.doi' | translate }}</mat-label>
                  <input matInput spellcheck="false" [name]="'publication-doi-' + i" [(ngModel)]="publication.doi" [errorStateMatcher]="matcher('publications.' + i + '.doi')" />
                  <mat-hint>{{ 'profiles.hint.doi' | translate }}</mat-hint>
                  <mat-error>{{ issueText('publications.' + i + '.doi') }}</mat-error>
                </mat-form-field>
                <button mat-icon-button type="button" (click)="removeRow(d.publications, i)" [attr.aria-label]="removeLabel('publications', i)">
                  <mat-icon aria-hidden="true">delete</mat-icon>
                </button>
              </div>
            </div>
          }
          <button mat-stroked-button type="button" class="ui-add-publication" (click)="d.publications.push(newPublication())">
            <mat-icon aria-hidden="true">add</mat-icon> {{ 'profiles.add.publication' | translate }}
          </button>
        </fieldset>

        @if (busy()) {
          <mat-progress-bar mode="indeterminate" [attr.aria-label]="'common.loading' | translate" />
        }
        <div class="ui-row ui-profile-actions">
          <button mat-flat-button color="primary" type="submit" class="ui-save-profile" [disabled]="busy()">
            <mat-icon aria-hidden="true">save</mat-icon> {{ 'profiles.save' | translate }}
          </button>
          <a mat-button [routerLink]="id() ? ['/profiles', id()] : ['/profiles']">{{ 'common.cancel' | translate }}</a>
        </div>
      </form>
    }
  `,
})
export class ProfileEditorPageComponent {
  private readonly ldp = inject(LdpService);

  private readonly config = inject(ConfigService);

  private readonly notifier = inject(Notifier);

  private readonly router = inject(Router);

  private readonly i18n = inject(I18nService);

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  private readonly injector = inject(Injector);

  /** The profile's name in the profile container; absent for a new profile (bound from the route). */
  readonly id = input<string | undefined>(undefined);

  protected readonly personTypes = PERSON_TYPES;

  protected readonly positionTypes = POSITION_TYPES;

  protected readonly publicationTypes = Object.keys(PUBLICATION_TYPES) as PublicationType[];

  protected readonly maxOverview = MAX_OVERVIEW_LENGTH;

  protected readonly newWebsite = emptyWebsite;

  protected readonly newPosition = emptyPosition;

  protected readonly newEducation = emptyEducation;

  protected readonly newPublication = emptyPublication;

  protected readonly source = rxResource({
    params: () => this.id(),
    stream: ({ params }) =>
      params
        ? this.ldp.load(childPath(this.config.profilesPath(), params)).pipe(
            map(
              (view): Source => ({
                view,
                document: isProfile(view.graph, view.subject) ? readProfile(view.graph, view.subject) : undefined,
              }),
            ),
          )
        : of(undefined),
  });

  protected readonly draft = signal<Profile | undefined>(undefined);

  protected readonly busy = signal(false);

  /** Whether a save was attempted: from then on every problem is shown. */
  protected readonly submitted = signal(false);

  protected readonly notAProfile = computed(() => !!this.source.value() && !this.source.value()?.document);

  protected readonly loadError = computed(() => {
    const error = this.source.error();
    return error ? this.notifier.message(error) : undefined;
  });

  protected readonly heading = computed(() => {
    const profile = this.source.value()?.document?.profile;
    return (profile && displayName(profile)) || this.id() || '';
  });

  private readonly matchers = new Map<string, ErrorStateMatcher>();

  /** The last validation and the state of the draft it was computed for. */
  private validation: { readonly state: string; readonly issues: ProfileIssue[] } = { state: '', issues: [] };

  constructor() {
    effect(() => {
      if (this.source.isLoading()) {
        return;
      }
      if (!this.id()) {
        this.draft.set(emptyProfile());
      } else {
        const profile = this.source.value()?.document?.profile;
        this.draft.set(profile ? (structuredClone(profile) as Profile) : undefined);
      }
      this.submitted.set(false);
    });
  }

  /**
   * The problems of the current draft (memoized: the template asks for them field by field).
   *
   * @returns the problems
   */
  protected issues(): ProfileIssue[] {
    const draft = this.draft();
    if (!draft) {
      return [];
    }
    const state = JSON.stringify(draft);
    if (state !== this.validation.state) {
      this.validation = { state, issues: validateProfile(draft) };
    }
    return this.validation.issues;
  }

  /**
   * The message of a field's first problem.
   *
   * @param field - the field path, for example `positions.0.start`
   * @returns the translated message, or an empty string
   */
  protected issueText(field: string): string {
    const issue = this.issues().find((candidate) => candidate.field === field);
    return issue ? this.message(issue) : '';
  }

  /**
   * Translates a problem.
   *
   * @param issue - the problem
   * @returns the message
   */
  protected message(issue: ProfileIssue): string {
    return this.i18n.t(issue.key, issue.params as Record<string, unknown> | undefined);
  }

  /**
   * Shows a field as invalid when it has a problem and was left or a save was attempted.
   *
   * @param field - the field path
   * @returns the matcher (the same instance for a field, so bindings stay stable)
   */
  protected matcher(field: string): ErrorStateMatcher {
    let matcher = this.matchers.get(field);
    if (!matcher) {
      matcher = {
        isErrorState: (control: AbstractControl | null) =>
          (this.submitted() || !!control?.touched) && this.issues().some((issue) => issue.field === field),
      };
      this.matchers.set(field, matcher);
    }
    return matcher;
  }

  /**
   * Names a field for the summary of problems: `Position 2 · Start year`.
   *
   * @param field - the field path
   * @returns the label
   */
  protected fieldLabel(field: string): string {
    const parts = field.split('.');
    if (parts.length === 3) {
      const [section, index, name] = parts;
      const item = this.i18n.t(SECTION_ITEMS[section], { number: Number(index) + 1 });
      const key = FIELD_LABELS[`${section}.${name}`] ?? FIELD_LABELS[name];
      return `${item} · ${key ? this.i18n.t(key) : name}`;
    }
    return FIELD_LABELS[field] ? this.i18n.t(FIELD_LABELS[field]) : field;
  }

  /**
   * The accessible name of a row's remove button.
   *
   * @param section - `websites`, `positions`, `education` or `publications`
   * @param index - the row
   * @returns for example `Remove position 2`
   */
  protected removeLabel(section: string, index: number): string {
    return this.i18n.t('profiles.removeItem', { item: this.i18n.t(SECTION_ITEMS[section], { number: index + 1 }) });
  }

  /**
   * Removes a row of a repeatable section.
   *
   * @param rows - the rows
   * @param index - the row to remove
   */
  protected removeRow(rows: unknown[], index: number): void {
    rows.splice(index, 1);
  }

  /**
   * Adds the value typed in a chip input, unless it is already there (ignoring case).
   *
   * @param values - the values
   * @param event - the chip input event
   */
  protected addValue(values: string[], event: MatChipInputEvent): void {
    const value = event.value.trim();
    if (value && !values.some((existing) => existing.toLocaleLowerCase() === value.toLocaleLowerCase())) {
      values.push(value);
    }
    event.chipInput.clear();
  }

  /**
   * Removes a value of a chip list.
   *
   * @param values - the values
   * @param value - the value to remove
   */
  protected removeValue(values: string[], value: string): void {
    const index = values.indexOf(value);
    if (index >= 0) {
      values.splice(index, 1);
    }
  }

  /**
   * Validates and saves the profile, then shows it.
   *
   * @param draft - the edited profile
   */
  protected save(draft: Profile): void {
    this.submitted.set(true);
    if (this.issues().length > 0) {
      // Material leaves aria-invalid off empty required inputs, so look for the invalid fields
      afterNextRender(
        () =>
          this.host.nativeElement
            .querySelector<HTMLElement>('.mat-form-field-invalid input, .mat-form-field-invalid textarea')
            ?.focus(),
        { injector: this.injector },
      );
      return;
    }
    const source = this.source.value();
    this.busy.set(true);
    if (!source) {
      const turtle = profileTurtle(draft);
      this.ldp
        .createChild(this.config.profilesPath(), { kind: 'basic', slugHint: slugOf(displayName(draft)) || undefined, turtle })
        .subscribe({
          next: (location) => {
            this.busy.set(false);
            this.notifier.success(this.i18n.t('profiles.created'));
            const path = pathOf(this.ldp.base(), location) ?? '';
            void this.router.navigate(['/profiles', path.substring(path.lastIndexOf('/') + 1)]);
          },
          error: (error: unknown) => this.failed(error),
        });
      return;
    }
    const turtle = profileTurtle(draft, source.view.subject.value, source.document?.preserved);
    this.ldp.replace(source.view.path, turtle, source.view.etag).subscribe({
      next: () => {
        this.busy.set(false);
        this.notifier.success(this.i18n.t('profiles.saved'));
        void this.router.navigate(['/profiles', this.id()]);
      },
      error: (error: unknown) => this.failed(error),
    });
  }

  private failed(error: unknown): void {
    this.busy.set(false);
    if (error instanceof HttpErrorResponse && error.status === 412) {
      this.notifier.error(this.i18n.t('profiles.save'), new Error(this.i18n.t('profiles.conflict')));
      return;
    }
    this.notifier.error(this.i18n.t('profiles.save'), error);
  }
}
