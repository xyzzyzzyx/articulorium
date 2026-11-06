import { Injectable, inject } from '@angular/core';
import { MatPaginatorIntl } from '@angular/material/paginator';
import { TranslateService } from '@ngx-translate/core';

import { formatNumber } from './format.pipes';

/**
 * The paginator's labels and range text in the user interface's language, updated when the
 * language changes.
 */
@Injectable()
export class LocalizedPaginatorIntl extends MatPaginatorIntl {
  private readonly translate = inject(TranslateService);

  constructor() {
    super();
    this.update();
    this.translate.onLangChange.subscribe(() => this.update());
  }

  override getRangeLabel = (page: number, pageSize: number, length: number): string => {
    const locale = this.translate.getCurrentLang() ?? 'en';
    if (length === 0 || pageSize === 0) {
      return this.translate.instant('paginator.rangeEmpty', { length: formatNumber(length, locale) }) as string;
    }
    const start = page * pageSize;
    const end = Math.min(start + pageSize, length);
    return this.translate.instant('paginator.range', {
      start: formatNumber(start + 1, locale),
      end: formatNumber(end, locale),
      length: formatNumber(length, locale),
    }) as string;
  };

  private update(): void {
    this.itemsPerPageLabel = this.translate.instant('paginator.itemsPerPage') as string;
    this.nextPageLabel = this.translate.instant('paginator.nextPage') as string;
    this.previousPageLabel = this.translate.instant('paginator.previousPage') as string;
    this.firstPageLabel = this.translate.instant('paginator.firstPage') as string;
    this.lastPageLabel = this.translate.instant('paginator.lastPage') as string;
    this.changes.next();
  }
}
