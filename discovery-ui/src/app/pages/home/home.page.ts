import { ChangeDetectionStrategy, Component, computed, effect, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';

import { DiscoveryApiService } from '../../core/api/discovery-api.service';
import { ConfigService } from '../../core/config/config.service';
import { LocaleService } from '../../core/i18n/locale.service';
import { SeoService } from '../../core/seo/seo.service';
import { siteStructuredData } from '../../core/seo/structured-data';
import { SearchBoxComponent } from '../../layout/search-box.component';
import { ResourceCardComponent } from '../../shared/resource-card.component';

/**
 * The home page: the site's sections in the configured order (introduction, search, top-level
 * communities and collections, recent resources).
 */
@Component({
  selector: 'disc-home-page',
  imports: [ResourceCardComponent, RouterLink, SearchBoxComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (!sections().includes('HERO')) {
      <h1 class="disc-visually-hidden">{{ siteName() }}</h1>
    }
    @for (section of sections(); track section) {
      @switch (section) {
        @case ('HERO') {
          <section class="disc-hero" aria-labelledby="disc-home-title">
            <h1 id="disc-home-title">{{ siteName() }}</h1>
            @if (tagline()) {
              <p class="disc-tagline">{{ tagline() }}</p>
            }
            @if (description()) {
              <p class="disc-description">{{ description() }}</p>
            }
          </section>
        }
        @case ('SEARCH') {
          <section class="disc-section" aria-labelledby="disc-home-search">
            <h2 id="disc-home-search" class="disc-visually-hidden">{{ 'home.search' | translate }}</h2>
            <disc-search-box [large]="true" />
          </section>
        }
        @case ('COLLECTIONS') {
          @if (collections.value()?.results?.length) {
            <section class="disc-section" aria-labelledby="disc-home-collections">
              <h2 id="disc-home-collections">{{ 'home.collections' | translate }}</h2>
              <ul class="disc-card-grid">
                @for (resource of collections.value()!.results; track resource.path) {
                  <li><disc-resource-card [resource]="resource" [showThumbnail]="thumbnails()" /></li>
                }
              </ul>
              @if (treeEnabled()) {
                <p>
                  <a [routerLink]="['/', locale(), 'collections']">{{
                    (collections.value()!.total > collections.value()!.results.length ? 'home.allCollections' : 'home.collectionTree') | translate
                  }}</a>
                </p>
              }
            </section>
          }
        }
        @case ('RECENT') {
          @if (recent.value()?.results?.length) {
            <section class="disc-section" aria-labelledby="disc-home-recent">
              <h2 id="disc-home-recent">{{ 'home.recent' | translate }}</h2>
              <ul class="disc-card-grid">
                @for (resource of recent.value()!.results; track resource.path) {
                  <li><disc-resource-card [resource]="resource" [showThumbnail]="thumbnails()" /></li>
                }
              </ul>
              <p><a [routerLink]="['/', locale(), 'search']">{{ 'home.browseAll' | translate }}</a></p>
            </section>
          }
        }
      }
    }
    @if (collections.error() && recent.error()) {
      <p class="disc-message" role="alert">{{ 'errors.search' | translate }}</p>
    }
  `,
  styles: `
    .disc-hero {
      padding: 32px 0 8px;
    }
    .disc-hero h1 {
      font: var(--mat-sys-display-small);
      font-family: var(--disc-brand-font);
    }
    .disc-tagline {
      font: var(--mat-sys-title-large);
      color: var(--mat-sys-primary);
      margin: 0 0 12px;
    }
    .disc-description {
      max-width: 70ch;
      color: var(--mat-sys-on-surface-variant);
    }
  `,
})
export class HomePage {
  private readonly config = inject(ConfigService);

  private readonly api = inject(DiscoveryApiService);

  private readonly locales = inject(LocaleService);

  private readonly seo = inject(SeoService);

  protected readonly locale = this.locales.locale;

  protected readonly sections = computed(() => this.config.config().layout.home.sections);

  protected readonly treeEnabled = computed(() => this.config.config().layout.collections.enabled);

  protected readonly thumbnails = computed(() => this.config.config().layout.results.thumbnails);

  protected readonly siteName = computed(() => this.locales.text(this.config.config().site.name));

  protected readonly tagline = computed(() => this.locales.text(this.config.config().site.tagline));

  protected readonly description = computed(() => this.locales.text(this.config.config().site.description));

  protected readonly collections = rxResource({
    params: () => {
      const home = this.config.config().layout.home;
      return home.sections.includes('COLLECTIONS') && home.collectionsLimit > 0
        ? { lang: this.locale(), within: home.collectionsPath, size: home.collectionsLimit }
        : undefined;
    },
    stream: ({ params }) =>
      this.api.search(
        { q: '', page: 1, size: params.size, sort: undefined, facets: {}, filters: [], within: params.within },
        params.lang,
      ),
  });

  protected readonly recent = rxResource({
    params: () => {
      const home = this.config.config().layout.home;
      return home.sections.includes('RECENT') && home.recentLimit > 0
        ? { lang: this.locale(), size: home.recentLimit }
        : undefined;
    },
    stream: ({ params }) =>
      this.api.search({ q: '', page: 1, size: params.size, facets: {}, filters: [], kinds: ['OBJECT'] }, params.lang),
  });

  constructor() {
    effect(() => {
      const config = this.config.config();
      const name = this.siteName();
      const description = this.description();
      this.seo.update({
        title: '',
        description,
        path: '',
        index: true,
        image: config.site.logoUrl && !config.site.defaultImageUrl ? config.site.logoUrl : undefined,
        structuredData: siteStructuredData(this.seo.url(''), this.seo.url('search'), name, description, {
          name: config.site.organizationName,
          url: config.site.organizationUrl,
          logo: config.site.logoUrl,
        }),
      });
    });
  }
}
