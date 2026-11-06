import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { TranslatePipe } from '@ngx-translate/core';

import { CLIENT, Client } from '../../main';
import { I18nService } from '../core/i18n/i18n.service';

/**
 * About Articulorium: what the user interface offers and the client it runs in.
 */
@Component({
  selector: 'ui-about',
  imports: [MatCardModule, TranslatePipe],
  templateUrl: './about.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './about.component.scss',
})
export class AboutComponent {
  /** The browser description computed at start-up, when available. */
  protected readonly client = inject<Client | null>(CLIENT, { optional: true });

  /** The interface language. */
  protected readonly i18n = inject(I18nService);
}
