import { ChildKind } from '../core/ldp/models';

/** Default titles the templates propose, in the user interface's language. */
export interface TemplateTitles {
  readonly untitled: string;
  readonly members: string;
  readonly proxies: string;
}

/** The English default titles. */
export const DEFAULT_TITLES: TemplateTitles = { untitled: 'Untitled', members: 'Members', proxies: 'Proxies' };

function quoted(value: string): string {
  return `"${value.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
}

/**
 * Starting Turtle for each kind of child container. Direct and indirect containers need their
 * membership configuration; the templates use the parent as membership resource and PCDM
 * relations, which are the usual choices in Fedora repositories.
 *
 * @param kind - the kind of child
 * @param parentIri - the IRI of the parent container
 * @param titles - the proposed titles
 * @returns the Turtle body (empty for binaries)
 */
export function childTemplate(kind: ChildKind, parentIri: string, titles: TemplateTitles = DEFAULT_TITLES): string {
  const title = `<> dcterms:title ${quoted(titles.untitled)} .`;
  const prefixes = '@prefix dcterms: <http://purl.org/dc/terms/> .\n';
  switch (kind) {
    case 'basic':
      return `${prefixes}\n${title}\n`;
    case 'direct':
      return (
        `${prefixes}@prefix ldp: <http://www.w3.org/ns/ldp#> .\n@prefix pcdm: <http://pcdm.org/models#> .\n\n` +
        `<> ldp:membershipResource <${parentIri}> ;\n   ldp:hasMemberRelation pcdm:hasMember ;\n   dcterms:title ${quoted(titles.members)} .\n`
      );
    case 'indirect':
      return (
        `${prefixes}@prefix ldp: <http://www.w3.org/ns/ldp#> .\n@prefix pcdm: <http://pcdm.org/models#> .\n` +
        `@prefix ore: <http://www.openarchives.org/ore/terms/> .\n\n` +
        `<> ldp:membershipResource <${parentIri}> ;\n   ldp:hasMemberRelation pcdm:hasMember ;\n` +
        `   ldp:insertedContentRelation ore:proxyFor ;\n   dcterms:title ${quoted(titles.proxies)} .\n`
      );
    case 'binary':
      return '';
  }
}

/**
 * Tells whether a body is one of the templates (or empty), so switching kinds may replace it
 * without losing the user's edits.
 *
 * @param body - the current body
 * @param parentIri - the parent IRI used by the templates
 * @param titles - the proposed titles
 * @returns `true` when the body is a template or blank
 */
export function isTemplate(body: string, parentIri: string, titles: TemplateTitles = DEFAULT_TITLES): boolean {
  return (
    body.trim() === '' ||
    (['basic', 'direct', 'indirect'] as const).some((kind) => childTemplate(kind, parentIri, titles) === body)
  );
}
