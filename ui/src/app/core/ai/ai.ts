import { HttpErrorResponse } from '@angular/common/http';

import { Translate } from '../ldp/problem';
import { sparqlIri, sparqlLiteral } from '../rdf/sparql';
import { Profile, normalizeProfile } from '../profile/profile';
import { Topology, TopologySummary } from '../topology/topology';

/** The capabilities of the AI agent, by key. */
export type AiCapability = 'topology' | 'media' | 'topics' | 'profiles' | 'classification';

/** What the AI agent offers now (`GET ai/capabilities`). */
export interface AiCapabilities {
  /** Whether the agent is turned on. */
  readonly enabled: boolean;
  /** Whether a chat model is configured. */
  readonly model: boolean;
  /** The provider and model, for example `ollama:qwen2.5vl:3b`. */
  readonly modelName?: string | null;
  /** Each capability: whether it can be used now. */
  readonly capabilities: Readonly<Partial<Record<AiCapability, boolean>>>;
}

/** What the user interface assumes when the agent cannot be reached: nothing is offered. */
export const NO_AI: AiCapabilities = { enabled: false, model: false, capabilities: {} };

/** A suggested statement about a resource. */
export interface MetadataStatement {
  readonly predicate: string;
  readonly value: string;
  readonly kind: 'LITERAL' | 'IRI';
  readonly language?: string | null;
  readonly datatype?: string | null;
  /** Read from the file, or suggested by the model. */
  readonly origin: 'FILE' | 'MODEL';
}

/** Suggested metadata of a binary (`POST ai/media-metadata`). */
export interface MediaMetadataSuggestion {
  readonly path: string;
  /** The path of the binary's description, where the statements go. */
  readonly description: string;
  readonly mediaType: string;
  readonly kind: 'IMAGE' | 'VIDEO' | 'PDF';
  /** Whether the model saw the image itself. */
  readonly imageShown: boolean;
  readonly statements: readonly MetadataStatement[];
  /** Codes of what limited the suggestion (`IMAGE_FORMAT_NOT_SHOWN`, `TEXT_SHORTENED` ...). */
  readonly notes: readonly string[];
}

/** An item of a topic. */
export interface TopicMember {
  readonly path: string;
  readonly title: string;
}

/** A suggested topic of a community or collection. */
export interface Topic {
  readonly label: string;
  readonly keywords: readonly string[];
  readonly description: string;
  readonly members: readonly TopicMember[];
  /** Share of the items analysed that are about it, 0 to 1. */
  readonly share: number;
}

/** Suggested topics and summary of a community or collection (`POST ai/topic-models`). */
export interface TopicModel {
  readonly path: string;
  readonly summary: string;
  readonly topics: readonly Topic[];
  /** How many items the model saw. */
  readonly analysed: number;
  /** Whether there were more items than were read. */
  readonly truncated: boolean;
  /** Language of the labels and summary (BCP 47). */
  readonly language?: string | null;
}

/** Suggestions for an academic profile (`POST ai/profile-suggestions`). */
export interface ProfileSuggestion {
  readonly path: string;
  /** A suggested overview, empty when the model has none. */
  readonly overview: string;
  /** Whether the profile has an overview already (keeping the suggestion replaces it). */
  readonly hasOverview: boolean;
  /** Research areas the profile does not have. */
  readonly researchAreas: readonly string[];
  /** Keywords the profile does not have. */
  readonly keywords: readonly string[];
  /** What the suggestions were drawn from. */
  readonly basedOn: { readonly positions: number; readonly education: number; readonly publications: number };
  /** Language of the suggestions (BCP 47). */
  readonly language?: string | null;
}

/** What the user keeps of a profile suggestion. */
export interface KeptProfileSuggestion {
  /** The overview, when kept. */
  readonly overview?: string;
  readonly researchAreas: readonly string[];
  readonly keywords: readonly string[];
}

/**
 * Tells whether a profile suggestion had anything to draw from.
 *
 * @param suggestion - the suggestion
 * @returns `false` when the profile records no position, degree or publication
 */
export function suggestionHasMaterial(suggestion: ProfileSuggestion): boolean {
  const based = suggestion.basedOn;
  return based.positions + based.education + based.publications > 0;
}

/**
 * Merges what the user keeps into a profile, as the profile editor would save it: the overview is
 * replaced, research areas and keywords are added (repeats dropped, sorted).
 *
 * @param profile - the profile as read
 * @param kept - what the user keeps
 * @returns the profile to save
 */
export function mergeProfileSuggestion(profile: Profile, kept: KeptProfileSuggestion): Profile {
  return normalizeProfile({
    ...profile,
    overview: kept.overview?.trim() ? kept.overview.trim() : profile.overview,
    researchAreas: [...profile.researchAreas, ...kept.researchAreas],
    keywords: [...profile.keywords, ...kept.keywords],
  });
}

/** What to suggest a topology for. */
export interface TopologySuggestionRequest {
  /** The material, in the user's words. */
  readonly description: string;
  /** A container holding an example of the material. */
  readonly sample?: string | null;
  /** The container the user works in (a collection). */
  readonly context?: string | null;
}

/** A suggested topology (`POST ai/topology-suggestions`). */
export interface TopologySuggestion {
  readonly recommendation: 'EXISTING' | 'NEW';
  readonly existing?: TopologySummary | null;
  /** The draft of a new topology, as the agent wrote it (lists and flags may be missing). */
  readonly draft?: PartialTopology | null;
  /** What saving the draft would still refuse. */
  readonly errors: readonly string[];
  readonly rationale?: string | null;
  readonly confidence: number;
}

/** A topology node as a model may write it. */
export interface PartialNode {
  readonly key?: string | null;
  readonly label?: string | null;
  readonly pcdmType?: string | null;
  readonly types?: readonly string[] | null;
  readonly metadata?: readonly {
    readonly predicate?: string | null;
    readonly label?: string | null;
    readonly required?: boolean | null;
    readonly repeatable?: boolean | null;
    readonly defaultValue?: string | null;
    readonly datatype?: string | null;
  }[] | null;
  readonly files?: readonly {
    readonly use?: string | null;
    readonly label?: string | null;
    readonly mediaTypes?: readonly string[] | null;
  }[] | null;
  readonly children?: readonly {
    readonly min?: number | null;
    readonly max?: number | null;
    readonly initial?: number | null;
    readonly slugPattern?: string | null;
    readonly node?: PartialNode | null;
  }[] | null;
}

/** A topology as a model may write it. */
export interface PartialTopology {
  readonly id?: string | null;
  readonly name?: string | null;
  readonly description?: string | null;
  readonly builtIn?: boolean | null;
  readonly root?: PartialNode | null;
}

/** Notes of media suggestions the user interface explains. */
export const MEDIA_NOTES: readonly string[] = [
  'IMAGE_FORMAT_NOT_SHOWN',
  'IMAGE_TOO_LARGE_TO_SHOW',
  'IMAGE_NOT_SHOWN',
  'VIDEO_NOT_SHOWN',
  'FILE_NOT_READ',
  'TEXT_FROM_FIRST_PAGES',
  'TEXT_SHORTENED',
  'NO_TEXT',
  'TEXT_NOT_EXTRACTABLE',
  'ENCRYPTED',
  'UNREADABLE_METADATA',
];

/** Reasons of the agent's problem documents the user interface explains. */
export const AI_REASONS: readonly string[] = [
  'INVALID_REQUEST',
  'UNAUTHENTICATED',
  'FORBIDDEN',
  'NOT_FOUND',
  'TOO_LARGE',
  'UNSUPPORTED',
  'BUSY',
  'MODEL_ERROR',
  'INVALID_OUTPUT',
  'DISABLED',
  'CAPABILITY_DISABLED',
  'MODEL_UNAVAILABLE',
  'PLATFORM_UNAVAILABLE',
];

/**
 * Writes a statement's object for SPARQL: an IRI, or a literal with its language or datatype.
 *
 * @param statement - the statement
 * @returns the SPARQL term
 */
export function sparqlObject(statement: MetadataStatement): string {
  if (statement.kind === 'IRI') {
    return sparqlIri(statement.value);
  }
  if (statement.language && /^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$/.test(statement.language)) {
    return `${sparqlLiteral(statement.value)}@${statement.language}`;
  }
  return sparqlLiteral(statement.value, statement.datatype);
}

/**
 * Builds the update adding statements to a resource.
 *
 * @param subject - the resource's IRI (for a binary: the binary's, which its description describes)
 * @param statements - the statements to add
 * @returns the SPARQL Update, or an empty string when there is nothing to add
 */
export function insertStatementsUpdate(subject: string, statements: readonly MetadataStatement[]): string {
  if (statements.length === 0) {
    return '';
  }
  const s = sparqlIri(subject);
  return `INSERT DATA { ${statements.map((st) => `${s} ${sparqlIri(st.predicate)} ${sparqlObject(st)} .`).join(' ')} }`;
}

/** Dublin Core terms the agent's topics and summaries are kept with. */
export const TOPIC_TERMS = {
  subject: 'http://purl.org/dc/terms/subject',
  abstract: 'http://purl.org/dc/terms/abstract',
} as const;

/**
 * Builds the update keeping topics and a summary on a community or collection: each topic as a
 * `dcterms:subject`, the summary as its `dcterms:abstract` (replacing an earlier one).
 *
 * @param subject - the community's or collection's IRI
 * @param labels - the topics kept
 * @param language - their language
 * @param summary - the summary, when kept
 * @returns the SPARQL Update, or an empty string when nothing is kept
 */
export function topicsUpdate(subject: string, labels: readonly string[], language?: string | null, summary?: string): string {
  const statements: MetadataStatement[] = labels.map((label) => ({
    predicate: TOPIC_TERMS.subject,
    value: label,
    kind: 'LITERAL',
    language,
    origin: 'MODEL',
  }));
  const parts: string[] = [];
  if (summary?.trim()) {
    parts.push(`DELETE WHERE { ${sparqlIri(subject)} ${sparqlIri(TOPIC_TERMS.abstract)} ?o }`);
    statements.push({ predicate: TOPIC_TERMS.abstract, value: summary.trim(), kind: 'LITERAL', language, origin: 'MODEL' });
  }
  const insert = insertStatementsUpdate(subject, statements);
  if (insert) {
    parts.push(insert);
  }
  return parts.join(' ;\n');
}

/**
 * Turns a model's draft into a topology the editor can open: every list present, flags and counts
 * with the topology API's defaults, never built in.
 *
 * @param draft - the draft
 * @returns the topology
 */
export function completeTopology(draft: PartialTopology): Topology {
  return {
    id: draft.id ?? '',
    name: draft.name ?? '',
    description: draft.description ?? '',
    builtIn: false,
    root: completeNode(draft.root ?? {}),
  };
}

function completeNode(node: PartialNode): Topology['root'] {
  return {
    key: node.key ?? '',
    label: node.label ?? '',
    pcdmType: node.pcdmType === 'Collection' ? 'Collection' : 'Object',
    types: [...(node.types ?? [])],
    metadata: (node.metadata ?? []).map((field) => ({
      predicate: field.predicate ?? '',
      label: field.label ?? '',
      required: field.required ?? false,
      repeatable: field.repeatable ?? false,
      defaultValue: field.defaultValue ?? null,
      datatype: field.datatype ?? null,
    })),
    files: (node.files ?? []).map((file) => ({
      use: file.use ?? '',
      label: file.label ?? '',
      mediaTypes: [...(file.mediaTypes ?? [])],
    })),
    children: (node.children ?? [])
      .filter((child) => child.node)
      .map((child) => ({
        min: child.min ?? 0,
        max: child.max ?? -1,
        initial: child.initial ?? 0,
        slugPattern: child.slugPattern ?? null,
        node: completeNode(child.node ?? {}),
      })),
  };
}

/**
 * The reason of the agent's problem document.
 *
 * @param error - the failure
 * @returns the reason, for example `MODEL_UNAVAILABLE`, or `undefined`
 */
export function aiReason(error: unknown): string | undefined {
  const http = error instanceof HttpErrorResponse ? error : error instanceof Error ? error.cause : undefined;
  if (!(http instanceof HttpErrorResponse)) {
    return undefined;
  }
  const reason = (http.error as { reason?: unknown } | null)?.reason;
  if (typeof reason === 'string' && AI_REASONS.includes(reason)) {
    return reason;
  }
  // without a problem document from the agent, the agent itself is not there
  return [0, 404, 502, 503, 504].includes(http.status) && typeof (http.error as { detail?: unknown } | null)?.detail !== 'string'
    ? 'UNREACHABLE'
    : undefined;
}

/**
 * Explains a failed AI request in the user's language.
 *
 * @param error - the failure
 * @param t - looks up texts
 * @param fallback - the message when the reason is unknown
 * @returns the message
 */
export function aiErrorMessage(error: unknown, t: Translate, fallback: (error: unknown) => string): string {
  const reason = aiReason(error);
  return reason ? t(`ai.reason.${reason}`) : fallback(error);
}

/**
 * Shows a confidence as a percentage.
 *
 * @param confidence - 0 to 1
 * @returns 0 to 100
 */
export function percent(confidence: number | null | undefined): number {
  return Math.round(Math.max(0, Math.min(1, confidence ?? 0)) * 100);
}
