import { PlatformLocation } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';

import { ConfigService } from '../core/config/config.service';
import { LocaleService } from '../core/i18n/locale.service';
import { isExternal, resolveLink } from '../shared/links';
import { LanguageMenuComponent } from './language-menu.component';
import { SearchBoxComponent } from './search-box.component';

/** The site's banner: logo and name, navigation, search field and languages. */
@Component({
  selector: 'disc-site-header',
  imports: [LanguageMenuComponent, MatButtonModule, RouterLink, RouterLinkActive, SearchBoxComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="disc-header">
      <div class="disc-container disc-header-row">
        <a class="disc-brand" [routerLink]="['/', locale()]">
          @if (logo(); as logo) {
            <img class="disc-logo" [src]="logo" [alt]="siteName()" />
          } @else {
            <span class="disc-site-name">{{ siteName() }}</span>
          }
        </a>
        <nav class="disc-nav" [attr.aria-label]="'nav.landmark' | translate">
          <a
            mat-button
            [routerLink]="['/', locale()]"
            routerLinkActive="disc-active"
            ariaCurrentWhenActive="page"
            [routerLinkActiveOptions]="{ exact: true }"
            >{{ 'nav.home' | translate }}</a
          >
          @if (collections()) {
            <a mat-button [routerLink]="['/', locale(), 'collections']" routerLinkActive="disc-active" ariaCurrentWhenActive="page">{{
              'nav.collections' | translate
            }}</a>
          }
          <a mat-button [routerLink]="['/', locale(), 'search']" routerLinkActive="disc-active" ariaCurrentWhenActive="page">{{
            'nav.browse' | translate
          }}</a>
          @for (link of links(); track link.href) {
            <a mat-button [href]="link.href" [attr.rel]="link.external ? 'noopener' : null">{{ link.label }}</a>
          }
        </nav>
        <disc-language-menu />
      </div>
      @if (showSearch()) {
        <div class="disc-container disc-header-search">
          <disc-search-box [query]="query()" />
        </div>
      }
    </header>
  `,
  styles: `
    .disc-header {
      background: var(--mat-sys-surface-container);
      border-bottom: 1px solid var(--mat-sys-outline-variant);
    }
    .disc-header-row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px 16px;
      min-height: 64px;
    }
    .disc-brand {
      display: inline-flex;
      align-items: center;
      color: var(--mat-sys-on-surface);
      text-decoration: none;
      margin-inline-end: auto;
    }
    .disc-site-name {
      font: var(--mat-sys-title-large);
      font-family: var(--disc-brand-font);
    }
    .disc-logo {
      display: block;
      max-height: 48px;
      max-width: min(240px, 60vw);
    }
    .disc-nav {
      display: flex;
      flex-wrap: wrap;
      gap: 4px;
    }
    .disc-active {
      text-decoration: underline;
      text-underline-offset: 6px;
    }
    .disc-header-search {
      padding-block: 4px 12px;
    }
  `,
})
export class SiteHeaderComponent {
  private readonly config = inject(ConfigService);

  private readonly locales = inject(LocaleService);

  private readonly base = inject(PlatformLocation).getBaseHrefFromDOM();

  /** Whether the header shows its search field (not on pages that have their own). */
  readonly search = input(true);

  /** The current search terms. */
  readonly query = input('');

  protected readonly locale = this.locales.locale;

  protected readonly siteName = computed(() => {
    this.locale();
    return this.locales.text(this.config.config().site.name);
  });

  protected readonly logo = computed(() => this.config.config().site.logoUrl);

  /** Whether the communities and collections page exists. */
  protected readonly collections = computed(() => this.config.config().layout.collections.enabled);

  protected readonly showSearch = computed(() => this.search() && this.config.config().layout.header.search);

  protected readonly links = computed(() => {
    const locale = this.locale();
    return this.config.config().layout.header.links.map((link) => ({
      label: this.locales.text(link.label),
      href: resolveLink(link.url, this.base, locale),
      external: isExternal(link.url),
    }));
  });
}
