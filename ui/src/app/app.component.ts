import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatToolbarModule } from '@angular/material/toolbar';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { ConfigService } from './core/config.service';

/**
 * Application shell: the navigation bar (Browse, Search, API, About), the current view and the
 * footer.
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

  /** The application title from the service settings. */
  protected readonly title = computed(() => this.config.config().title);

  /** Swagger UI of the platform's OpenAPI description. */
  protected readonly apiDocs = computed(() => `${this.config.platformBase()}.well-known/swagger-ui.html`);
}
