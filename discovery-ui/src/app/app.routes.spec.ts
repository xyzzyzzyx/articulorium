import { UrlSegment } from '@angular/router';

import { itemMatcher } from './app.routes';

const segments = (...paths: string[]) => paths.map((path) => new UrlSegment(path, {}));

describe('itemMatcher', () => {
  it('matches resource paths of any depth', () => {
    const match = itemMatcher(segments('items', 'communities', 'c', 'moby'));
    expect(match?.posParams?.['path'].path).toBe('communities/c/moby');
    expect(match?.consumed).toHaveLength(4);
  });

  it('matches nothing else', () => {
    expect(itemMatcher(segments('items'))).toBeNull();
    expect(itemMatcher(segments('search'))).toBeNull();
  });
});
