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

  private readonly auth = inject(AuthService);

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
    if (this.auth.signedIn()) {
      this.webac.access('').subscribe({
        next: (info) => this.agent.set(info.agent.authenticated ? (info.agent.webId ?? undefined) : undefined),
        error: () => this.auth.signOut(),
      });
    }
  }

  /** Opens the sign-in dialog. */
  protected signIn(): void {
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

  /** Forgets the token. */
  protected signOut(): void {
    this.auth.signOut();
    this.agent.set(undefined);
    this.notifier.success('Signed out');
  }
}
