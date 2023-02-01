import { TestBed } from '@angular/core/testing';
import { TranslateService } from '@ngx-translate/core';

import { provideTestI18n } from '../../testing/test-i18n';
import { LocalizedPaginatorIntl } from './paginator-intl';

describe('LocalizedPaginatorIntl', () => {
  it('translates labels and formats ranges, following language changes', () => {
    TestBed.configureTestingModule({ providers: [...provideTestI18n(), LocalizedPaginatorIntl] });
    const intl = TestBed.inject(LocalizedPaginatorIntl);
    expect(intl.nextPageLabel).toBe('Next page');
    expect(intl.getRangeLabel(1, 10, 1234)).toBe('11 – 20 of 1,234');
    expect(intl.getRangeLabel(0, 10, 0)).toBe('0 of 0');

    const translate = TestBed.inject(TranslateService);
    let changes = 0;
    intl.changes.subscribe(() => changes++);
    translate.setTranslation('de', { paginator: { nextPage: 'Nächste Seite', range: '{{start}}–{{end}} von {{length}}' } });
    translate.use('de');
    expect(intl.nextPageLabel).toBe('Nächste Seite');
    expect(intl.getRangeLabel(1, 10, 1234)).toBe('11–20 von 1.234');
    expect(changes).toBeGreaterThan(0);
  });
});
