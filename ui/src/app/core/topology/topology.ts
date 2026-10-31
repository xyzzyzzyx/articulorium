/** A metadata field of a topology node. */
export interface MetadataField {
  readonly predicate: string;
  readonly label: string;
  readonly required: boolean;
  readonly repeatable: boolean;
  readonly defaultValue?: string | null;
  readonly datatype?: string | null;
}

/** A kind of file a node holds. */
export interface FileUse {
  readonly use: string;
  readonly label: string;
  readonly mediaTypes: readonly string[];
}

/** How many children of a kind a node has. */
export interface ChildRule {
  readonly min: number;
  /** `-1` for unlimited. */
  readonly max: number;
  readonly initial: number;
  readonly slugPattern?: string | null;
  readonly node: TopologyNode;
}

/** One level of a topology. */
export interface TopologyNode {
  readonly key: string;
  readonly label: string;
  readonly pcdmType: 'Collection' | 'Object';
  readonly types: readonly string[];
  readonly metadata: readonly MetadataField[];
  readonly files: readonly FileUse[];
  readonly children: readonly ChildRule[];
}

/** A topology: the PCDM shape of a kind of digital object. */
export interface Topology {
  readonly id: string;
  readonly name: string;
  readonly description?: string | null;
  readonly builtIn: boolean;
  readonly root: TopologyNode;
}

/** A topology in listings. */
export interface TopologySummary {
  readonly id: string;
  readonly name: string;
  readonly description?: string | null;
  readonly builtIn: boolean;
  readonly rootLabel?: string | null;
  readonly depth: number;
}

/** A request to build a topology instance. */
export interface BuildRequest {
  readonly parent: string;
  readonly title: string;
  readonly slug?: string;
  readonly metadata?: Readonly<Record<string, readonly string[]>>;
  readonly counts?: Readonly<Record<string, number>>;
}

/** The outcome of a build. */
export interface BuildResult {
  readonly path: string;
  readonly topology: string;
  readonly nodeKey: string;
  readonly resources: readonly string[];
}

/** PCDM use types offered for files. */
export const FILE_USES: readonly { readonly iri: string; readonly label: string }[] = [
  { iri: 'http://pcdm.org/use#OriginalFile', label: 'Original file' },
  { iri: 'http://pcdm.org/use#PreservationMasterFile', label: 'Preservation master' },
  { iri: 'http://pcdm.org/use#ServiceFile', label: 'Service file' },
  { iri: 'http://pcdm.org/use#ThumbnailImage', label: 'Thumbnail' },
  { iri: 'http://pcdm.org/use#ExtractedText', label: 'Extracted text' },
  { iri: 'http://pcdm.org/use#Transcript', label: 'Transcript' },
  { iri: 'http://pcdm.org/use#IntermediateFile', label: 'Intermediate file' },
];

/**
 * Finds a node by key.
 *
 * @param node - the root of the search
 * @param key - the key
 * @returns the node, or `undefined`
 */
export function findNode(node: TopologyNode, key: string): TopologyNode | undefined {
  if (node.key === key) {
    return node;
  }
  for (const child of node.children) {
    const found = findNode(child.node, key);
    if (found) {
      return found;
    }
  }
  return undefined;
}

/**
 * Lists every node of a tree with its depth, depth first.
 *
 * @param node - the root
 * @param depth - the root's depth
 * @returns the nodes
 */
export function flatten(node: TopologyNode, depth = 0): { node: TopologyNode; depth: number; rule?: ChildRule }[] {
  const result: { node: TopologyNode; depth: number; rule?: ChildRule }[] = [{ node, depth }];
  for (const rule of node.children) {
    flatten(rule.node, depth + 1).forEach((entry, index) => result.push(index === 0 ? { ...entry, rule } : entry));
  }
  return result;
}

/**
 * Describes how many children a rule allows.
 *
 * @param rule - the rule
 * @returns for example `1 – ∞` or `exactly 1`
 */
export function cardinality(rule: ChildRule): string {
  if (rule.max === rule.min) {
    return `exactly ${rule.min}`;
  }
  return `${rule.min} – ${rule.max < 0 ? '∞' : rule.max}`;
}

/**
 * Creates an empty node for the editor.
 *
 * @param key - the key
 * @param label - the label
 * @param pcdmType - Collection or Object
 * @returns the node
 */
export function emptyNode(key: string, label: string, pcdmType: TopologyNode['pcdmType'] = 'Object'): TopologyNode {
  return {
    key,
    label,
    pcdmType,
    types: [],
    metadata: [{ predicate: 'http://purl.org/dc/terms/title', label: 'Title', required: true, repeatable: false }],
    files: [],
    children: [],
  };
}

/**
 * Checks a topology before it is sent (the service validates again).
 *
 * @param topology - the topology
 * @returns the problems found
 */
export function validateTopology(topology: Topology): string[] {
  const errors: string[] = [];
  const slug = /^[a-z0-9][a-z0-9_-]{0,63}$/;
  if (!slug.test(topology.id)) {
    errors.push('The identifier must be a lower-case slug (a-z, 0-9, - and _).');
  }
  if (!topology.name.trim()) {
    errors.push('The name is required.');
  }
  const keys = new Set<string>();
  for (const { node } of flatten(topology.root)) {
    if (!slug.test(node.key)) {
      errors.push(`Node key "${node.key}" must be a lower-case slug.`);
    } else if (keys.has(node.key)) {
      errors.push(`Node key "${node.key}" is used twice.`);
    }
    keys.add(node.key);
    if (!node.label.trim()) {
      errors.push(`Node "${node.key}" needs a label.`);
    }
    for (const rule of node.children) {
      if (node.pcdmType === 'Object' && rule.node.pcdmType === 'Collection') {
        errors.push(`A PCDM object (${node.key}) cannot contain a collection (${rule.node.key}).`);
      }
      if (rule.max >= 0 && rule.max < rule.min) {
        errors.push(`The maximum of ${rule.node.key} must be unlimited (-1) or at least the minimum.`);
      }
    }
  }
  return errors;
}

/**
 * Splits a comma or line separated list.
 *
 * @param value - the text
 * @returns the trimmed, non-empty items
 */
export function splitList(value: string): string[] {
  return value
    .split(/[\n,]/)
    .map((item) => item.trim())
    .filter(Boolean);
}
