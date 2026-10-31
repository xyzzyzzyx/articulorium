import { ACL } from '../rdf/vocabulary';
import { Authorization, aclTurtle, allows, granteeLabel, parseWacAllow, toDrafts } from './webac';

describe('webac', () => {
  it('parses WAC-Allow headers', () => {
    const allow = parseWacAllow('user="read write append",public="read"');
    expect([...(allow?.user ?? [])]).toEqual(['read', 'write', 'append']);
    expect([...(allow?.public ?? [])]).toEqual(['read']);
    expect(parseWacAllow(null)).toBeUndefined();
    expect([...(parseWacAllow('user="",public="bogus read"')?.public ?? [])]).toEqual(['read']);
  });

  it('decides with unknown modes as allowed and write implying append', () => {
    expect(allows(undefined, 'write')).toBe(true);
    expect(allows(parseWacAllow('user="write"'), 'append')).toBe(true);
    expect(allows(parseWacAllow('user="read"'), 'write')).toBe(false);
  });

  it('turns platform authorizations into drafts', () => {
    const authorization: Authorization = {
      id: '/a/fcr:acl#x',
      agents: ['https://example.org/alice'],
      agentClasses: [ACL.foafAgent, ACL.authenticatedAgent],
      agentGroups: ['info:consul/role/editors'],
      modes: [`${ACL.ns}Read`, `${ACL.ns}Write`],
      accessTo: ['/a'],
      defaults: ['/a'],
      accessToClass: [],
    };
    const drafts = toDrafts(authorization);
    expect(drafts.map((draft) => draft.kind)).toEqual(['everyone', 'authenticated', 'agent', 'group']);
    expect(drafts[0]).toEqual({ kind: 'everyone', modes: ['read', 'write'], inherit: true });
    expect(granteeLabel(drafts[3])).toBe('Group info:consul/role/editors');
  });

  it('writes Turtle access control lists', () => {
    const turtle = aclTurtle('http://h/platform/a', [
      { kind: 'everyone', modes: ['read'], inherit: true },
      { kind: 'agent', iri: 'https://example.org/a b', modes: ['read', 'control'], inherit: false },
      { kind: 'group', iri: 'info:consul/role/x', modes: [], inherit: false },
    ]);
    expect(turtle).toContain('<#authorization-1> a acl:Authorization ;');
    expect(turtle).toContain('acl:agentClass foaf:Agent ;');
    expect(turtle).toContain('acl:default <http://h/platform/a> ;');
    expect(turtle).toContain('acl:agent <https://example.org/a%20b> ;');
    expect(turtle).toContain('acl:mode acl:Read, acl:Control .');
    expect(turtle).not.toContain('authorization-3');
  });
});
