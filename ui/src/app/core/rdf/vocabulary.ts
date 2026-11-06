/**
 * IRIs of the vocabularies the user interface interprets, and the default namespace prefixes
 * used to display IRIs compactly.
 *
 * The prefixes mirror the platform's bundled `namespaces.yml`: every namespace Fedora Commons
 * declares by default plus the common Linked Data vocabularies (PCDM, ORE, SKOS, schema.org,
 * VIVO...).
 */

/** RDF and XML Schema terms. */
export const RDF = {
  type: 'http://www.w3.org/1999/02/22-rdf-syntax-ns#type',
} as const;

/** XML Schema datatype namespace. */
export const XSD = 'http://www.w3.org/2001/XMLSchema#';

/** W3C Linked Data Platform terms. */
export const LDP = {
  ns: 'http://www.w3.org/ns/ldp#',
  resource: 'http://www.w3.org/ns/ldp#Resource',
  rdfSource: 'http://www.w3.org/ns/ldp#RDFSource',
  nonRdfSource: 'http://www.w3.org/ns/ldp#NonRDFSource',
  container: 'http://www.w3.org/ns/ldp#Container',
  basicContainer: 'http://www.w3.org/ns/ldp#BasicContainer',
  directContainer: 'http://www.w3.org/ns/ldp#DirectContainer',
  indirectContainer: 'http://www.w3.org/ns/ldp#IndirectContainer',
  contains: 'http://www.w3.org/ns/ldp#contains',
  membershipResource: 'http://www.w3.org/ns/ldp#membershipResource',
  hasMemberRelation: 'http://www.w3.org/ns/ldp#hasMemberRelation',
  insertedContentRelation: 'http://www.w3.org/ns/ldp#insertedContentRelation',
} as const;

/** Fedora repository terms (server-managed properties). */
export const FEDORA = {
  ns: 'http://fedora.info/definitions/v4/repository#',
  binary: 'http://fedora.info/definitions/v4/repository#Binary',
  created: 'http://fedora.info/definitions/v4/repository#created',
  createdBy: 'http://fedora.info/definitions/v4/repository#createdBy',
  lastModified: 'http://fedora.info/definitions/v4/repository#lastModified',
  lastModifiedBy: 'http://fedora.info/definitions/v4/repository#lastModifiedBy',
} as const;

/** PREMIS and EBUCore terms describing binaries. */
export const BINARY = {
  size: 'http://www.loc.gov/premis/rdf/v1#hasSize',
  digest: 'http://www.loc.gov/premis/rdf/v1#hasMessageDigest',
  mimeType: 'http://www.ebu.ch/metadata/ontologies/ebucore/ebucore#hasMimeType',
  filename: 'http://www.ebu.ch/metadata/ontologies/ebucore/ebucore#filename',
} as const;

/** Fedora's archival group class and Articulorium's link relation from members to their group. */
export const ARCHIVAL_GROUP = {
  type: 'http://fedora.info/definitions/v4/repository#ArchivalGroup',
  rel: 'https://articulorium.org/ns/repository#archivalGroup',
} as const;

/** Web Access Control terms. */
export const ACL = {
  ns: 'http://www.w3.org/ns/auth/acl#',
  foafAgent: 'http://xmlns.com/foaf/0.1/Agent',
  authenticatedAgent: 'http://www.w3.org/ns/auth/acl#AuthenticatedAgent',
} as const;

/** Portland Common Data Model terms. */
export const PCDM = {
  ns: 'http://pcdm.org/models#',
  collection: 'http://pcdm.org/models#Collection',
  object: 'http://pcdm.org/models#Object',
  file: 'http://pcdm.org/models#File',
  hasMember: 'http://pcdm.org/models#hasMember',
  hasFile: 'http://pcdm.org/models#hasFile',
  use: 'http://pcdm.org/use#',
} as const;

/** Articulorium repository terms (community structure and topology links). */
export const ART = {
  ns: 'https://articulorium.org/ns/repository#',
  community: 'https://articulorium.org/ns/repository#Community',
  collection: 'https://articulorium.org/ns/repository#Collection',
  communityRoot: 'https://articulorium.org/ns/repository#CommunityRoot',
  profileRoot: 'https://articulorium.org/ns/repository#ProfileRoot',
  topology: 'https://articulorium.org/ns/repository#topology',
  topologyNode: 'https://articulorium.org/ns/repository#topologyNode',
} as const;

/** The VIVO ontology (academic profiles): classes and properties of `vivo:`. */
export const VIVO = {
  ns: 'http://vivoweb.org/ontology/core#',
  overview: 'http://vivoweb.org/ontology/core#overview',
  orcidId: 'http://vivoweb.org/ontology/core#orcidId',
  freetextKeyword: 'http://vivoweb.org/ontology/core#freetextKeyword',
  hasResearchArea: 'http://vivoweb.org/ontology/core#hasResearchArea',
  researchAreaOf: 'http://vivoweb.org/ontology/core#researchAreaOf',
  relatedBy: 'http://vivoweb.org/ontology/core#relatedBy',
  relates: 'http://vivoweb.org/ontology/core#relates',
  rank: 'http://vivoweb.org/ontology/core#rank',
  middleName: 'http://vivoweb.org/ontology/core#middleName',
  dateTimeInterval: 'http://vivoweb.org/ontology/core#dateTimeInterval',
  dateTimeValue: 'http://vivoweb.org/ontology/core#dateTimeValue',
  start: 'http://vivoweb.org/ontology/core#start',
  end: 'http://vivoweb.org/ontology/core#end',
  dateTime: 'http://vivoweb.org/ontology/core#dateTime',
  dateTimePrecision: 'http://vivoweb.org/ontology/core#dateTimePrecision',
  yearPrecision: 'http://vivoweb.org/ontology/core#yearPrecision',
  DateTimeInterval: 'http://vivoweb.org/ontology/core#DateTimeInterval',
  DateTimeValue: 'http://vivoweb.org/ontology/core#DateTimeValue',
  Position: 'http://vivoweb.org/ontology/core#Position',
  Authorship: 'http://vivoweb.org/ontology/core#Authorship',
  EducationalProcess: 'http://vivoweb.org/ontology/core#EducationalProcess',
  AwardedDegree: 'http://vivoweb.org/ontology/core#AwardedDegree',
  majorField: 'http://vivoweb.org/ontology/core#majorField',
  assignedBy: 'http://vivoweb.org/ontology/core#assignedBy',
  hasPublicationVenue: 'http://vivoweb.org/ontology/core#hasPublicationVenue',
} as const;

/** OBO Relation Ontology and ARG terms the VIVO ontology uses. */
export const OBO = {
  ns: 'http://purl.obolibrary.org/obo/',
  /** has contact info (person to vCard). */
  hasContactInfo: 'http://purl.obolibrary.org/obo/ARG_2000028',
  /** contact information for (vCard to person). */
  contactInformationFor: 'http://purl.obolibrary.org/obo/ARG_2000029',
  /** participates in (person to process). */
  participatesIn: 'http://purl.obolibrary.org/obo/RO_0000056',
  /** has participant (process to person or organization). */
  hasParticipant: 'http://purl.obolibrary.org/obo/RO_0000057',
  /** has output (process to awarded degree). */
  hasOutput: 'http://purl.obolibrary.org/obo/RO_0002234',
} as const;

/** vCard terms of a VIVO contact card. */
export const VCARD = {
  ns: 'http://www.w3.org/2006/vcard/ns#',
  Individual: 'http://www.w3.org/2006/vcard/ns#Individual',
  Name: 'http://www.w3.org/2006/vcard/ns#Name',
  Title: 'http://www.w3.org/2006/vcard/ns#Title',
  Email: 'http://www.w3.org/2006/vcard/ns#Email',
  Work: 'http://www.w3.org/2006/vcard/ns#Work',
  Telephone: 'http://www.w3.org/2006/vcard/ns#Telephone',
  URL: 'http://www.w3.org/2006/vcard/ns#URL',
  hasName: 'http://www.w3.org/2006/vcard/ns#hasName',
  givenName: 'http://www.w3.org/2006/vcard/ns#givenName',
  familyName: 'http://www.w3.org/2006/vcard/ns#familyName',
  hasTitle: 'http://www.w3.org/2006/vcard/ns#hasTitle',
  title: 'http://www.w3.org/2006/vcard/ns#title',
  hasEmail: 'http://www.w3.org/2006/vcard/ns#hasEmail',
  email: 'http://www.w3.org/2006/vcard/ns#email',
  hasTelephone: 'http://www.w3.org/2006/vcard/ns#hasTelephone',
  telephone: 'http://www.w3.org/2006/vcard/ns#telephone',
  hasURL: 'http://www.w3.org/2006/vcard/ns#hasURL',
  url: 'http://www.w3.org/2006/vcard/ns#url',
} as const;

/** FOAF, RDFS, SKOS and BIBO terms used by academic profiles. */
export const FOAF = {
  Person: 'http://xmlns.com/foaf/0.1/Person',
  Organization: 'http://xmlns.com/foaf/0.1/Organization',
} as const;

/** `rdfs:label`. */
export const RDFS_LABEL = 'http://www.w3.org/2000/01/rdf-schema#label';

/** `skos:Concept`. */
export const SKOS_CONCEPT = 'http://www.w3.org/2004/02/skos/core#Concept';

/** Bibliographic Ontology terms of publications. */
export const BIBO = {
  ns: 'http://purl.org/ontology/bibo/',
  doi: 'http://purl.org/ontology/bibo/doi',
  Document: 'http://purl.org/ontology/bibo/Document',
  Periodical: 'http://purl.org/ontology/bibo/Periodical',
} as const;

/** Dublin Core terms used by the forms. */
export const DCTERMS = {
  title: 'http://purl.org/dc/terms/title',
  description: 'http://purl.org/dc/terms/description',
} as const;

/** Order of members among their siblings. */
export const SCHEMA_POSITION = 'http://schema.org/position';

/** Linked Data Notifications inbox link relation. */
export const LDP_INBOX = 'http://www.w3.org/ns/ldp#inbox';

/** Memento terms. */
export const MEMENTO = {
  memento: 'http://mementoweb.org/ns#Memento',
  datetime: 'http://mementoweb.org/ns#mementoDatetime',
} as const;

/** Properties used, in order of preference, as the display title of a resource. */
export const TITLE_PROPERTIES: readonly string[] = [
  'http://purl.org/dc/terms/title',
  'http://purl.org/dc/elements/1.1/title',
  'http://www.w3.org/2004/02/skos/core#prefLabel',
  'http://www.w3.org/2000/01/rdf-schema#label',
  'http://xmlns.com/foaf/0.1/name',
  'http://schema.org/name',
  BINARY.filename,
];

/** Default namespace prefixes (prefix to namespace IRI). */
export const DEFAULT_PREFIXES: Readonly<Record<string, string>> = {
  acl: 'http://www.w3.org/ns/auth/acl#',
  art: 'https://articulorium.org/ns/repository#',
  as: 'https://www.w3.org/ns/activitystreams#',
  bibo: 'http://purl.org/ontology/bibo/',
  dc: 'http://purl.org/dc/elements/1.1/',
  dcmitype: 'http://purl.org/dc/dcmitype/',
  dcterms: 'http://purl.org/dc/terms/',
  ebucore: 'http://www.ebu.ch/metadata/ontologies/ebucore/ebucore#',
  evOutcome: 'http://id.loc.gov/vocabulary/preservation/eventOutcome/',
  evType: 'http://id.loc.gov/vocabulary/preservation/eventType/',
  fedora: 'http://fedora.info/definitions/v4/repository#',
  fedoraconfig: 'http://fedora.info/definitions/v4/config#',
  foaf: 'http://xmlns.com/foaf/0.1/',
  hashFunc: 'http://id.loc.gov/vocabulary/preservation/cryptographicHashFunctions/',
  iana: 'http://www.iana.org/assignments/relation/',
  ldp: 'http://www.w3.org/ns/ldp#',
  memento: 'http://mementoweb.org/ns#',
  obo: 'http://purl.obolibrary.org/obo/',
  ore: 'http://www.openarchives.org/ore/terms/',
  owl: 'http://www.w3.org/2002/07/owl#',
  pcdm: 'http://pcdm.org/models#',
  pcdmrights: 'http://pcdm.org/rights#',
  pcdmuse: 'http://pcdm.org/use#',
  premis: 'http://www.loc.gov/premis/rdf/v1#',
  premis3: 'http://www.loc.gov/premis/rdf/v3/',
  prov: 'http://www.w3.org/ns/prov#',
  rdf: 'http://www.w3.org/1999/02/22-rdf-syntax-ns#',
  rdfs: 'http://www.w3.org/2000/01/rdf-schema#',
  schema: 'http://schema.org/',
  skos: 'http://www.w3.org/2004/02/skos/core#',
  vcard: 'http://www.w3.org/2006/vcard/ns#',
  vivo: 'http://vivoweb.org/ontology/core#',
  webac: 'http://fedora.info/definitions/v4/webac#',
  xml: 'http://www.w3.org/XML/1998/namespace',
  xs: 'http://www.w3.org/2001/XMLSchema',
  xsd: 'http://www.w3.org/2001/XMLSchema#',
  xsi: 'http://www.w3.org/2001/XMLSchema-instance',
  xmlns: 'http://www.w3.org/2000/xmlns/',
};
