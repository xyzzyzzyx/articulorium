import { Graph } from '../core/rdf/graph';
import { parseNTriples } from '../core/rdf/ntriples';
import { ART, PCDM, RDF } from '../core/rdf/vocabulary';
import { slugOf, structureKind, structureTurtle } from './communities';

const typed = (type: string): Graph => new Graph(parseNTriples(`<http://h/x> <${RDF.type}> <${type}> .`));

describe('communities', () => {
  it('classifies resources of the structure', () => {
    const subject = { kind: 'iri', value: 'http://h/x' } as const;
    expect(structureKind(typed(ART.communityRoot), subject)).toBe('root');
    expect(structureKind(typed(ART.community), subject)).toBe('community');
    expect(structureKind(typed(PCDM.collection), subject)).toBe('collection');
    expect(structureKind(typed(PCDM.object), subject)).toBe('item');
    expect(structureKind(typed('urn:other'), subject)).toBe('other');
  });

  it('writes communities and collections', () => {
    expect(structureTurtle('community', 'Libraries')).toBe(
      `<> a <${ART.community}> ;\n   <http://purl.org/dc/terms/title> "Libraries" .\n`,
    );
    const collection = structureTurtle('collection', 'Maps "old"', ' Historic maps ');
    expect(collection).toContain(`<${ART.collection}>, <${PCDM.collection}>`);
    expect(collection).toContain('"Maps \\"old\\"" ;');
    expect(collection).toContain('<http://purl.org/dc/terms/description> "Historic maps" .');
  });

  it('derives names from titles', () => {
    expect(slugOf('Rare Books & Manuscripts')).toBe('rare-books-manuscripts');
    expect(slugOf('Café Société')).toBe('cafe-societe');
    expect(slugOf('***')).toBe('');
  });
});
