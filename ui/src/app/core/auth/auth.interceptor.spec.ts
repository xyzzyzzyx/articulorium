import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { TEST_BASE, TEST_TOPOLOGY, TEST_TRANSFORM, provideTestConfig } from '../../testing/test-config';
import { authInterceptor } from './auth.interceptor';
import { AuthService, TOKEN_KEY } from './auth.service';

describe('authInterceptor', () => {
  let http: HttpTestingController;
  let client: HttpClient;
  let auth: AuthService;

  beforeEach(() => {
    sessionStorage.removeItem(TOKEN_KEY);
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting(), provideTestConfig()],
    });
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => {
    http.verify();
    sessionStorage.removeItem(TOKEN_KEY);
  });

  it('sends nothing while signed out', () => {
    client.get(`${TEST_BASE}books`).subscribe();
    expect(http.expectOne(`${TEST_BASE}books`).request.headers.has('Authorization')).toBe(false);
  });

  it('sends the token to the repository services only', () => {
    auth.signIn(' secret ');
    expect(sessionStorage.getItem(TOKEN_KEY)).toBe('secret');

    for (const url of [`${TEST_BASE}books`, `${TEST_TOPOLOGY}topologies`, `${TEST_TRANSFORM}iiif/x`]) {
      client.get(url).subscribe();
      expect(http.expectOne(url).request.headers.get('Authorization')).toBe('Bearer secret');
    }
    client.get('https://elsewhere.example/data').subscribe();
    expect(http.expectOne('https://elsewhere.example/data').request.headers.has('Authorization')).toBe(false);
  });

  it('forgets the token on sign out or an empty token', () => {
    auth.signIn('secret');
    auth.signOut();
    expect(auth.signedIn()).toBe(false);
    expect(sessionStorage.getItem(TOKEN_KEY)).toBeNull();
    auth.signIn('   ');
    expect(auth.token()).toBeUndefined();
  });
});
