import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { User } from 'oidc-client-ts';

import { TEST_AUTH, provideTestConfig } from './testing/test-config';
import { AppComponent } from './app.component';
import { AuthConfig } from './core/config.service';
import { AuthService } from './core/auth/auth.service';
import { OIDC_CLIENT_FACTORY, OidcClient } from './core/auth/oidc-auth.provider';

describe('AppComponent', () => {
  let redirects: number;

  const client: OidcClient = {
    getUser: () => Promise.resolve(null as User | null),
    signinRedirect: () => {
      redirects++;
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
  };

  async function setUp(auth: AuthConfig = TEST_AUTH): Promise<void> {
    redirects = 0;
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideHttpClient(),
        provideRouter([]),
        provideTestConfig(auth),
        { provide: OIDC_CLIENT_FACTORY, useValue: () => client },
      ],
    }).compileComponents();
    await TestBed.inject(AuthService).init();
  }

  beforeEach(() => (redirects = 0));

  it('shows the configured title and the navigation', async () => {
    await setUp();
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelector('.ui-brand')?.textContent).toContain('Test repository');
    const links = [...element.querySelectorAll('.ui-nav a')].map((a) => a.getAttribute('href'));
    expect(links).toEqual(['/browse', '/communities', '/topologies', '/search', 'http://host/swagger-ui.html', '/about']);
    expect(element.querySelector('.ui-account')?.textContent).toContain('Sign in');
  });

  it('leaves for the identity provider to sign in with OpenID Connect', async () => {
    await setUp({ ...TEST_AUTH, provider: 'oidc' });
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const button = [...(fixture.nativeElement as HTMLElement).querySelectorAll('.ui-account button')].find((b) =>
      b.textContent?.includes('Sign in'),
    ) as HTMLButtonElement;

    button.click();
    expect(redirects).toBe(1);
  });

  it('offers no sign-in without a provider', async () => {
    await setUp({ ...TEST_AUTH, provider: 'none' });
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.ui-account')?.textContent).not.toContain('Sign in');
  });
});
