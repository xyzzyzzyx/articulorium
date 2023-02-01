import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { filter, map } from 'rxjs';

import { ConfigService } from './core/config/config.service';
import { ResponseStatusService } from './core/http/response-status.service';
import { SiteFooterComponent } from './layout/site-footer.component';
import { SiteHeaderComponent } from './layout/site-header.component';

/**
 * The page frame: a skip link, the site's header, the page and the footer. While the discovery
 * service is unavailable, the frame says so (HTTP 503) instead of rendering pages.
 */
@Component({
  selector: 'disc-root',
  imports: [RouterOutlet, SiteFooterComponent, SiteHeaderComponent, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a class="disc-skip-link" href="#main" (click)="skip($event)">{{ 'nav.skip' | translate }}</a>
    <disc-site-header [search]="headerSearch()" />
    <main id="main" class="disc-container" tabindex="-1">
      @if (unavailable()) {
        <h1>{{ 'errors.unavailableTitle' | translate }}</h1>
        <p class="disc-message" role="alert">{{ 'errors.unavailable' | translate }}</p>
      } @else {
        <router-outlet />
      }
    </main>
    <disc-site-footer />
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      min-height: 100vh;
    }
    main {
      flex: 1;
      padding-block: 24px;
      outline: none;
    }
    .disc-skip-link {
      position: absolute;
      inset-inline-start: 8px;
      top: -48px;
      z-index: 10;
      padding: 8px 16px;
      border-radius: var(--mat-sys-corner-small);
      background: var(--mat-sys-primary);
      color: var(--mat-sys-on-primary);
    }
    .disc-skip-link:focus {
      top: 8px;
    }
  `,
})
export class App {
  private readonly router = inject(Router);

  protected readonly unavailable = inject(ConfigService).unavailable;

  private readonly url = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map((event) => event.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );

  /** The home and search pages have their own search field. */
  protected readonly headerSearch = computed(() => {
    const path = this.url().split(/[?#]/)[0];
    return !/^\/[^/]+\/?$/.test(path) && !/^\/[^/]+\/search\/?$/.test(path);
  });

  constructor() {
    if (this.unavailable()) {
      inject(ResponseStatusService).set(503);
    }
  }

  /** Moves the focus to the page's content (the hash alone does not in every browser). */
  protected skip(event: Event): void {
    event.preventDefault();
    const main = (event.target as HTMLElement).ownerDocument.getElementById('main');
    main?.focus();
  }
}
