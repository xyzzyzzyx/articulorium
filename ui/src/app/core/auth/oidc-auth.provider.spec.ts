import { User, UserManagerSettings } from 'oidc-client-ts';

import { OidcAuthProvider, OidcClient, OidcSettings, returnTarget, userManagerSettings } from './oidc-auth.provider';

const BASE = new URL('/ui/', window.location.href).toString();

const SETTINGS: OidcSettings = {
  authority: 'http://idp.example/realms/test',
  clientId: 'test-ui',
  scope: 'openid profile',
  baseUri: BASE,
};

/** A user as oidc-client-ts stores it. */
function user(token: string, expired = false, state?: unknown): User {
  return { access_token: token, expired, state } as User;
}

/** An in-memory OpenID Connect client recording what the provider asks of it. */
class FakeClient implements OidcClient {
  stored: User | null = null;

  callback: () => Promise<User> = () => Promise.resolve(user('from-callback', false, '/ui/browse/books?x=1'));

  readonly redirects: unknown[] = [];

  callbackUrls: string[] = [];

  signedOut = 0;

  failSignOut = false;

  private readonly loaded: ((user: User) => void)[] = [];

  private readonly unloaded: (() => void)[] = [];

  private readonly expired: (() => void)[] = [];

  readonly events = {
    addUserLoaded: (callback: (user: User) => void) => this.loaded.push(callback),
    addUserUnloaded: (callback: () => void) => this.unloaded.push(callback),
    addAccessTokenExpired: (callback: () => void) => this.expired.push(callback),
    addSilentRenewError: () => undefined,
  };

  getUser(): Promise<User | null> {
    return Promise.resolve(this.stored);
  }

  signinRedirect(args?: unknown): Promise<void> {
    this.redirects.push(args);
    return Promise.resolve();
  }

  signinRedirectCallback(url?: string): Promise<User> {
    this.callbackUrls.push(url ?? '');
    return this.callback().then((loaded) => {
      this.stored = loaded;
      return loaded;
    });
  }

  signoutRedirect(): Promise<void> {
    this.signedOut++;
    return this.failSignOut ? Promise.reject(new Error('offline')) : Promise.resolve();
  }

  removeUser(): Promise<void> {
    this.stored = null;
    this.unloaded.forEach((callback) => callback());
    return Promise.resolve();
  }

  renew(token: string): void {
    this.loaded.forEach((callback) => callback(user(token)));
  }

  expire(): void {
    this.expired.forEach((callback) => callback());
  }
}

describe('OidcAuthProvider', () => {
  let client: FakeClient;
  let created: UserManagerSettings[];
  let provider: OidcAuthProvider;
  const original = window.location.href;

  beforeEach(() => {
    client = new FakeClient();
    created = [];
    provider = new OidcAuthProvider(SETTINGS, (settings) => {
      created.push(settings);
      return client;
    });
  });

  afterEach(() => {
    window.history.replaceState(null, '', original);
    sessionStorage.clear();
  });

  it('is a public client using the code flow and returning to the user interface', () => {
    expect(created).toHaveLength(1);
    expect(created[0]).toEqual(
      expect.objectContaining({
        authority: 'http://idp.example/realms/test',
        client_id: 'test-ui',
        scope: 'openid profile',
        response_type: 'code',
        redirect_uri: BASE,
        post_logout_redirect_uri: BASE,
        automaticSilentRenew: true,
      }),
    );
    expect(created[0]).not.toHaveProperty('client_secret');
    expect(provider.kind).toBe('oidc');
    expect(provider.redirects).toBe(true);
  });

  it('restores an unexpired session of the tab', async () => {
    client.stored = user('stored');
    await provider.init();
    expect(provider.token()).toBe('stored');

    client.stored = user('stale', true);
    await provider.init();
    expect(provider.token()).toBeUndefined();
  });

  it('completes the redirect back from the provider and returns to the page sign-in started on', async () => {
    window.history.replaceState(null, '', '/ui/?code=abc&state=xyz&session_state=s');
    await provider.init();

    expect(client.callbackUrls).toEqual([new URL('/ui/?code=abc&state=xyz&session_state=s', BASE).toString()]);
    expect(provider.token()).toBe('from-callback');
    expect(provider.error()).toBeUndefined();
    expect(window.location.pathname + window.location.search).toBe('/ui/browse/books?x=1');
  });

  it('reports a sign-in the provider refused and clears the response from the address', async () => {
    client.callback = () => Promise.reject(new Error('access_denied'));
    window.history.replaceState(null, '', '/ui/?error=access_denied&error_description=Denied+by+policy&state=xyz');
    await provider.init();

    expect(provider.token()).toBeUndefined();
    expect(provider.error()).toBe('Denied by policy');
    expect(window.location.pathname + window.location.search).toBe('/ui/');
  });

  it('ignores ordinary pages', async () => {
    window.history.replaceState(null, '', '/ui/browse/books?state=draft');
    await provider.init();
    expect(client.callbackUrls).toEqual([]);
    expect(window.location.search).toBe('?state=draft');
  });

  it('signs in from the current page', async () => {
    window.history.replaceState(null, '', '/ui/search?q=owl#top');
    await provider.signIn();
    expect(client.redirects).toEqual([{ state: '/ui/search?q=owl#top' }]);
  });

  it('follows renewed and expired tokens', async () => {
    await provider.init();
    client.renew('renewed');
    expect(provider.token()).toBe('renewed');
    client.expire();
    expect(provider.token()).toBeUndefined();
  });

  it('signs out at the provider and forgets the tokens when it cannot be reached', async () => {
    client.stored = user('stored');
    await provider.init();
    await provider.signOut();
    expect(client.signedOut).toBe(1);

    client.failSignOut = true;
    await provider.signOut();
    expect(client.stored).toBeNull();
    expect(provider.token()).toBeUndefined();
  });
});

describe('returnTarget', () => {
  it('accepts only paths of this origin', () => {
    expect(returnTarget('/ui/browse/a')).toBe('/ui/browse/a');
    expect(returnTarget('//evil.example/x')).toBeUndefined();
    expect(returnTarget('/\\evil.example')).toBeUndefined();
    expect(returnTarget('https://evil.example/')).toBeUndefined();
    expect(returnTarget(undefined)).toBeUndefined();
  });
});

describe('userManagerSettings', () => {
  it('keeps tokens in the session storage of the tab', () => {
    const settings = userManagerSettings(SETTINGS);
    expect(settings.userStore).toBeDefined();
    expect(settings.stateStore).toBeDefined();
    expect(settings.loadUserInfo).toBe(false);
  });
});
