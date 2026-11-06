import { Signal, signal } from '@angular/core';

/** The kinds of sign-in the user interface supports (`articulorium.ui.auth.provider`). */
export type AuthProviderKind = 'oidc' | 'token' | 'none';

/**
 * A way of signing in. The rest of the user interface only sees the bearer token a provider
 * yields: the {@link authInterceptor} sends it to the repository's services, which turn it into
 * an agent. Providers never expose product specific details, so the identity provider behind
 * `oidc` (Keycloak by default) can be replaced by configuration alone.
 */
export interface AuthProvider {
  /** The kind of provider. */
  readonly kind: AuthProviderKind;

  /**
   * Whether signing in leaves the page (an identity provider's login page) rather than asking
   * for a credential in the user interface.
   */
  readonly redirects: boolean;

  /** The bearer token to present, `undefined` while signed out. */
  readonly token: Signal<string | undefined>;

  /** Why the last sign-in failed, `undefined` when it did not. */
  readonly error: Signal<string | undefined>;

  /**
   * Restores a session of this browser tab and completes a sign-in the identity provider
   * redirected back from. Called once at start-up, before the first navigation.
   */
  init(): Promise<void>;

  /**
   * Signs in.
   *
   * @param credential - the credential, for providers that do not redirect (a pasted token)
   */
  signIn(credential?: string): Promise<void>;

  /** Signs out, at the identity provider too where there is a session there. */
  signOut(): Promise<void>;

  /** Forgets the credentials of this tab only (for example when the repository rejects them). */
  forget(): Promise<void>;
}

/** No sign-in: every request is anonymous. */
export class NoAuthProvider implements AuthProvider {
  readonly kind = 'none';

  readonly redirects = false;

  readonly token = signal<string | undefined>(undefined).asReadonly();

  readonly error = signal<string | undefined>(undefined).asReadonly();

  init(): Promise<void> {
    return Promise.resolve();
  }

  signIn(): Promise<void> {
    return Promise.resolve();
  }

  signOut(): Promise<void> {
    return Promise.resolve();
  }

  forget(): Promise<void> {
    return Promise.resolve();
  }
}
