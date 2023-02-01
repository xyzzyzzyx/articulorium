import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { DiscoveryApiService } from '../api/discovery-api.service';
import { DiscoveryConfig } from './discovery-config';
import { FALLBACK_CONFIG } from './fallback-config';

/**
 * The deployment's configuration, loaded once before the application starts (an app
 * initializer): during server-side rendering from the discovery service, in browsers from the
 * HTTP transfer cache the server filled.
 */
@Injectable({ providedIn: 'root' })
export class ConfigService {
  private readonly api = inject(DiscoveryApiService);

  private readonly state = signal<DiscoveryConfig | undefined>(undefined);

  private readonly failure = signal(false);

  /** Whether the configuration could not be loaded (the service is unavailable). */
  readonly unavailable = this.failure.asReadonly();

  /**
   * The configuration.
   *
   * @throws Error before {@link load} completed
   */
  config(): DiscoveryConfig {
    const config = this.state();
    if (!config) {
      throw new Error('The discovery configuration is not loaded');
    }
    return config;
  }

  /** Whether the configuration is loaded. */
  loaded(): boolean {
    return this.state() !== undefined;
  }

  /**
   * Loads the configuration; when the discovery service cannot be reached, falls back to
   * {@link FALLBACK_CONFIG} and reports {@link unavailable}.
   */
  async load(): Promise<DiscoveryConfig> {
    try {
      const config = await firstValueFrom(this.api.config());
      this.state.set(config);
      this.failure.set(false);
      return config;
    } catch (error) {
      console.error('The discovery configuration cannot be loaded', error);
      this.state.set(FALLBACK_CONFIG);
      this.failure.set(true);
      return FALLBACK_CONFIG;
    }
  }

  /**
   * Sets the configuration (tests).
   *
   * @param config - the configuration
   */
  set(config: DiscoveryConfig): void {
    this.state.set(config);
  }
}
