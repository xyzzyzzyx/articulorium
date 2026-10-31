import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** Settings served by the service module at `api/config`. */
export interface UiConfig {
  /** Where browsers reach the LDP root, relative to the document base or absolute. */
  readonly platformUrl: string;
  /** Title of the application. */
  readonly title: string;
  /** The Swagger UI listing every service's API (the gateway's `/swagger-ui.html`). */
  readonly apiDocsUrl?: string;
  /** Where browsers reach the topology service. */
  readonly topologyUrl?: string;
  /** Where browsers reach the transform service (IIIF exports). */
  readonly transformUrl?: string;
  /** Path (below the LDP root) of the community structure. */
  readonly communitiesPath?: string;
}

/** Settings used when `api/config` cannot be loaded (for example `ng serve` without a backend). */
export const FALLBACK_CONFIG: Required<UiConfig> = {
  platformUrl: '../platform/',
  title: 'Articulorum',
  apiDocsUrl: '../swagger-ui.html',
  topologyUrl: '../topology/',
  transformUrl: '../transform/',
  communitiesPath: 'communities',
};

/**
 * Loads the user interface settings once at start-up and resolves the platform root against the
 * document's base URI, so every other component works with an absolute platform base.
 */
@Injectable({ providedIn: 'root' })
export class ConfigService {
  private readonly http = inject(HttpClient);

  private readonly state = signal<UiConfig>(FALLBACK_CONFIG);

  /** The current settings. */
  readonly config = this.state.asReadonly();

  /**
   * Loads `api/config`; falls back to {@link FALLBACK_CONFIG} when it is unavailable.
   *
   * @returns a promise resolved once the settings are known
   */
  async load(): Promise<void> {
    try {
      this.state.set(await firstValueFrom(this.http.get<UiConfig>('api/config')));
    } catch (error) {
      console.warn('Using default UI settings: api/config is unavailable', error);
    }
  }

  /**
   * The absolute IRI of the LDP root container, always ending with `/`.
   *
   * @returns for example `http://localhost:8000/platform/`
   */
  platformBase(): string {
    return resolvePlatformBase(this.state().platformUrl, document.baseURI);
  }

  /**
   * The absolute URL of the API documentation (the gateway's Swagger UI).
   *
   * @returns for example `http://localhost:8000/swagger-ui.html`
   */
  apiDocs(): string {
    return new URL(this.state().apiDocsUrl || FALLBACK_CONFIG.apiDocsUrl, document.baseURI).toString();
  }

  /**
   * The absolute base of the topology service, ending with `/`.
   *
   * @returns for example `http://localhost:8000/topology/`
   */
  topologyBase(): string {
    return resolvePlatformBase(this.state().topologyUrl || FALLBACK_CONFIG.topologyUrl, document.baseURI);
  }

  /**
   * The absolute base of the transform service, ending with `/`.
   *
   * @returns for example `http://localhost:8000/transform/`
   */
  transformBase(): string {
    return resolvePlatformBase(this.state().transformUrl || FALLBACK_CONFIG.transformUrl, document.baseURI);
  }

  /**
   * The path of the community structure below the LDP root.
   *
   * @returns for example `communities`
   */
  communitiesPath(): string {
    return (this.state().communitiesPath || FALLBACK_CONFIG.communitiesPath).replace(/^\/+|\/+$/g, '');
  }
}

/**
 * Resolves a configured platform URL against a base URI.
 *
 * @param platformUrl - the configured URL (relative or absolute)
 * @param baseUri - the document base URI
 * @returns the absolute platform base, ending with `/`
 */
export function resolvePlatformBase(platformUrl: string, baseUri: string): string {
  const resolved = new URL(platformUrl || FALLBACK_CONFIG.platformUrl, baseUri).toString();
  return resolved.endsWith('/') ? resolved : `${resolved}/`;
}
