import { TestBed } from '@angular/core/testing';
import { User } from 'oidc-client-ts';

import { TEST_AUTH, provideTestConfig } from '../../testing/test-config';
import { AuthConfig } from '../config.service';
import { AuthService, TOKEN_KEY } from './auth.service';
import { OIDC_CLIENT_FACTORY, OidcClient } from './oidc-auth.provider';

const OIDC: AuthConfig = { ...TEST_AUTH, provider: 'oidc' };

describe('AuthService', () => {
  let redirects: unknown[];
  let authorities: string[];

  const client = (): OidcClient => ({
    getUser: () => Promise.resolve({ access_token: 'jwt', expired: false } as User),
    signinRedirect: (args) => {
      redirects.push(args);
      return Promise.resolve();
    },
    signinRedirectCallback: () => Promise.reject(new Error('unused')),
    signoutRedirect: () => Promise.resolve(),
    removeUser: () => Promise.resolve(),
    events: {
      addUserLoaded: () => undefined,
      addUserUnloaded: () => undefined,
      addAccessTokenExpired: () => undefined,
      addSilentRenewError: () => undefined,
    },
  });

  function setUp(auth: AuthConfig): AuthService {
    redirects = [];
    authorities = [];
    TestBed.configureTestingModule({
      providers: [
        provideTestConfig(auth),
        {
          provide: OIDC_CLIENT_FACTORY,
          useValue: (settings: { authority: string }) => {
            authorities.push(settings.authority);
            return client();
          },
        },
      ],
    });
    return TestBed.inject(AuthService);
  }

  afterEach(() => sessionStorage.removeItem(TOKEN_KEY));

  it('signs in with the OpenID Connect provider the settings name', async () => {
    const auth = setUp(OIDC);
    await auth.init();

    expect(auth.kind()).toBe('oidc');
    expect(auth.redirects()).toBe(true);
    expect(authorities).toEqual(['http://host/auth/realms/test']);
    expect(auth.token()).toBe('jwt');
    expect(auth.signedIn()).toBe(true);

    await auth.signIn();
    expect(redirects).toHaveLength(1);
  });

  it('asks for a token with the token provider', async () => {
    const auth = setUp(TEST_AUTH);
    await auth.init();

    expect(auth.kind()).toBe('token');
    expect(auth.redirects()).toBe(false);
    await auth.signIn(' consul-secret ');
    expect(auth.token()).toBe('consul-secret');
    await auth.forget();
    expect(auth.signedIn()).toBe(false);
    expect(authorities).toEqual([]);
  });

  it('offers no sign-in with none', async () => {
    sessionStorage.setItem(TOKEN_KEY, 'left-over');
    const auth = setUp({ ...TEST_AUTH, provider: 'none' });
    await auth.init();

    expect(auth.kind()).toBe('none');
    await auth.signIn('ignored');
    expect(auth.token()).toBeUndefined();
  });
});
