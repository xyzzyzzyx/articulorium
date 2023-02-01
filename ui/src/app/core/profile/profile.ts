import { Graph } from '../rdf/graph';
import { sparqlIri, sparqlLiteral } from '../rdf/sparql';
import { IriTerm, SubjectTerm, Term, Triple, iri, literal, subjectKey } from '../rdf/term';
import { BIBO, FEDORA, FOAF, LDP, OBO, RDF, RDFS_LABEL, SKOS_CONCEPT, VCARD, VIVO, XSD } from '../rdf/vocabulary';

/**
 * Academic profiles described with the VIVO ontology.
 *
 * A profile is one LDP resource (a basic container below the profile container) whose subject is
 * the person. Everything VIVO models as separate individuals (the vCard, positions, educational
 * processes, authorships, publications, organizations, date-time values) is a hash IRI of the
 * profile (`<#position-1>`), so a profile is read and replaced as a whole, with one entity tag
 * guarding concurrent edits.
 *
 * The editor owns the parts it models and regenerates them on every save. Statements it does not
 * model (other properties of the person, relationships to resources elsewhere, unrecognised
 * related individuals) are kept as they are (see {@link ProfileDocument.preserved}).
 */

/** VIVO person classes offered by the editor (`foaf:Person` alone is `Person`). */
export const PERSON_TYPES = [
  'FacultyMember',
  'NonFacultyAcademic',
  'EmeritusProfessor',
  'Librarian',
  'Student',
  'NonAcademic',
  'Person',
] as const;

/** The VIVO class of a person. */
export type PersonType = (typeof PERSON_TYPES)[number];

/** VIVO position classes offered by the editor. */
export const POSITION_TYPES = [
  'FacultyPosition',
  'FacultyAdministrativePosition',
  'NonFacultyAcademicPosition',
  'PostdocPosition',
  'Position',
] as const;

/** The VIVO class of a position. */
export type PositionType = (typeof POSITION_TYPES)[number];

/** Publication classes offered by the editor, with their IRIs. */
export const PUBLICATION_TYPES = {
  AcademicArticle: `${BIBO.ns}AcademicArticle`,
  ConferencePaper: `${VIVO.ns}ConferencePaper`,
  Book: `${BIBO.ns}Book`,
  Chapter: `${BIBO.ns}Chapter`,
  Document: BIBO.Document,
} as const;

/** The class of a publication. */
export type PublicationType = keyof typeof PUBLICATION_TYPES;

/** Class of the venue a publication appears in, by publication class. */
const VENUE_TYPES: Record<PublicationType, string> = {
  AcademicArticle: `${BIBO.ns}Journal`,
  ConferencePaper: `${BIBO.ns}Proceedings`,
  Book: BIBO.Periodical,
  Chapter: `${BIBO.ns}Book`,
  Document: BIBO.Periodical,
};

/** A web page of the person (`vcard:URL`). */
export interface ProfileWebsite {
  url: string;
  label: string;
}

/** A position held in an organization (`vivo:Position` relating the person and a `foaf:Organization`). */
export interface ProfilePosition {
  title: string;
  type: PositionType;
  organization: string;
  /** Start year (`YYYY`), may be empty. */
  start: string;
  /** End year (`YYYY`), empty while the position is held. */
  end: string;
}

/** A degree earned (`vivo:EducationalProcess` with a `vivo:AwardedDegree`). */
export interface ProfileEducation {
  degree: string;
  field: string;
  organization: string;
  /** Year the degree was awarded (`YYYY`), may be empty. */
  end: string;
}

/** A publication authored (`vivo:Authorship` relating the person and a `bibo:Document`). */
export interface ProfilePublication {
  title: string;
  type: PublicationType;
  venue: string;
  /** Publication year (`YYYY`), may be empty. */
  year: string;
  /** DOI without resolver, for example `10.1000/182`. */
  doi: string;
}

/** An academic profile as edited: every field is a plain, mutable value. */
export interface Profile {
  personType: PersonType;
  givenName: string;
  middleName: string;
  familyName: string;
  /** Preferred title, for example `Professor of Physics` (`vcard:Title`). */
  preferredTitle: string;
  email: string;
  phone: string;
  /** ORCID iD, for example `0000-0002-1825-0097` (stored as `vivo:orcidId <https://orcid.org/…>`). */
  orcid: string;
  overview: string;
  keywords: string[];
  researchAreas: string[];
  websites: ProfileWebsite[];
  positions: ProfilePosition[];
  education: ProfileEducation[];
  publications: ProfilePublication[];
}

/** A profile read from the repository with the statements the editor does not model. */
export interface ProfileDocument {
  readonly profile: Profile;
  /** Statements kept unchanged when the profile is saved. */
  readonly preserved: readonly Triple[];
}

/** A validation problem: the field it concerns and a translation key. */
export interface ProfileIssue {
  /** The field, for example `familyName` or `positions.0.start`. */
  readonly field: string;
  /** Translation key of the message, for example `profiles.validation.required`. */
  readonly key: string;
  /** Placeholders of the message. */
  readonly params?: Readonly<Record<string, unknown>>;
}

/** Longest overview accepted. */
export const MAX_OVERVIEW_LENGTH = 10000;

/** ORCID's resolver; ORCID iDs are stored as IRIs below it. */
export const ORCID_BASE = 'https://orcid.org/';

/** The DOI resolver used to link DOIs. */
export const DOI_BASE = 'https://doi.org/';

/** Person classes and their IRIs. */
const PERSON_TYPE_IRIS: Record<PersonType, string | undefined> = {
  FacultyMember: `${VIVO.ns}FacultyMember`,
  NonFacultyAcademic: `${VIVO.ns}NonFacultyAcademic`,
  EmeritusProfessor: `${VIVO.ns}EmeritusProfessor`,
  Librarian: `${VIVO.ns}Librarian`,
  Student: `${VIVO.ns}Student`,
  NonAcademic: `${VIVO.ns}NonAcademic`,
  Person: undefined,
};

/** Properties of the person the editor owns entirely (any value). */
const OWNED_PROPERTIES = new Set<string>([RDFS_LABEL, VIVO.overview, VIVO.orcidId, VIVO.freetextKeyword]);

/** Base of the IRIs of a profile that has no IRI yet; written relative, so never stored. */
const NEW_PROFILE = 'urn:articulorium:new-profile';

/**
 * A new, empty profile.
 *
 * @returns the profile
 */
export function emptyProfile(): Profile {
  return {
    personType: 'FacultyMember',
    givenName: '',
    middleName: '',
    familyName: '',
    preferredTitle: '',
    email: '',
    phone: '',
    orcid: '',
    overview: '',
    keywords: [],
    researchAreas: [],
    websites: [],
    positions: [],
    education: [],
    publications: [],
  };
}

/** An empty website row. */
export function emptyWebsite(): ProfileWebsite {
  return { url: '', label: '' };
}

/** An empty position row. */
export function emptyPosition(): ProfilePosition {
  return { title: '', type: 'FacultyPosition', organization: '', start: '', end: '' };
}

/** An empty education row. */
export function emptyEducation(): ProfileEducation {
  return { degree: '', field: '', organization: '', end: '' };
}

/** An empty publication row. */
export function emptyPublication(): ProfilePublication {
  return { title: '', type: 'AcademicArticle', venue: '', year: '', doi: '' };
}

/**
 * Tells whether a resource is an academic profile.
 *
 * @param graph - its representation
 * @param subject - the resource
 * @returns `true` when it is a `foaf:Person`
 */
export function isProfile(graph: Graph, subject: SubjectTerm): boolean {
  return graph.hasType(subject, FOAF.Person);
}

/**
 * The name of a person as written in running text: `Marie Skłodowska Curie`.
 *
 * @param profile - the profile
 * @returns the name, possibly empty
 */
export function displayName(profile: Pick<Profile, 'givenName' | 'middleName' | 'familyName'>): string {
  return [profile.givenName, profile.middleName, profile.familyName]
    .map((part) => part.trim())
    .filter(Boolean)
    .join(' ');
}

/**
 * The name of a person as sorted and labelled by VIVO (`rdfs:label`): `Curie, Marie Skłodowska`.
 *
 * @param profile - the profile
 * @returns the label, possibly empty
 */
export function sortName(profile: Pick<Profile, 'givenName' | 'middleName' | 'familyName'>): string {
  const given = [profile.givenName, profile.middleName]
    .map((part) => part.trim())
    .filter(Boolean)
    .join(' ');
  const family = profile.familyName.trim();
  return family && given ? `${family}, ${given}` : family || given;
}

/**
 * Reads the canonical form of an ORCID iD, with or without its resolver.
 *
 * @param value - for example `https://orcid.org/0000-0002-1825-0097` or `0000000218250097`
 * @returns `0000-0002-1825-0097`, or `undefined` when the value is not an ORCID iD with a
 *   valid check digit (ISO 7064 MOD 11-2)
 */
export function orcidId(value: string): string | undefined {
  const match = /^(?:https?:\/\/(?:www\.)?orcid\.org\/)?(\d{4})-?(\d{4})-?(\d{4})-?(\d{3}[\dX])$/i.exec(value.trim());
  if (!match) {
    return undefined;
  }
  const digits = match.slice(1).join('').toUpperCase();
  let total = 0;
  for (const digit of digits.substring(0, 15)) {
    total = (total + Number(digit)) * 2;
  }
  const remainder = (12 - (total % 11)) % 11;
  const check = remainder === 10 ? 'X' : String(remainder);
  return check === digits[15] ? match.slice(1).join('-').toUpperCase() : undefined;
}

/**
 * Reads a DOI, with or without a resolver or `doi:` prefix.
 *
 * @param value - for example `https://doi.org/10.1000/182`
 * @returns `10.1000/182`, or `undefined` when the value is not a DOI
 */
export function doiOf(value: string): string | undefined {
  const bare = value
    .trim()
    .replace(/^https?:\/\/(?:dx\.)?doi\.org\//i, '')
    .replace(/^doi:\s*/i, '');
  return /^10\.\d{4,9}\/\S+$/.test(bare) ? bare : undefined;
}

/**
 * Validates a profile before it is saved. Rows of repeatable fields that are entirely empty are
 * ignored (they are dropped on save).
 *
 * @param profile - the profile
 * @param currentYear - the current year, latest acceptable start year
 * @returns the problems, empty when the profile can be saved
 */
export function validateProfile(profile: Profile, currentYear = new Date().getFullYear()): ProfileIssue[] {
  const issues: ProfileIssue[] = [];
  const required = (field: string, value: string): void => {
    if (!value.trim()) {
      issues.push({ field, key: 'profiles.validation.required' });
    }
  };
  const year = (field: string, value: string, latest: number): boolean => {
    const text = value.trim();
    if (!text) {
      return true;
    }
    if (!/^\d{4}$/.test(text) || Number(text) < 1000 || Number(text) > latest) {
      issues.push({ field, key: 'profiles.validation.year', params: { max: latest } });
      return false;
    }
    return true;
  };

  required('givenName', profile.givenName);
  required('familyName', profile.familyName);
  if (profile.email.trim() && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(profile.email.trim())) {
    issues.push({ field: 'email', key: 'profiles.validation.email' });
  }
  if (profile.phone.trim() && !/^\+?[\d\s().-]{4,30}(?:\s*(?:x|ext\.?)\s*\d{1,6})?$/i.test(profile.phone.trim())) {
    issues.push({ field: 'phone', key: 'profiles.validation.phone' });
  }
  if (profile.orcid.trim() && !orcidId(profile.orcid)) {
    issues.push({ field: 'orcid', key: 'profiles.validation.orcid' });
  }
  if (profile.overview.length > MAX_OVERVIEW_LENGTH) {
    issues.push({ field: 'overview', key: 'profiles.validation.tooLong', params: { max: MAX_OVERVIEW_LENGTH } });
  }
  profile.websites.forEach((website, index) => {
    if (isBlank(website)) {
      return;
    }
    if (!website.url.trim()) {
      issues.push({ field: `websites.${index}.url`, key: 'profiles.validation.required' });
    } else if (!isWebUrl(website.url.trim())) {
      issues.push({ field: `websites.${index}.url`, key: 'profiles.validation.url' });
    }
  });
  profile.positions.forEach((position, index) => {
    if (isBlank(position, ['type'])) {
      return;
    }
    required(`positions.${index}.title`, position.title);
    required(`positions.${index}.organization`, position.organization);
    const start = year(`positions.${index}.start`, position.start, currentYear + 1);
    const end = year(`positions.${index}.end`, position.end, currentYear + 10);
    if (start && end && position.start.trim() && position.end.trim() && Number(position.end) < Number(position.start)) {
      issues.push({ field: `positions.${index}.end`, key: 'profiles.validation.endBeforeStart' });
    }
  });
  profile.education.forEach((education, index) => {
    if (isBlank(education)) {
      return;
    }
    required(`education.${index}.degree`, education.degree);
    required(`education.${index}.organization`, education.organization);
    year(`education.${index}.end`, education.end, currentYear + 10);
  });
  profile.publications.forEach((publication, index) => {
    if (isBlank(publication, ['type'])) {
      return;
    }
    required(`publications.${index}.title`, publication.title);
    year(`publications.${index}.year`, publication.year, currentYear + 1);
    if (publication.doi.trim() && !doiOf(publication.doi)) {
      issues.push({ field: `publications.${index}.doi`, key: 'profiles.validation.doi' });
    }
  });
  return issues;
}

/**
 * Cleans a profile for saving: trims every value, writes ORCID iDs and DOIs in their canonical
 * form, drops empty rows and repeated keywords or research areas, and sorts keywords and research
 * areas (they are unordered in RDF, so they are always shown sorted).
 *
 * @param profile - the profile (left unchanged)
 * @returns the cleaned copy
 */
export function normalizeProfile(profile: Profile): Profile {
  const trim = <T extends object>(row: T): T =>
    Object.fromEntries(Object.entries(row).map(([key, value]) => [key, typeof value === 'string' ? value.trim() : value])) as T;
  return {
    ...trim(profile),
    orcid: orcidId(profile.orcid) ?? profile.orcid.trim(),
    keywords: sorted(distinct(profile.keywords)),
    researchAreas: sorted(distinct(profile.researchAreas)),
    websites: profile.websites.filter((row) => !isBlank(row)).map(trim),
    positions: profile.positions.filter((row) => !isBlank(row, ['type'])).map(trim),
    education: profile.education.filter((row) => !isBlank(row)).map(trim),
    publications: profile.publications
      .filter((row) => !isBlank(row, ['type']))
      .map(trim)
      .map((row) => ({ ...row, doi: doiOf(row.doi) ?? row.doi })),
  };
}

/**
 * Reads a profile from the representation of its resource.
 *
 * @param graph - the representation
 * @param person - the profile's subject (the resource IRI)
 * @returns the profile and the statements it does not model
 */
export function readProfile(graph: Graph, person: IriTerm): ProfileDocument {
  const profile = emptyProfile();
  const local = (term: Term): term is SubjectTerm =>
    term.kind === 'bnode' || (term.kind === 'iri' && term.value.startsWith(`${person.value}#`));
  const label = (node: Term | undefined): string =>
    node && node.kind !== 'literal' ? (graph.value(node, RDFS_LABEL) ?? '') : '';
  const yearOf = (value: Term | undefined, predicate: string): string => {
    const point = value && value.kind !== 'literal' ? graph.objects(value, predicate)[0] : undefined;
    const dateTime = point && point.kind !== 'literal' ? graph.value(point, VIVO.dateTime) : undefined;
    return dateTime && /^-?\d{4}/.test(dateTime) ? dateTime.substring(0, 4) : '';
  };
  const intervalYear = (node: SubjectTerm, predicate: string): string =>
    yearOf(graph.objects(node, VIVO.dateTimeInterval)[0], predicate);
  const rank = (node: SubjectTerm): number => {
    const value = Number(graph.value(node, VIVO.rank));
    return Number.isFinite(value) ? value : Number.MAX_SAFE_INTEGER;
  };
  const other = (node: SubjectTerm, predicate: string): Term | undefined =>
    graph.objects(node, predicate).find((term) => term.kind !== 'literal' && term.value !== person.value);
  const byRank = <T>(rows: { node: SubjectTerm; value: T }[]): T[] =>
    rows.sort((a, b) => rank(a.node) - rank(b.node)).map((row) => row.value);

  // the managed statements of the person: [predicate, object key] pairs
  const consumed = new Set<string>();
  const consume = (predicate: string, node: SubjectTerm): void => {
    consumed.add(`${predicate} ${subjectKey(node)}`);
  };
  const roots: SubjectTerm[] = [];

  const types = graph.types(person);
  profile.personType = PERSON_TYPES.find((type) => {
    const typeIri = PERSON_TYPE_IRIS[type];
    return typeIri !== undefined && types.includes(typeIri);
  }) ?? 'Person';
  profile.overview = graph.value(person, VIVO.overview) ?? '';
  const orcid = graph.objects(person, VIVO.orcidId)[0];
  profile.orcid = orcid ? (orcidId(orcid.value) ?? orcid.value) : '';
  profile.keywords = sorted(
    graph
      .objects(person, VIVO.freetextKeyword)
      .filter((term) => term.kind === 'literal')
      .map((term) => term.value),
  );

  const card = graph.objects(person, OBO.hasContactInfo).find(local);
  if (card) {
    consume(OBO.hasContactInfo, card);
    roots.push(card);
    const name = graph.objects(card, VCARD.hasName).find(local);
    if (name) {
      profile.givenName = graph.value(name, VCARD.givenName) ?? '';
      profile.middleName = graph.value(name, VIVO.middleName) ?? '';
      profile.familyName = graph.value(name, VCARD.familyName) ?? '';
    }
    const title = graph.objects(card, VCARD.hasTitle).find(local);
    profile.preferredTitle = title ? (graph.value(title, VCARD.title) ?? '') : '';
    const email = graph.objects(card, VCARD.hasEmail).find(local);
    profile.email = email ? (graph.value(email, VCARD.email) ?? '') : '';
    const phone = graph.objects(card, VCARD.hasTelephone).find(local);
    profile.phone = phone ? (graph.value(phone, VCARD.telephone) ?? '') : '';
    profile.websites = byRank(
      graph
        .objects(card, VCARD.hasURL)
        .filter(local)
        .map((node) => ({ node, value: { url: graph.value(node, VCARD.url) ?? '', label: label(node) } })),
    );
  }

  const areas = graph.objects(person, VIVO.hasResearchArea).filter(local);
  areas.forEach((area) => {
    consume(VIVO.hasResearchArea, area);
    roots.push(area);
  });
  profile.researchAreas = sorted(areas.map(label).filter(Boolean));

  const positions: { node: SubjectTerm; value: ProfilePosition }[] = [];
  const publications: { node: SubjectTerm; value: ProfilePublication }[] = [];
  for (const node of graph.objects(person, VIVO.relatedBy).filter(local)) {
    const nodeTypes = graph.types(node);
    if (nodeTypes.includes(VIVO.Authorship)) {
      const document = other(node, VIVO.relates);
      if (!document || document.kind === 'literal') {
        continue;
      }
      const documentTypes = graph.types(document);
      const type = (Object.keys(PUBLICATION_TYPES) as PublicationType[]).find((key) =>
        documentTypes.includes(PUBLICATION_TYPES[key]),
      );
      const doi = graph.value(document, BIBO.doi) ?? '';
      publications.push({
        node,
        value: {
          title: label(document),
          type: type ?? 'Document',
          venue: label(graph.objects(document, VIVO.hasPublicationVenue)[0]),
          year: yearOf(document, VIVO.dateTimeValue),
          doi: doiOf(doi) ?? doi,
        },
      });
    } else if (nodeTypes.some((type) => type === VIVO.Position || (type.startsWith(VIVO.ns) && type.endsWith('Position')))) {
      const type = POSITION_TYPES.find((key) => nodeTypes.includes(`${VIVO.ns}${key}`));
      positions.push({
        node,
        value: {
          title: label(node),
          type: type ?? 'Position',
          organization: label(other(node, VIVO.relates)),
          start: intervalYear(node, VIVO.start),
          end: intervalYear(node, VIVO.end),
        },
      });
    } else {
      continue;
    }
    consume(VIVO.relatedBy, node);
    roots.push(node);
  }
  profile.positions = byRank(positions);
  profile.publications = byRank(publications);

  const education: { node: SubjectTerm; value: ProfileEducation }[] = [];
  for (const node of graph.objects(person, OBO.participatesIn).filter(local)) {
    if (!graph.hasType(node, VIVO.EducationalProcess)) {
      continue;
    }
    education.push({
      node,
      value: {
        degree: label(graph.objects(node, OBO.hasOutput)[0]),
        field: graph.value(node, VIVO.majorField) ?? '',
        organization: label(other(node, OBO.hasParticipant)),
        end: intervalYear(node, VIVO.end),
      },
    });
    consume(OBO.participatesIn, node);
    roots.push(node);
  }
  profile.education = byRank(education);

  // every individual reachable from a modelled part belongs to the profile's managed parts
  const managed = new Set<string>();
  const pending = [...roots];
  while (pending.length > 0) {
    const node = pending.pop() as SubjectTerm;
    const key = subjectKey(node);
    if (managed.has(key)) {
      continue;
    }
    managed.add(key);
    for (const triple of graph.about(node)) {
      if (local(triple.object) && triple.object.value !== person.value) {
        pending.push(triple.object);
      }
    }
  }

  const managedTypes = new Set<string>([FOAF.Person, ...Object.values(PERSON_TYPE_IRIS).filter((v): v is string => !!v)]);
  const preserved = graph.triples.filter((triple) => {
    if (isServerManaged(triple)) {
      return false;
    }
    if (managed.has(subjectKey(triple.subject))) {
      return false;
    }
    if (triple.subject.kind === 'iri' && triple.subject.value === person.value) {
      const predicate = triple.predicate.value;
      if (OWNED_PROPERTIES.has(predicate)) {
        return false;
      }
      if (predicate === RDF.type && managedTypes.has(triple.object.value)) {
        return false;
      }
      if (triple.object.kind !== 'literal' && consumed.has(`${predicate} ${subjectKey(triple.object)}`)) {
        return false;
      }
    }
    return true;
  });
  return { profile, preserved };
}

/**
 * Writes a profile as Turtle for creating (`POST`) or replacing (`PUT`) its resource: `<>` is the
 * person and `<#…>` its parts.
 *
 * @param input - the profile (normalized here)
 * @param subjectIri - the IRI of an existing profile, to write the preserved statements relative
 *   to it; omitted for a new profile
 * @param preserved - statements to keep (see {@link readProfile})
 * @returns the Turtle document
 */
export function profileTurtle(input: Profile, subjectIri?: string, preserved: readonly Triple[] = []): string {
  const profile = normalizeProfile(input);
  const base = subjectIri ?? NEW_PROFILE;
  const triples: Triple[] = [];
  const node = (fragment: string): IriTerm => iri(`${base}#${fragment}`);
  const person = iri(base);
  const add = (subject: SubjectTerm, predicate: string, object: Term | string | undefined): void => {
    const term = typeof object === 'string' ? (object ? literal(object) : undefined) : object;
    if (term) {
      triples.push({ subject, predicate: iri(predicate), object: term });
    }
  };
  const typed = (subject: SubjectTerm, ...types: string[]): void => types.forEach((type) => add(subject, RDF.type, iri(type)));
  const ranked = (subject: SubjectTerm, index: number): void =>
    add(subject, VIVO.rank, literal(String(index + 1), `${XSD}integer`));
  const yearValue = (subject: IriTerm, year: string): void => {
    typed(subject, VIVO.DateTimeValue);
    add(subject, VIVO.dateTime, literal(`${year}-01-01T00:00:00`, `${XSD}dateTime`));
    add(subject, VIVO.dateTimePrecision, iri(VIVO.yearPrecision));
  };
  const interval = (owner: IriTerm, prefix: string, start: string, end: string): void => {
    if (!start && !end) {
      return;
    }
    const span = node(`${prefix}-interval`);
    add(owner, VIVO.dateTimeInterval, span);
    typed(span, VIVO.DateTimeInterval);
    if (start) {
      add(span, VIVO.start, node(`${prefix}-start`));
      yearValue(node(`${prefix}-start`), start);
    }
    if (end) {
      add(span, VIVO.end, node(`${prefix}-end`));
      yearValue(node(`${prefix}-end`), end);
    }
  };
  const organization = (subject: IriTerm, name: string): void => {
    typed(subject, FOAF.Organization);
    add(subject, RDFS_LABEL, name);
  };

  typed(person, FOAF.Person, ...[PERSON_TYPE_IRIS[profile.personType]].filter((type): type is string => !!type));
  add(person, RDFS_LABEL, sortName(profile));
  add(person, VIVO.overview, profile.overview);
  if (profile.orcid) {
    add(person, VIVO.orcidId, iri(`${ORCID_BASE}${profile.orcid}`));
  }
  profile.keywords.forEach((keyword) => add(person, VIVO.freetextKeyword, keyword));

  const card = node('vcard');
  add(person, OBO.hasContactInfo, card);
  typed(card, VCARD.Individual);
  add(card, OBO.contactInformationFor, person);
  const name = node('vcard-name');
  add(card, VCARD.hasName, name);
  typed(name, VCARD.Name);
  add(name, VCARD.givenName, profile.givenName);
  add(name, VIVO.middleName, profile.middleName);
  add(name, VCARD.familyName, profile.familyName);
  if (profile.preferredTitle) {
    const title = node('vcard-title');
    add(card, VCARD.hasTitle, title);
    typed(title, VCARD.Title);
    add(title, VCARD.title, profile.preferredTitle);
  }
  if (profile.email) {
    const email = node('vcard-email');
    add(card, VCARD.hasEmail, email);
    typed(email, VCARD.Email, VCARD.Work);
    add(email, VCARD.email, profile.email);
  }
  if (profile.phone) {
    const phone = node('vcard-phone');
    add(card, VCARD.hasTelephone, phone);
    typed(phone, VCARD.Telephone);
    add(phone, VCARD.telephone, profile.phone);
  }
  profile.websites.forEach((website, index) => {
    const url = node(`vcard-url-${index + 1}`);
    add(card, VCARD.hasURL, url);
    typed(url, VCARD.URL);
    add(url, VCARD.url, literal(website.url, `${XSD}anyURI`));
    add(url, RDFS_LABEL, website.label);
    ranked(url, index);
  });

  profile.researchAreas.forEach((area, index) => {
    const concept = node(`area-${index + 1}`);
    add(person, VIVO.hasResearchArea, concept);
    typed(concept, SKOS_CONCEPT);
    add(concept, RDFS_LABEL, area);
    add(concept, VIVO.researchAreaOf, person);
  });

  profile.positions.forEach((position, index) => {
    const prefix = `position-${index + 1}`;
    const subject = node(prefix);
    const org = node(`${prefix}-org`);
    add(person, VIVO.relatedBy, subject);
    typed(subject, `${VIVO.ns}${position.type}`, ...(position.type === 'Position' ? [] : [VIVO.Position]));
    add(subject, RDFS_LABEL, position.title);
    add(subject, VIVO.relates, person);
    add(subject, VIVO.relates, org);
    ranked(subject, index);
    interval(subject, prefix, position.start, position.end);
    organization(org, position.organization);
    add(org, VIVO.relatedBy, subject);
  });

  profile.education.forEach((education, index) => {
    const prefix = `education-${index + 1}`;
    const process = node(prefix);
    const org = node(`${prefix}-org`);
    const degree = node(`${prefix}-degree`);
    add(person, OBO.participatesIn, process);
    typed(process, VIVO.EducationalProcess);
    add(process, OBO.hasParticipant, person);
    add(process, OBO.hasParticipant, org);
    add(process, VIVO.majorField, education.field);
    add(process, OBO.hasOutput, degree);
    ranked(process, index);
    interval(process, prefix, '', education.end);
    organization(org, education.organization);
    typed(degree, VIVO.AwardedDegree);
    add(degree, RDFS_LABEL, education.degree);
    add(degree, VIVO.relates, person);
    add(degree, VIVO.assignedBy, org);
  });

  profile.publications.forEach((publication, index) => {
    const authorship = node(`authorship-${index + 1}`);
    const document = node(`publication-${index + 1}`);
    add(person, VIVO.relatedBy, authorship);
    typed(authorship, VIVO.Authorship);
    add(authorship, VIVO.relates, person);
    add(authorship, VIVO.relates, document);
    ranked(authorship, index);
    typed(document, ...new Set([PUBLICATION_TYPES[publication.type], BIBO.Document]));
    add(document, RDFS_LABEL, publication.title);
    add(document, VIVO.relatedBy, authorship);
    add(document, BIBO.doi, publication.doi);
    if (publication.year) {
      const date = node(`publication-${index + 1}-date`);
      add(document, VIVO.dateTimeValue, date);
      yearValue(date, publication.year);
    }
    if (publication.venue) {
      const venue = node(`publication-${index + 1}-venue`);
      add(document, VIVO.hasPublicationVenue, venue);
      typed(venue, VENUE_TYPES[publication.type]);
      add(venue, RDFS_LABEL, publication.venue);
    }
  });

  return [...triples, ...preserved].map((triple) => writeTriple(triple, base)).join('\n') + '\n';
}

/**
 * Writes one triple as a Turtle statement, with IRIs of the profile relative to it.
 *
 * @param triple - the triple
 * @param base - the profile's IRI
 * @returns the statement
 */
function writeTriple(triple: Triple, base: string): string {
  return `${writeTerm(triple.subject, base)} ${writeTerm(triple.predicate, base)} ${writeTerm(triple.object, base)} .`;
}

function writeTerm(term: Term, base: string): string {
  switch (term.kind) {
    case 'iri':
      if (term.value === base) {
        return '<>';
      }
      if (term.value.startsWith(`${base}#`)) {
        return sparqlIri(term.value.substring(base.length));
      }
      return sparqlIri(term.value);
    case 'bnode':
      return `_:${term.value.replace(/[^A-Za-z0-9_-]/g, '_')}`;
    default:
      if (term.language) {
        return `${sparqlLiteral(term.value)}@${term.language}`;
      }
      return sparqlLiteral(term.value, term.datatype && term.datatype !== `${XSD}string` ? term.datatype : undefined);
  }
}

/**
 * Tells whether a statement is maintained by the platform (LDP containment and types, Fedora
 * metadata), so it must not be sent back.
 *
 * @param triple - the statement
 * @returns `true` for server-managed statements
 */
function isServerManaged(triple: Triple): boolean {
  const predicate = triple.predicate.value;
  if (predicate.startsWith(LDP.ns) || predicate.startsWith(FEDORA.ns)) {
    return true;
  }
  return predicate === RDF.type && (triple.object.value.startsWith(LDP.ns) || triple.object.value.startsWith(FEDORA.ns));
}

function isBlank(row: object, ignore: readonly string[] = []): boolean {
  return Object.entries(row).every(([key, value]) => ignore.includes(key) || typeof value !== 'string' || !value.trim());
}

function isWebUrl(value: string): boolean {
  try {
    const url = new URL(value);
    return (url.protocol === 'http:' || url.protocol === 'https:') && !!url.hostname;
  } catch {
    return false;
  }
}

function distinct(values: readonly string[]): string[] {
  const seen = new Set<string>();
  const result: string[] = [];
  for (const value of values.map((v) => v.trim()).filter(Boolean)) {
    const key = value.toLocaleLowerCase();
    if (!seen.has(key)) {
      seen.add(key);
      result.push(value);
    }
  }
  return result;
}

function sorted(values: string[]): string[] {
  return [...values].sort((a, b) => a.localeCompare(b, undefined, { sensitivity: 'base', numeric: true }));
}
