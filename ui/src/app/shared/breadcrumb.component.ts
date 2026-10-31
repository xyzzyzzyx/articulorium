import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';

import { routeTo, segmentsOf } from '../core/ldp/paths';

/**
 * The path from the root container to a resource, each ancestor linking to its browse view.
 */
@Component({
  selector: 'ui-breadcrumb',
  imports: [MatIconModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nav class="ui-breadcrumb" aria-label="Breadcrumb">
      <ol>
        <li><a [routerLink]="['/browse']"><mat-icon inline>home</mat-icon> Root</a></li>
        @for (crumb of crumbs(); track crumb.path; let last = $last) {
          <li>
            @if (last && !suffix()) {
              <span aria-current="page">{{ crumb.name }}</span>
            } @else {
              <a [routerLink]="crumb.route">{{ crumb.name }}</a>
            }
          </li>
        }
        @if (suffix(); as label) {
          <li><span aria-current="page">{{ label }}</span></li>
        }
      </ol>
    </nav>
  `,
  styles: `
    ol { display: flex; flex-wrap: wrap; gap: 4px; list-style: none; margin: 0 0 16px; padding: 0; }
    li + li::before { content: '/'; margin-right: 4px; color: #999; }
    a { text-decoration: none; }
    mat-icon { vertical-align: text-bottom; }
  `,
})
export class BreadcrumbComponent {
  /** The resource path. */
  readonly path = input.required<string>();

  /** An extra trailing crumb, for example `Versions`. */
  readonly suffix = input<string>();

  /** One crumb per ancestor and the resource itself. */
  protected readonly crumbs = computed(() => {
    const segments = segmentsOf(this.path());
    return segments.map((name, index) => {
      const path = segments.slice(0, index + 1).join('/');
      return { name, path, route: routeTo('browse', path) };
    });
  });
}
