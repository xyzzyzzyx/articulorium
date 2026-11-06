import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatToolbarModule } from '@angular/material/toolbar';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { AuthService } from './core/auth/auth.service';
import { ConfigService } from './core/config.service';
import { WebAcService } from './core/webac/webac.service';
import { Notifier } from './shared/notifier.service';
import { SignInDialogComponent } from './shared/sign-in-dialog.component';

/**
 * Application shell: the navigation bar (Browse, Communities, Topologies, Search, API, About),
 * the signed-in agent, the current view and the footer.
 */
@Component({
  selector: 'ui-root',
  imports: [MatButtonModule, MatIconModule, MatToolbarModule, RouterLink, RouterLinkActive, RouterOutlet],
  templateUrl: './app.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './app.component.scss',
})
export class AppComponent {
  private readonly config = inject(ConfigService);

  protected readonly auth = inject(AuthService);

  private readonly webac = inject(WebAcService);

  private readonly dialog = inject(MatDialog);

  private readonly notifier = inject(Notifier);

  /** The application title from the service settings. */
  protected readonly title = computed(() => this.config.config().title);

  /** The gateway's Swagger UI, which lists the OpenAPI descriptions of every service. */
  protected readonly apiDocs = computed(() => {
    this.config.config();
    return this.config.apiDocs();
  });

  /** The signed-in agent's identifier. */
  protected readonly agent = signal<string | undefined>(undefined);

  /** The last segment of the agent's identifier, for display. */
  protected readonly shortAgent = computed(() => {
    const agent = this.agent() ?? '';
    const name = decodeURIComponent(agent.substring(Math.max(agent.lastIndexOf('/'), agent.lastIndexOf('#')) + 1));
    return name || agent;
  });

  constructor() {
    const failure = this.auth.error();
    if (failure) {
      this.notifier.error('Signing in', failure);
    }
    if (this.auth.signedIn()) {
      this.webac.access('').subscribe({
        next: (info) => this.agent.set(info.agent.authenticated ? (info.agent.webId ?? undefined) : undefined),
        // the repository rejects the credentials (expired, revoked, another issuer): drop them here
        error: () => void this.auth.forget(),
      });
    }
  }

  /** Leaves for the identity provider's login page, or asks for a token. */
  protected signIn(): void {
    if (this.auth.redirects()) {
      this.auth.signIn().catch((error: unknown) => this.notifier.error('Signing in', error));
      return;
    }
    this.dialog
      .open(SignInDialogComponent, { width: '480px' })
      .afterClosed()
      .subscribe((agent?: string) => {
        if (agent) {
          this.agent.set(agent);
          this.notifier.success('Signed in');
        }
      });
  }

  /** Signs out (at the identity provider too, which then returns to the user interface). */
  protected signOut(): void {
    const redirects = this.auth.redirects();
    this.auth.signOut().then(
      () => {
        this.agent.set(undefined);
        if (!redirects) {
          this.notifier.success('Signed out');
        }
      },
      (error: unknown) => this.notifier.error('Signing out', error),
    );
  }
}
