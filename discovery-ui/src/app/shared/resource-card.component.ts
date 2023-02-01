import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';

import { SearchResult } from '../core/api/discovery-api';
import { LocaleService } from '../core/i18n/locale.service';
import { itemLink } from './links';

/**
 * A resource as a card: thumbnail, title (a third-level heading) linking to its page, kind, date
 * and description.
 */
@Component({
  selector: 'disc-resource-card',
  imports: [RouterLink, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <article class="disc-card">
      @if (showThumbnail() && resource().thumbnail) {
        <img [src]="resource().thumbnail" alt="" loading="lazy" decoding="async" />
      }
      <div class="disc-card-body">
        <h3>
          <a [routerLink]="link()">{{ resource().title }}</a>
        </h3>
        <span><span class="disc-kind">{{ 'kinds.' + resource().kind.toLowerCase() | translate }}</span></span>
        @if (resource().date) {
          <span class="disc-muted">{{ date() }}</span>
        }
        @if (resource().description) {
          <p class="disc-card-description">{{ resource().description }}</p>
        }
      </div>
    </article>
  `,
  styles: `
    .disc-card-description {
      margin: 0;
      color: var(--mat-sys-on-surface-variant);
      display: -webkit-box;
      -webkit-line-clamp: 4;
      -webkit-box-orient: vertical;
      overflow: hidden;
    }
  `,
})
export class ResourceCardComponent {
  private readonly locales = inject(LocaleService);

  readonly resource = input.required<SearchResult>();

  readonly showThumbnail = input(true);

  protected readonly link = computed(() => itemLink(this.locales.locale(), this.resource().path));

  protected readonly date = computed(() => this.locales.date(this.resource().date));
}
