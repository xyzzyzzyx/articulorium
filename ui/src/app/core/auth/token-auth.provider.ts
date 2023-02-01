import { signal } from '@angular/core';

import { AuthProvider } from './auth-provider';

/** Key under which the token is kept for the browser tab's session. */
export const TOKEN_KEY = 'articulorium.token';

/**
 * Signs in with a token the user pastes (`articulorium.ui.auth.provider: token`), for example a
 * Consul ACL token (its secret ID). The token lives in the tab's session storage, so it survives
 * reloads but not closing the tab; when storage is unavailable it is kept in memory only.
 */
export class TokenAuthProvider implements AuthProvider {
  readonly kind = 'token';

  readonly redirects = false;

  private readonly state = signal<string | undefined>(readToken());

  readonly token = this.state.asReadonly();

  readonly error = signal<string | undefined>(undefined).asReadonly();

  init(): Promise<void> {
    return Promise.resolve();
  }

  /**
   * Uses a token from now on; an empty token signs out.
   *
   * @param credential - the token
   * @returns resolved once stored
   */
  signIn(credential = ''): Promise<void> {
    const value = credential.trim();
    if (!value) {
      return this.forget();
    }
    this.state.set(value);
    try {
      sessionStorage.setItem(TOKEN_KEY, value);
    } catch {
      // storage unavailable: keep the token in memory only
    }
    return Promise.resolve();
  }

  signOut(): Promise<void> {
    return this.forget();
  }

  forget(): Promise<void> {
    this.state.set(undefined);
    try {
      sessionStorage.removeItem(TOKEN_KEY);
    } catch {
      // storage unavailable: nothing was stored
    }
    return Promise.resolve();
  }
}

/**
 * Reads a token stored by an earlier page of the same tab.
 *
 * @returns the token, or `undefined`
 */
function readToken(): string | undefined {
  try {
    return sessionStorage.getItem(TOKEN_KEY) ?? undefined;
  } catch {
    return undefined;
  }
}
