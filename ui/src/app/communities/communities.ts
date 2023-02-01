import { Graph } from '../core/rdf/graph';
import { SubjectTerm } from '../core/rdf/term';
import { ART, DCTERMS, PCDM } from '../core/rdf/vocabulary';

/** What a resource is in the community structure. */
export type StructureKind = 'root' | 'community' | 'collection' | 'item' | 'other';

/**
 * Classifies a resource of the community structure by its types.
 *
 * @param graph - its representation
 * @param subject - the resource
 * @returns the kind
 */
export function structureKind(graph: Graph, subject: SubjectTerm): StructureKind {
  if (graph.hasType(subject, ART.communityRoot)) {
    return 'root';
  }
  if (graph.hasType(subject, ART.community)) {
    return 'community';
  }
  if (graph.hasType(subject, ART.collection) || graph.hasType(subject, PCDM.collection)) {
    return 'collection';
  }
  if (graph.hasType(subject, PCDM.object)) {
    return 'item';
  }
  return 'other';
}

/**
 * Builds the Turtle of a new community or collection.
 *
 * @param kind - `community` (also sub-communities) or `collection`
 * @param title - its title
 * @param description - its description (optional)
 * @returns the Turtle (`<>` is the new resource)
 */
export function structureTurtle(kind: 'community' | 'collection', title: string, description?: string): string {
  const types = kind === 'community' ? [`<${ART.community}>`] : [`<${ART.collection}>`, `<${PCDM.collection}>`];
  const lines = [`<> a ${types.join(', ')} ;`, `   <${DCTERMS.title}> ${literal(title)}`];
  if (description?.trim()) {
    lines[lines.length - 1] += ' ;';
    lines.push(`   <${DCTERMS.description}> ${literal(description.trim())}`);
  }
  return `${lines.join('\n')} .\n`;
}

/**
 * Derives a resource name from a title.
 *
 * @param title - the title
 * @returns a lower-case slug, for example `rare-books` for `Rare Books`
 */
export function slugOf(title: string): string {
  return title
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .substring(0, 80);
}

function literal(value: string): string {
  return `"${value.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n').replace(/\r/g, '\\r')}"`;
}
