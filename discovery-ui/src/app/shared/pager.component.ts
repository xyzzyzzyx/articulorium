import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';

import { LocaleService } from '../core/i18n/locale.service';

/**
 * The number of pages of a listing.
 *
 * @param total - the number of entries (as the index counts them)
 * @param size - entries per page
 */
export function pageCount(total: number, size: number): number {
  return size > 0 ? Math.max(1, Math.ceil(total / size)) : 1;
}

/**
 * Reads a page number of a query parameter: a positive integer, 1 otherwise.
 *
 * @param value - the parameter
 */
export function parsePage(value: unknown): number {
  const page = typeof value === 'string' ? Number.parseInt(value, 10) : typeof value === 'number' ? value : Number.NaN;
  return Number.isSafeInteger(page) && page > 0 ? page : 1;
}

/**
 * Previous and next links between the pages of a listing, with "page x of y". The links change
 * only the page query parameter of the current URL (kept off on the first page), so pages are
 * crawlable and work without JavaScript; `fragment` scrolls back to the listing.
 */
@Component({
  selector: 'disc-pager',
  imports: [MatButtonModule, MatIconModule, RouterLink, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (pages() > 1) {
      <nav class="disc-pagination" [attr.aria-label]="label()">
        @if (page() > 1) {
          <a
            mat-stroked-button
            [routerLink]="[]"
            [queryParams]="params(page() - 1)"
            queryParamsHandling="merge"
            [fragment]="fragment()"
            rel="prev"
          >
            <mat-icon aria-hidden="true">{{ direction() === 'rtl' ? 'chevron_right' : 'chevron_left' }}</mat-icon>
            {{ 'search.previous' | translate }}
          </a>
        }
        <span class="disc-page-of">{{ pageOf() }}</span>
        @if (page() < pages()) {
          <a
            mat-stroked-button
            [routerLink]="[]"
            [queryParams]="params(page() + 1)"
            queryParamsHandling="merge"
            [fragment]="fragment()"
            rel="next"
          >
            {{ 'search.next' | translate }}
            <mat-icon aria-hidden="true" iconPositionEnd>{{ direction() === 'rtl' ? 'chevron_left' : 'chevron_right' }}</mat-icon>
          </a>
        }
      </nav>
    }
  `,
  styles: `
    .disc-pagination {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: center;
      gap: 16px;
      margin-top: 24px;
    }
  `,
})
export class PagerComponent {
  private readonly locales = inject(LocaleService);

  /** The current page, from 1. */
  readonly page = input.required<number>();

  /** The number of pages. */
  readonly pages = input.required<number>();

  /** The accessible name of the navigation landmark. */
  readonly label = input.required<string>();

  /** The query parameter holding the page. */
  readonly param = input('page');

  /** The element the links scroll to. */
  readonly fragment = input<string | undefined>(undefined);

  protected readonly direction = this.locales.direction;

  protected readonly pageOf = computed(() => {
    this.locales.locale();
    return this.locales.t('search.pageOf', { page: this.locales.number(this.page()), pages: this.locales.number(this.pages()) });
  });

  protected params(page: number): Record<string, string | null> {
    return { [this.param()]: page > 1 ? String(page) : null };
  }
}
