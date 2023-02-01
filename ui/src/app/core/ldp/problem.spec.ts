import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';

import { errorMessage, sameLanguage } from './problem';

describe('errorMessage', () => {
  it('prefers the problem detail', () => {
    const error = new HttpErrorResponse({ status: 409, error: { title: 'Conflict', detail: 'ldp:contains is server managed' } });

    expect(errorMessage(error)).toBe('ldp:contains is server managed');
  });

  it('parses problem documents received as text', () => {
    const error = new HttpErrorResponse({ status: 400, error: '{"title":"Bad Request","detail":"Invalid RDF"}' });

    expect(errorMessage(error)).toBe('Invalid RDF');
  });

  it('falls back to text, status or the error message', () => {
    expect(errorMessage(new HttpErrorResponse({ status: 500, error: 'boom' }))).toBe('boom');
    expect(errorMessage(new HttpErrorResponse({ status: 0 }))).toBe('The platform cannot be reached');
    expect(errorMessage(new HttpErrorResponse({ status: 404, statusText: 'Not Found' }))).toBe('404 Not Found');
    expect(errorMessage(new Error('plain'))).toBe('plain');
    expect(errorMessage(new Error('wrapped', { cause: new HttpErrorResponse({ status: 410, error: { detail: 'gone' } }) }))).toBe('gone');
  });
});

describe('errorMessage with a locale', () => {
  const texts: Record<string, string> = {
    'error.status.404': 'Nichts gefunden.',
    'error.status.other': 'Fehlgeschlagen.',
    'error.unreachable': 'Nicht erreichbar.',
  };
  const locale = { t: (key: string) => texts[key] ?? key, locale: 'de' };
  const headers = (language: string) => new HttpHeaders({ 'Content-Language': language });

  it('keeps messages written in the user interface language', () => {
    const error = new HttpErrorResponse({ status: 404, error: 'Ressource /x nicht gefunden', headers: headers('de') });
    expect(errorMessage(error, locale)).toBe('Ressource /x nicht gefunden');
    expect(errorMessage(new HttpErrorResponse({ status: 404, error: 'kein Header' }), locale)).toBe('kein Header');
  });

  it('summarises messages in another language', () => {
    const plain = new HttpErrorResponse({ status: 404, error: 'Resource /x was not found', headers: headers('en') });
    expect(errorMessage(plain, locale)).toBe('Nichts gefunden. (Resource /x was not found)');
    const problem = new HttpErrorResponse({
      status: 400,
      error: { title: 'Ungültige Anfrage', detail: 'name is required' },
      headers: headers('en'),
    });
    expect(errorMessage(problem, locale)).toBe('Ungültige Anfrage (name is required)');
    expect(errorMessage(new HttpErrorResponse({ status: 418, error: 'teapot', headers: headers('en') }), locale)).toBe(
      'Fehlgeschlagen. (teapot)',
    );
  });

  it('translates statuses without a message', () => {
    expect(errorMessage(new HttpErrorResponse({ status: 0 }), locale)).toBe('Nicht erreichbar.');
    expect(errorMessage(new HttpErrorResponse({ status: 404 }), locale)).toBe('Nichts gefunden.');
  });

  it('compares primary languages', () => {
    expect(sameLanguage('pt-BR', 'pt-PT')).toBe(true);
    expect(sameLanguage('en', 'de')).toBe(false);
    expect(sameLanguage(null, 'de')).toBe(true);
  });
});
