import { UrlSegment } from '@angular/router';

import { viewMatcher } from './app.routes';

const segments = (...paths: string[]): UrlSegment[] => paths.map((path) => new UrlSegment(path, {}));

describe('viewMatcher', () => {
  it('captures any number of path segments', () => {
    const match = viewMatcher('browse')(segments('browse', 'a', 'b c'), {} as never, {} as never);

    expect(match?.posParams?.['path'].path).toBe('a/b c');
    expect(viewMatcher('browse')(segments('browse'), {} as never, {} as never)?.posParams?.['path'].path).toBe('');
  });

  it('captures fixed parameters before the path', () => {
    const match = viewMatcher('memento', 'version')(segments('memento', 'v2', 'a'), {} as never, {} as never);

    expect(match?.posParams?.['version'].path).toBe('v2');
    expect(match?.posParams?.['path'].path).toBe('a');
    expect(viewMatcher('memento', 'version')(segments('memento'), {} as never, {} as never)).toBeNull();
  });

  it('ignores other views', () => {
    expect(viewMatcher('browse')(segments('search'), {} as never, {} as never)).toBeNull();
  });
});
