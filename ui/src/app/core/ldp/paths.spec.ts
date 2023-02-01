import { childPath, normalizePath, parentPath, pathOf, platformUrl, routeTo, segmentsOf } from './paths';

const BASE = 'http://host/platform/';

describe('paths', () => {
  it('normalises and splits paths', () => {
    expect(normalizePath('/a//b/')).toBe('a/b');
    expect(normalizePath(undefined)).toBe('');
    expect(segmentsOf('a/b c')).toEqual(['a', 'b c']);
  });

  it('navigates up and down', () => {
    expect(parentPath('a/b')).toBe('a');
    expect(parentPath('a')).toBe('');
    expect(parentPath('')).toBeUndefined();
    expect(childPath('', 'x')).toBe('x');
    expect(childPath('a', 'x/')).toBe('a/x');
  });

  it('builds platform URLs with encoded segments', () => {
    expect(platformUrl(BASE, '')).toBe(BASE);
    expect(platformUrl(BASE, 'books/moby dick')).toBe(`${BASE}books/moby%20dick`);
    expect(platformUrl(BASE, 'a', 'fcr:versions', 'v1')).toBe(`${BASE}a/fcr:versions/v1`);
  });

  it('maps repository IRIs back to paths', () => {
    expect(pathOf(BASE, 'http://host/platform')).toBe('');
    expect(pathOf(BASE, `${BASE}books/moby%20dick`)).toBe('books/moby dick');
    expect(pathOf(BASE, `${BASE}a#frag`)).toBe('a');
    expect(pathOf(BASE, 'http://host/platformx/a')).toBeUndefined();
    expect(pathOf(BASE, 'http://elsewhere/a')).toBeUndefined();
  });

  it('builds route commands per segment', () => {
    expect(routeTo('browse', 'a/b')).toEqual(['/', 'browse', 'a', 'b']);
    expect(routeTo('browse', '')).toEqual(['/', 'browse']);
  });
});
