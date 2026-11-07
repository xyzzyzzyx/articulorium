import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { NavigationEnd, Router } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { filter, map } from 'rxjs';

import { ConfigService } from '../core/config/config.service';
import { LocaleService } from '../core/i18n/locale.service';

/**
 * The languages of the site: links to the current page in every other locale (full page loads,
 * so the page is rendered and indexed in that language).
 */
@Component({
  selector: 'disc-language-menu',
  imports: [MatButtonModule, MatIconModule, MatMenuModule, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (locales().length > 1) {
      <button
        mat-button
        type="button"
        [matMenuTriggerFor]="menu"
        [attr.aria-label]="'nav.languageMenu' | translate: { language: current().name }"
      >
        <mat-icon aria-hidden="true">language</mat-icon>
        <span [attr.lang]="current().tag">{{ current().name }}</span>
      </button>
      <mat-menu #menu="matMenu">
        @for (locale of locales(); track locale.tag) {
          <a
            mat-menu-item
            [href]="href(locale.tag)"
            [attr.lang]="locale.tag"
            [attr.hreflang]="locale.tag"
            [attr.aria-current]="locale.tag === current().tag ? 'true' : null"
          >
            {{ locale.name }}
          </a>
        }
      </mat-menu>
    }
  `,
})
export class LanguageMenuComponent {
  private readonly config = inject(ConfigService);

  private readonly localeService = inject(LocaleService);

  private readonly router = inject(Router);

  private readonly url = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map(() => this.router.url),
    ),
    { initialValue: this.router.url },
  );

  protected readonly locales = computed(() => this.config.config().locales.available);

  protected readonly current = this.localeService.descriptor;

  /** The current page (path and query) in another locale, relative to the base href. */
  protected href(tag: string): string {
    const rest = this.url().replace(/^\/[^/?#]*/, '');
    return `${tag}${rest || '/'}`;
  }
}
