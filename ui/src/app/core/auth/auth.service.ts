import { Injectable, computed, signal } from '@angular/core';

/** Key under which the token is kept for the browser tab's session. */
export const TOKEN_KEY = 'articulorum.token';

/**
 * The credentials the user interface presents: a Consul ACL token entered by the user, sent as
 * `Authorization: Bearer <token>` to the platform, the topology service and the transform service
 * (see {@link authInterceptor}). The token lives in the tab's session storage, so it survives
 * reloads but not closing the tab; when storage is unavailable it is kept in memory only.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly state = signal<string | undefined>(readToken());

  /** The current token, `undefined` when signed out. */
  readonly token = this.state.asReadonly();

  /** Whether a token is set. */
  readonly signedIn = computed(() => !!this.state());

  /**
   * Uses a token from now on.
   *
   * @param token - the Consul ACL token (secret ID)
   */
  signIn(token: string): void {
    const value = token.trim();
    if (!value) {
      this.signOut();
      return;
    }
    this.state.set(value);
    try {
      sessionStorage.setItem(TOKEN_KEY, value);
    } catch {
      // storage unavailable: keep the token in memory only
    }
  }

  /** Forgets the token. */
  signOut(): void {
    this.state.set(undefined);
    try {
      sessionStorage.removeItem(TOKEN_KEY);
    } catch {
      // storage unavailable: nothing was stored
    }
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
