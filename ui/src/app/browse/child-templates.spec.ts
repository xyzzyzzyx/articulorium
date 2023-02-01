import { childTemplate, isTemplate } from './child-templates';

describe('child templates', () => {
  const parent = 'http://h/p/books';

  it('configures membership for direct and indirect containers', () => {
    expect(childTemplate('basic', parent)).toContain('dcterms:title');
    expect(childTemplate('direct', parent)).toContain(`ldp:membershipResource <${parent}>`);
    expect(childTemplate('indirect', parent)).toContain('ldp:insertedContentRelation ore:proxyFor');
    expect(childTemplate('binary', parent)).toBe('');
  });

  it('recognises untouched templates', () => {
    expect(isTemplate('', parent)).toBe(true);
    expect(isTemplate(childTemplate('direct', parent), parent)).toBe(true);
    expect(isTemplate('<> a <urn:x> .', parent)).toBe(false);
  });
});
