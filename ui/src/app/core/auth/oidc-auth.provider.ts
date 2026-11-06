import { InjectionToken, signal } from '@angular/core';
import { SigninRedirectArgs, User, UserManager, UserManagerSettings, WebStorageStateStore } from 'oidc-client-ts';

import { AuthProvider } from './auth-provider';

/** The OpenID Connect client settings of `api/config`, with the authority resolved. */
export interface OidcSettings {
  /** The issuer, absolute. */
  readonly authority: string;
  /** The public client registered with the provider. */
  readonly clientId: string;
  /** Requested scopes. */
  readonly scope: string;
  /** The user interface's base URI: where the provider redirects back to after signing in or out. */
  readonly baseUri: string;
}

/** The part of oidc-client-ts' `UserManager` the provider uses (replaced in tests). */
export interface OidcClient {
  getUser(): Promise<User | null>;
  signinRedirect(args?: SigninRedirectArgs): Promise<void>;
  signinRedirectCallback(url?: string): Promise<User>;
  signoutRedirect(): Promise<void>;
  removeUser(): Promise<void>;
  readonly events: {
    addUserLoaded(callback: (user: User) => void): unknown;
    addUserUnloaded(callback: () => void): unknown;
    addAccessTokenExpired(callback: () => void): unknown;
    addSilentRenewError(callback: (error: Error) => void): unknown;
  };
}

/** Creates the OpenID Connect client from its settings. */
export type OidcClientFactory = (settings: UserManagerSettings) => OidcClient;

/** The OpenID Connect client factory; tests provide a fake. */
export const OIDC_CLIENT_FACTORY = new InjectionToken<OidcClientFactory>('OIDC_CLIENT_FACTORY', {
  providedIn: 'root',
  factory: () => (settings) => new UserManager(settings),
});

/**
 * Builds the client settings: the authorization code flow with PKCE as a public client, tokens
 * kept in the tab's session storage and renewed before they expire (with the refresh token the
 * provider issues).
 *
 * @param settings - the provider settings
 * @returns the oidc-client-ts settings
 */
export function userManagerSettings(settings: OidcSettings): UserManagerSettings {
  return {
    authority: settings.authority,
    client_id: settings.clientId,
    scope: settings.scope,
    redirect_uri: settings.baseUri,
    post_logout_redirect_uri: settings.baseUri,
    response_type: 'code',
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
    stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
    automaticSilentRenew: true,
    loadUserInfo: false,
    monitorSession: false,
  };
}

/**
 * Signs in with an OpenID Connect provider (`articulorium.ui.auth.provider: oidc`): Keycloak by
 * default, or any provider supporting the authorization code flow with PKCE for public clients.
 * The access token is the bearer token; the platform validates it against the same issuer.
 */
export class OidcAuthProvider implements AuthProvider {
  readonly kind = 'oidc';

  readonly redirects = true;

  private readonly state = signal<string | undefined>(undefined);

  private readonly failure = signal<string | undefined>(undefined);

  readonly token = this.state.asReadonly();

  readonly error = this.failure.asReadonly();

  private readonly client: OidcClient;

  /**
   * Creates the provider.
   *
   * @param settings - the provider settings
   * @param factory - creates the client
   */
  constructor(
    private readonly settings: OidcSettings,
    factory: OidcClientFactory,
  ) {
    this.client = factory(userManagerSettings(settings));
    this.client.events.addUserLoaded((user) => this.state.set(user.access_token));
    this.client.events.addUserUnloaded(() => this.state.set(undefined));
    this.client.events.addAccessTokenExpired(() => this.state.set(undefined));
    this.client.events.addSilentRenewError((error) => console.warn('Unable to renew the access token', error));
  }

  /**
   * Completes a redirect back from the provider (then restores the page sign-in started from)
   * and restores the tab's session otherwise.
   *
   * @returns resolved once the session is known
   */
  async init(): Promise<void> {
    const location = new URL(window.location.href);
    if (this.isCallback(location)) {
      let target = new URL(this.settings.baseUri).pathname;
      try {
        const user = await this.client.signinRedirectCallback(location.toString());
        target = returnTarget(user.state) ?? target;
      } catch (error: unknown) {
        console.warn('Signing in failed', error);
        this.failure.set(
          location.searchParams.get('error_description') ?? location.searchParams.get('error') ?? 'Signing in failed',
        );
      }
      window.history.replaceState(window.history.state, '', target);
    }
    try {
      const user = await this.client.getUser();
      this.state.set(user && !user.expired ? user.access_token : undefined);
    } catch (error: unknown) {
      console.warn('Unable to restore the session', error);
    }
  }

  /**
   * Leaves for the provider's login page; it redirects back to the current page.
   *
   * @returns resolved when the browser navigates away
   */
  signIn(): Promise<void> {
    this.failure.set(undefined);
    const { pathname, search, hash } = window.location;
    return this.client.signinRedirect({ state: `${pathname}${search}${hash}` });
  }

  /**
   * Ends the session at the provider too; forgets the tokens when the provider cannot be reached.
   *
   * @returns resolved when the browser navigates away or the tokens are forgotten
   */
  async signOut(): Promise<void> {
    try {
      await this.client.signoutRedirect();
    } catch (error: unknown) {
      console.warn('Signing out at the identity provider failed', error);
      await this.forget();
    }
  }

  async forget(): Promise<void> {
    this.state.set(undefined);
    await this.client.removeUser();
  }

  private isCallback(location: URL): boolean {
    const redirect = new URL(this.settings.baseUri);
    return (
      location.origin === redirect.origin &&
      location.pathname === redirect.pathname &&
      location.searchParams.has('state') &&
      (location.searchParams.has('code') || location.searchParams.has('error'))
    );
  }
}

/**
 * Accepts only paths of this origin as the page to return to after signing in.
 *
 * @param state - the state carried through the provider
 * @returns the path, or `undefined`
 */
export function returnTarget(state: unknown): string | undefined {
  return typeof state === 'string' && state.startsWith('/') && !state.startsWith('//') && !state.includes('\\')
    ? state
    : undefined;
}
