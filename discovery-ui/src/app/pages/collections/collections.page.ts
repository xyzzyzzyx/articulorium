import { ChangeDetectionStrategy, Component, computed, effect, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';

import { ConfigService } from '../../core/config/config.service';
import { ResponseStatusService } from '../../core/http/response-status.service';
import { LocaleService } from '../../core/i18n/locale.service';
import { SeoService } from '../../core/seo/seo.service';
import { SearchBoxComponent } from '../../layout/search-box.component';
import { CollectionTreeComponent } from './collection-tree.component';
import { CollectionTreeStore } from './collection-tree.store';

/**
 * The communities and collections page: a tree browser of the containers below the community root
 * (`layout.home.collections-path`). The server renders the top level; deeper levels load in the
 * browser as the visitor expands them. Deployments that switch the page off answer 404.
 */
@Component({
  selector: 'disc-collections-page',
  imports: [CollectionTreeComponent, MatButtonModule, MatIconModule, RouterLink, SearchBoxComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (enabled()) {
      <div class="disc-collections-header">
        <h1>{{ 'collections.title' | translate }}</h1>
        @if (store.expandedPaths().size > 0) {
          <button mat-button type="button" (click)="store.collapseAll()">
            <mat-icon aria-hidden="true">unfold_less</mat-icon>
            {{ 'collections.collapseAll' | translate }}
          </button>
        }
      </div>
      <p class="disc-collections-intro">{{ 'collections.intro' | translate }}</p>
      <section class="disc-section" [attr.aria-label]="'collections.title' | translate">
        <disc-collection-tree [parent]="root()" />
      </section>
    } @else {
      <h1>{{ 'errors.notFoundTitle' | translate }}</h1>
      <p class="disc-message">{{ 'errors.pageNotFound' | translate }}</p>
      <div class="disc-section"><disc-search-box /></div>
      <p><a [routerLink]="['/', locale()]">{{ 'nav.home' | translate }}</a></p>
    }
  `,
  styles: `
    .disc-collections-header {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
    }
    .disc-collections-intro {
      max-width: 70ch;
      color: var(--mat-sys-on-surface-variant);
    }
  `,
})
export class CollectionsPage {
  private readonly config = inject(ConfigService);

  private readonly locales = inject(LocaleService);

  protected readonly store = inject(CollectionTreeStore);

  protected readonly locale = this.locales.locale;

  protected readonly enabled = computed(() => this.config.config().layout.collections.enabled);

  protected readonly root = computed(() => this.config.config().layout.home.collectionsPath);

  constructor() {
    const seo = inject(SeoService);
    const status = inject(ResponseStatusService);
    effect(() => {
      this.locale();
      if (!this.enabled()) {
        status.set(404);
        seo.update({ title: this.locales.t('errors.notFoundTitle'), path: 'collections', index: false, alternates: false });
        return;
      }
      // the top level could not be loaded: crawlers should come back rather than index an empty page
      const top = this.store.level(this.locale(), this.root());
      if (top?.failed && top.entries.length === 0) {
        status.set(503);
      }
      seo.update({
        title: this.locales.t('collections.title'),
        description: this.locales.t('collections.intro'),
        path: 'collections',
        index: true,
      });
    });
  }
}
