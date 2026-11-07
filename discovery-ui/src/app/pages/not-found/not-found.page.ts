import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';

import { ResponseStatusService } from '../../core/http/response-status.service';
import { LocaleService } from '../../core/i18n/locale.service';
import { SeoService } from '../../core/seo/seo.service';
import { SearchBoxComponent } from '../../layout/search-box.component';

/** Pages that do not exist: HTTP 404, not indexed, with a way back. */
@Component({
  selector: 'disc-not-found-page',
  imports: [RouterLink, SearchBoxComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>{{ 'errors.notFoundTitle' | translate }}</h1>
    <p class="disc-message">{{ 'errors.pageNotFound' | translate }}</p>
    <div class="disc-section"><disc-search-box /></div>
    <p><a [routerLink]="['/', locale()]">{{ 'nav.home' | translate }}</a></p>
  `,
})
export class NotFoundPage {
  protected readonly locale = inject(LocaleService).locale;

  constructor() {
    inject(ResponseStatusService).set(404);
    const locales = inject(LocaleService);
    inject(SeoService).update({ title: locales.t('errors.notFoundTitle'), path: '', index: false, alternates: false });
  }
}
