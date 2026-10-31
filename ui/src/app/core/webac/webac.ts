import { ACL } from '../rdf/vocabulary';

/** Access modes as the WebAC endpoint and `WAC-Allow` name them. */
export type AccessMode = 'read' | 'write' | 'append' | 'control';

/** Every access mode, in display order. */
export const ACCESS_MODES: readonly AccessMode[] = ['read', 'write', 'append', 'control'];

/** One authorization of an access control list (IRIs as the platform reports them). */
export interface Authorization {
  readonly id: string;
  readonly agents: readonly string[];
  readonly agentClasses: readonly string[];
  readonly agentGroups: readonly string[];
  readonly modes: readonly string[];
  readonly accessTo: readonly string[];
  readonly defaults: readonly string[];
  readonly accessToClass: readonly string[];
}

/** The requesting agent and its access to a resource (`/.well-known/webac`). */
export interface AccessInfo {
  readonly agent: { readonly webId?: string | null; readonly groups: readonly string[]; readonly authenticated: boolean };
  readonly enforced: boolean;
  readonly resource: string;
  readonly aclResource?: string | null;
  readonly inherited: boolean;
  readonly userModes: readonly AccessMode[];
  readonly publicModes: readonly AccessMode[];
  readonly authorizations: readonly Authorization[];
}

/** Modes granted to the user and to everyone, from a `WAC-Allow` header. */
export interface WacAllow {
  readonly user: ReadonlySet<AccessMode>;
  readonly public: ReadonlySet<AccessMode>;
}

/** Who an authorization is for, as the permissions form offers it. */
export type GranteeKind = 'everyone' | 'authenticated' | 'agent' | 'group';

/** An authorization as edited in the user interface. */
export interface AuthorizationDraft {
  readonly kind: GranteeKind;
  /** The agent or group IRI (for `agent` and `group`). */
  readonly iri?: string;
  readonly modes: readonly AccessMode[];
  /** Whether descendants inherit the authorization (`acl:default`). */
  readonly inherit: boolean;
}

/**
 * Parses a `WAC-Allow` header such as `user="read write",public="read"`.
 *
 * @param header - the header value
 * @returns the modes, or `undefined` without a header
 */
export function parseWacAllow(header: string | null | undefined): WacAllow | undefined {
  if (!header) {
    return undefined;
  }
  const groups: Record<string, Set<AccessMode>> = { user: new Set(), public: new Set() };
  for (const match of header.matchAll(/(\w+)\s*=\s*"([^"]*)"/g)) {
    const modes = match[2]
      .split(/\s+/)
      .filter((mode): mode is AccessMode => (ACCESS_MODES as readonly string[]).includes(mode));
    groups[match[1].toLowerCase()] = new Set(modes);
  }
  return { user: groups['user'] ?? new Set(), public: groups['public'] ?? new Set() };
}

/**
 * Tells whether modes allow changing a resource (`write`, or `append` for creating children).
 *
 * @param allow - the parsed header, `undefined` when access control did not report modes
 * @param mode - the mode needed
 * @returns `true` when allowed or unknown
 */
export function allows(allow: WacAllow | undefined, mode: AccessMode): boolean {
  if (!allow) {
    return true;
  }
  return allow.user.has(mode) || (mode === 'append' && allow.user.has('write'));
}

/**
 * Turns an authorization reported by the platform into an editable draft, for a resource that
 * takes over (copies) inherited authorizations.
 *
 * @param authorization - the authorization
 * @returns the drafts it amounts to (one per grantee)
 */
export function toDrafts(authorization: Authorization): AuthorizationDraft[] {
  const modes = authorization.modes
    .map((mode) => mode.substring(ACL.ns.length).toLowerCase())
    .filter((mode): mode is AccessMode => (ACCESS_MODES as readonly string[]).includes(mode));
  const inherit = authorization.defaults.length > 0;
  const drafts: AuthorizationDraft[] = [];
  for (const agentClass of authorization.agentClasses) {
    if (agentClass === ACL.foafAgent) {
      drafts.push({ kind: 'everyone', modes, inherit });
    } else if (agentClass === ACL.authenticatedAgent) {
      drafts.push({ kind: 'authenticated', modes, inherit });
    }
  }
  authorization.agents.forEach((iri) => drafts.push({ kind: 'agent', iri, modes, inherit }));
  authorization.agentGroups.forEach((iri) => drafts.push({ kind: 'group', iri, modes, inherit }));
  return drafts;
}

/**
 * Builds the Turtle of an access control list for a resource.
 *
 * @param resourceIri - the resource the ACL belongs to
 * @param drafts - the authorizations
 * @returns the Turtle document
 */
export function aclTurtle(resourceIri: string, drafts: readonly AuthorizationDraft[]): string {
  const lines = [`@prefix acl: <${ACL.ns}> .`, `@prefix foaf: <http://xmlns.com/foaf/0.1/> .`, ''];
  drafts.forEach((draft, index) => {
    if (draft.modes.length === 0) {
      return;
    }
    const grantee =
      draft.kind === 'everyone'
        ? 'acl:agentClass foaf:Agent'
        : draft.kind === 'authenticated'
          ? 'acl:agentClass acl:AuthenticatedAgent'
          : `${draft.kind === 'agent' ? 'acl:agent' : 'acl:agentGroup'} <${escapeIri(draft.iri ?? '')}>`;
    const modes = draft.modes.map((mode) => `acl:${mode.charAt(0).toUpperCase()}${mode.substring(1)}`).join(', ');
    lines.push(`<#authorization-${index + 1}> a acl:Authorization ;`);
    lines.push(`    ${grantee} ;`);
    lines.push(`    acl:accessTo <${escapeIri(resourceIri)}> ;`);
    if (draft.inherit) {
      lines.push(`    acl:default <${escapeIri(resourceIri)}> ;`);
    }
    lines.push(`    acl:mode ${modes} .`, '');
  });
  return lines.join('\n');
}

/**
 * Describes who an authorization draft is for.
 *
 * @param draft - the draft
 * @returns for example `Everyone` or the agent IRI
 */
export function granteeLabel(draft: AuthorizationDraft): string {
  switch (draft.kind) {
    case 'everyone':
      return 'Everyone';
    case 'authenticated':
      return 'Authenticated agents';
    case 'agent':
      return `Agent ${draft.iri ?? ''}`;
    case 'group':
      return `Group ${draft.iri ?? ''}`;
  }
}

function escapeIri(iri: string): string {
  return iri.replace(/[<>"{}|^`\\\s]/g, (c) => `%${c.charCodeAt(0).toString(16).toUpperCase().padStart(2, '0')}`);
}
