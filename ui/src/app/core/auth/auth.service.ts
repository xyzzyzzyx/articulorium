import { Injectable, computed, inject, signal } from '@angular/core';

import { ConfigService } from '../config.service';
import { AuthProvider, AuthProviderKind, NoAuthProvider } from './auth-provider';
import { OIDC_CLIENT_FACTORY, OidcAuthProvider } from './oidc-auth.provider';
import { TokenAuthProvider } from './token-auth.provider';

export { TOKEN_KEY } from './token-auth.provider';

/**
 * The credentials the user interface presents, from the {@link AuthProvider} the service's
 * settings choose (`api/config`, `auth.provider`): an OpenID Connect provider (`oidc`, Keycloak by
 * default), a pasted token (`token`, for example a Consul ACL token) or none. The token is sent as
 * `Authorization: Bearer <token>` to the platform, the topology service and the transform service
 * (see {@link authInterceptor}).
 *
 * Until {@link init} runs (after the settings are loaded) a pasted-token provider is active.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly config = inject(ConfigService);

  private readonly oidcClients = inject(OIDC_CLIENT_FACTORY);

  private readonly provider = signal<AuthProvider>(new TokenAuthProvider());

  /** The kind of sign-in offered. */
  readonly kind = computed<AuthProviderKind>(() => this.provider().kind);

  /** Whether signing in leaves for an identity provider's page. */
  readonly redirects = computed(() => this.provider().redirects);

  /** The current token, `undefined` when signed out. */
  readonly token = computed(() => this.provider().token());

  /** Whether a token is set. */
  readonly signedIn = computed(() => !!this.token());

  /** Why the last sign-in failed. */
  readonly error = computed(() => this.provider().error());

  /**
   * Chooses the provider the settings name and restores the tab's session.
   *
   * @returns resolved once the session is known
   */
  async init(): Promise<void> {
    const auth = this.config.auth();
    const provider: AuthProvider =
      auth.provider === 'oidc'
        ? new OidcAuthProvider(
            {
              authority: auth.oidc.authority,
              clientId: auth.oidc.clientId,
              scope: auth.oidc.scope,
              baseUri: document.baseURI,
            },
            this.oidcClients,
          )
        : auth.provider === 'none'
          ? new NoAuthProvider()
          : new TokenAuthProvider();
    this.provider.set(provider);
    try {
      await provider.init();
    } catch (error: unknown) {
      console.warn('Unable to restore the session', error);
    }
  }

  /**
   * Signs in: leaves for the identity provider, or uses the given credential.
   *
   * @param credential - the token, for the pasted-token provider
   * @returns resolved once signed in or navigating away
   */
  signIn(credential?: string): Promise<void> {
    return this.provider().signIn(credential);
  }

  /**
   * Signs out (at the identity provider too).
   *
   * @returns resolved once signed out or navigating away
   */
  signOut(): Promise<void> {
    return this.provider().signOut();
  }

  /**
   * Forgets this tab's credentials without leaving the page.
   *
   * @returns resolved once forgotten
   */
  forget(): Promise<void> {
    return this.provider().forget();
  }
}
