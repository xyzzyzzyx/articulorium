import { PlatformLocation } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { TranslatePipe } from '@ngx-translate/core';

import { ConfigService } from '../core/config/config.service';
import { LocaleService } from '../core/i18n/locale.service';
import { isExternal, resolveLink } from '../shared/links';

/** The site's footer: the deployment's text and links. */
@Component({
  selector: 'disc-site-footer',
  imports: [TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <footer class="disc-footer">
      <div class="disc-container disc-footer-row">
        @if (text()) {
          <p class="disc-footer-text">{{ text() }}</p>
        }
        @if (links().length > 0) {
          <nav [attr.aria-label]="'nav.footer' | translate">
            <ul class="disc-footer-links">
              @for (link of links(); track link.href) {
                <li>
                  <a [href]="link.href" [attr.rel]="link.external ? 'noopener' : null">{{ link.label }}</a>
                </li>
              }
            </ul>
          </nav>
        }
        <p class="disc-muted disc-footer-iiif">{{ 'footer.iiif' | translate }}</p>
      </div>
    </footer>
  `,
  styles: `
    .disc-footer {
      margin-top: 48px;
      padding-block: 24px;
      background: var(--mat-sys-surface-container-high);
      color: var(--mat-sys-on-surface-variant);
    }
    .disc-footer-row {
      display: flex;
      flex-wrap: wrap;
      gap: 12px 32px;
      align-items: baseline;
      justify-content: space-between;
    }
    .disc-footer-text {
      margin: 0;
      white-space: pre-line;
    }
    .disc-footer-links {
      display: flex;
      flex-wrap: wrap;
      gap: 8px 16px;
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .disc-footer-iiif {
      margin: 0;
      font: var(--mat-sys-body-small);
    }
  `,
})
export class SiteFooterComponent {
  private readonly config = inject(ConfigService);

  private readonly locales = inject(LocaleService);

  private readonly base = inject(PlatformLocation).getBaseHrefFromDOM();

  protected readonly text = computed(() => {
    this.locales.locale();
    return this.locales.text(this.config.config().layout.footer.text);
  });

  protected readonly links = computed(() => {
    const locale = this.locales.locale();
    return this.config.config().layout.footer.links.map((link) => ({
      label: this.locales.text(link.label),
      href: resolveLink(link.url, this.base, locale),
      external: isExternal(link.url),
    }));
  });
}
