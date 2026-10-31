import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatCardModule } from '@angular/material/card';

import { CLIENT, Client } from '../../main';

/**
 * About Articulorum: what the user interface offers and the client it runs in.
 */
@Component({
  selector: 'ui-about',
  imports: [MatCardModule],
  templateUrl: './about.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './about.component.scss',
})
export class AboutComponent {
  /** The browser description computed at start-up, when available. */
  protected readonly client = inject<Client | null>(CLIENT, { optional: true });
}
