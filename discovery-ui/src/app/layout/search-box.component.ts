import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { Router } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';

import { LocaleService } from '../core/i18n/locale.service';

let nextId = 0;

/**
 * A search field: submitting opens the search page with the terms. Works without JavaScript too:
 * it is a plain `GET` form to the search page.
 */
@Component({
  selector: 'disc-search-box',
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form
      role="search"
      class="disc-search-box"
      [class.disc-search-box--large]="large()"
      [attr.action]="action()"
      method="get"
      [attr.aria-label]="'search.landmark' | translate"
      (submit)="submit($event)"
    >
      <mat-form-field appearance="outline" subscriptSizing="dynamic" class="disc-search-field">
        <mat-label>{{ 'search.label' | translate }}</mat-label>
        <mat-icon matPrefix aria-hidden="true">search</mat-icon>
        <input
          matInput
          type="search"
          name="q"
          [id]="inputId"
          autocomplete="off"
          enterkeyhint="search"
          [ngModel]="terms()"
          (ngModelChange)="terms.set($event)"
          [ngModelOptions]="{ standalone: true }"
        />
      </mat-form-field>
      <button mat-flat-button type="submit">{{ 'search.submit' | translate }}</button>
    </form>
  `,
  styles: `
    .disc-search-box {
      display: flex;
      gap: 8px;
      align-items: center;
      width: 100%;
    }
    .disc-search-field {
      flex: 1;
      min-width: 0;
    }
    .disc-search-box--large .disc-search-field {
      font-size: 1.125rem;
    }
  `,
})
export class SearchBoxComponent {
  private readonly router = inject(Router);

  private readonly locales = inject(LocaleService);

  /** The terms shown initially. */
  readonly query = input('');

  /** Whether the field is the page's main search (home page). */
  readonly large = input(false);

  protected readonly terms = signal('');

  protected readonly inputId = `disc-search-${nextId++}`;

  protected readonly action = () => `${this.locales.locale()}/search`;

  constructor() {
    effect(() => this.terms.set(this.query()));
  }

  protected submit(event: Event): void {
    event.preventDefault();
    const q = this.terms().trim();
    void this.router.navigate(['/', this.locales.locale(), 'search'], { queryParams: q ? { q } : {} });
  }
}
