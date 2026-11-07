import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { User } from 'oidc-client-ts';

import { TEST_AUTH, provideTestConfig } from './testing/test-config';
import { AppComponent } from './app.component';
import { AuthConfig } from './core/config.service';
import { AuthService } from './core/auth/auth.service';
import { OIDC_CLIENT_FACTORY, OidcClient } from './core/auth/oidc-auth.provider';
import { provideTestI18n } from './testing/test-i18n';
import { I18nService } from './core/i18n/i18n.service';

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
        ...provideTestI18n(),
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
    expect(links).toEqual(['/browse', '/communities', '/profiles', '/topologies', '/harvest', '/search', 'http://host/swagger-ui.html', '/about']);
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

  it('renders in the locale the server chose and offers the enabled languages', async () => {
    const script = document.createElement('script');
    script.id = 'ui-i18n';
    script.type = 'application/json';
    script.textContent = JSON.stringify({
      locale: 'de',
      direction: 'ltr',
      defaultLocale: 'en',
      locales: [
        { tag: 'en', name: 'English', direction: 'ltr' },
        { tag: 'de', name: 'Deutsch', direction: 'ltr' },
        { tag: 'ar', name: 'العربية', direction: 'rtl' },
      ],
      messages: { nav: { browse: 'Durchsuchen' }, auth: { signIn: 'Anmelden' } },
    });
    document.head.appendChild(script);
    try {
      await setUp();
      await TestBed.inject(I18nService).init();
      const fixture = TestBed.createComponent(AppComponent);
      fixture.detectChanges();
      const element = fixture.nativeElement as HTMLElement;

      expect(document.documentElement.lang).toBe('de');
      expect(element.querySelector('.ui-nav')?.textContent).toContain('Durchsuchen');
      // texts the translation lacks fall back to English
      expect(element.querySelector('.ui-nav')?.textContent).toContain('Communities');
      expect(element.querySelector('.ui-account')?.textContent).toContain('Anmelden');
      const language = element.querySelector('.ui-language');
      expect(language?.textContent).toContain('Deutsch');
      expect(language?.getAttribute('aria-label')).toBe('Language: Deutsch');
    } finally {
      script.remove();
      document.documentElement.lang = 'en';
    }
  });
});
