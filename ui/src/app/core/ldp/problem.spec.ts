import { HttpErrorResponse } from '@angular/common/http';

import { errorMessage } from './problem';

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
