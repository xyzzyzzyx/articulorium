import { ChangeDetectionStrategy, Component, computed, effect, inject, input, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';

import { ConfigService } from '../../core/config/config.service';
import { LocaleService } from '../../core/i18n/locale.service';
import { itemLink } from '../../shared/links';
import { CollectionTreeStore, TreeRequest, hasMore, treeLevelId } from './collection-tree.store';

/**
 * One level of the communities and collections tree: the containers below `parent`, each linking
 * to its page with a disclosure button that shows its own communities and collections below it
 * (loaded when first shown). Levels list a page at a time, with a button to load more.
 *
 * Disclosure buttons (`aria-expanded`, `aria-controls`) in nested lists keep the tree usable with
 * the keyboard and screen readers without the composite `tree` role, whose arrow-key model does
 * not suit rows that also hold links.
 */
@Component({
  selector: 'disc-collection-tree',
  imports: [MatButtonModule, MatIconModule, MatProgressSpinnerModule, RouterLink, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[attr.id]': 'levelId(parent())' },
  template: `
    @if (level(); as level) {
      @if (level.entries.length) {
        <ul class="disc-tree-level" [class.disc-tree-root]="depth() === 0">
          @for (entry of level.entries; track entry.path) {
            <li class="disc-tree-node">
              <div class="disc-tree-row">
                <button
                  mat-icon-button
                  type="button"
                  class="disc-tree-toggle"
                  [attr.aria-expanded]="isExpanded(entry.path)"
                  [attr.aria-controls]="isExpanded(entry.path) ? levelId(entry.path) : null"
                  [attr.aria-label]="(isExpanded(entry.path) ? 'collections.collapse' : 'collections.expand') | translate: { title: entry.title }"
                  (click)="store.toggle(entry.path)"
                >
                  <mat-icon aria-hidden="true">{{ isExpanded(entry.path) ? 'expand_more' : direction() === 'rtl' ? 'chevron_left' : 'chevron_right' }}</mat-icon>
                </button>
                <mat-icon class="disc-tree-kind-icon" aria-hidden="true">{{ entry.kind === 'COMMUNITY' ? 'account_balance' : 'collections_bookmark' }}</mat-icon>
                <a class="disc-tree-link" [routerLink]="link(entry.path)">{{ entry.title }}</a>
                <span class="disc-kind">{{ 'kinds.' + entry.kind.toLowerCase() | translate }}</span>
              </div>
              @if (isExpanded(entry.path)) {
                <disc-collection-tree [parent]="entry.path" [depth]="depth() + 1" />
              }
            </li>
          }
        </ul>
      } @else if (!level.loading && !level.failed) {
        <p class="disc-tree-note disc-muted">
          {{ (depth() === 0 ? 'collections.none' : 'collections.empty') | translate }}
        </p>
      }
      @if (level.loading) {
        <p class="disc-tree-note" role="status">
          <mat-spinner diameter="20" aria-hidden="true" />
          {{ 'collections.loading' | translate }}
        </p>
      } @else if (level.failed) {
        <p class="disc-tree-note" role="alert">
          {{ 'collections.failed' | translate }}
          <button mat-button type="button" (click)="store.load(request())">{{ 'collections.retry' | translate }}</button>
        </p>
      } @else if (more()) {
        <p class="disc-tree-note">
          <button mat-stroked-button type="button" class="disc-tree-more" (click)="store.more(request())">
            {{ 'collections.more' | translate: { shown: number(level.entries.length), total: number(level.total) } }}
          </button>
        </p>
      }
    }
  `,
  styles: `
    :host {
      display: block;
    }
    .disc-tree-level {
      list-style: none;
      margin: 0;
      padding-inline-start: 24px;
      border-inline-start: 1px solid var(--mat-sys-outline-variant);
    }
    .disc-tree-level.disc-tree-root {
      padding-inline-start: 0;
      border-inline-start: none;
    }
    .disc-tree-row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 4px 8px;
      min-height: 48px;
    }
    .disc-tree-kind-icon {
      color: var(--mat-sys-primary);
    }
    .disc-tree-link {
      font: var(--mat-sys-title-medium);
      overflow-wrap: anywhere;
    }
    .disc-tree-note {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
      margin: 4px 0 8px;
      padding-inline-start: 48px;
    }
  `,
})
export class CollectionTreeComponent {
  private readonly config = inject(ConfigService);

  private readonly locales = inject(LocaleService);

  protected readonly store = inject(CollectionTreeStore);

  /** The container whose communities and collections this level lists. */
  readonly parent = input.required<string>();

  /** Nesting depth, 0 for the top level. */
  readonly depth = input(0);

  protected readonly direction = this.locales.direction;

  protected readonly request = computed<TreeRequest>(() => {
    const settings = this.config.config().layout.collections;
    return { lang: this.locales.locale(), path: this.parent(), size: settings.pageSize, sort: settings.sort || undefined };
  });

  protected readonly level = computed(() => {
    const request = this.request();
    return this.store.level(request.lang, request.path);
  });

  protected readonly more = computed(() => {
    const level = this.level();
    return level ? hasMore(level) : false;
  });

  constructor() {
    effect(() => {
      const request = this.request();
      untracked(() => this.store.load(request));
    });
  }

  protected isExpanded(path: string): boolean {
    return this.store.expandedPaths().has(path);
  }

  /** The id of a container's level (its host element, which the disclosure button controls). */
  protected levelId(path: string): string {
    return treeLevelId(path);
  }

  protected link(path: string): string[] {
    return itemLink(this.locales.locale(), path);
  }

  protected number(value: number): string {
    return this.locales.number(value);
  }
}
