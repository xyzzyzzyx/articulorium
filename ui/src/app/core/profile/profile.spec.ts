import { Graph } from '../rdf/graph';
import { parseNTriples } from '../rdf/ntriples';
import { iri } from '../rdf/term';
import { FOAF, LDP, OBO, RDF, RDFS_LABEL, VCARD, VIVO } from '../rdf/vocabulary';
import {
  Profile,
  displayName,
  doiOf,
  emptyEducation,
  emptyPosition,
  emptyProfile,
  emptyPublication,
  emptyWebsite,
  isProfile,
  normalizeProfile,
  orcidId,
  profileTurtle,
  readProfile,
  sortName,
  validateProfile,
} from './profile';

const S = 'http://host/platform/profiles/marie-curie';

/**
 * Resolves the Turtle the editor writes against the profile's IRI, as the platform does, giving
 * N-Triples (every statement of the writer is on one line with absolute or relative IRIs).
 */
function stored(turtle: string, subject = S): Graph {
  return new Graph(parseNTriples(turtle.replace(/<>/g, `<${subject}>`).replace(/<#/g, `<${subject}#`)));
}

function curie(): Profile {
  return {
    ...emptyProfile(),
    personType: 'FacultyMember',
    givenName: ' Marie ',
    middleName: 'Skłodowska',
    familyName: 'Curie',
    preferredTitle: 'Professor of Physics',
    email: 'marie@example.org',
    phone: '+33 1 23 45 67 89',
    orcid: 'https://orcid.org/0000-0002-1825-0097',
    overview: 'Pioneer of "radioactivity".\nTwo Nobel prizes.',
    keywords: ['radium', 'Polonium', 'radium'],
    researchAreas: ['Radioactivity', 'Chemistry'],
    websites: [{ url: 'https://example.org/curie', label: 'Lab' }, emptyWebsite()],
    positions: [
      { title: 'Professor', type: 'FacultyPosition', organization: 'University of Paris', start: '1906', end: '1934' },
      { title: 'Director', type: 'FacultyAdministrativePosition', organization: 'Radium Institute', start: '1914', end: '' },
      emptyPosition(),
    ],
    education: [{ degree: 'Doctor of Science', field: 'Physics', organization: 'University of Paris', end: '1903' }],
    publications: [
      { title: 'Recherches sur les substances radioactives', type: 'Book', venue: '', year: '1904', doi: '' },
      {
        title: 'Sur une nouvelle substance radio-active',
        type: 'AcademicArticle',
        venue: 'Comptes rendus',
        year: '1898',
        doi: 'https://doi.org/10.1000/182',
      },
    ],
  };
}

describe('profiles', () => {
  it('names people for running text and for sorting', () => {
    expect(displayName({ givenName: 'Marie', middleName: ' ', familyName: 'Curie' })).toBe('Marie Curie');
    expect(sortName({ givenName: 'Marie', middleName: 'S.', familyName: 'Curie' })).toBe('Curie, Marie S.');
    expect(sortName({ givenName: '', middleName: '', familyName: 'Curie' })).toBe('Curie');
  });

  it('reads ORCID iDs and checks their check digit', () => {
    expect(orcidId('0000-0002-1825-0097')).toBe('0000-0002-1825-0097');
    expect(orcidId('https://orcid.org/0000-0002-1825-0097')).toBe('0000-0002-1825-0097');
    expect(orcidId('000000021694233x')).toBe('0000-0002-1694-233X');
    expect(orcidId('0000-0002-1825-0098')).toBeUndefined();
    expect(orcidId('0000-0002-1825')).toBeUndefined();
  });

  it('reads DOIs with or without a resolver', () => {
    expect(doiOf('10.1000/182')).toBe('10.1000/182');
    expect(doiOf('https://doi.org/10.1000/182')).toBe('10.1000/182');
    expect(doiOf('doi: 10.1000/182')).toBe('10.1000/182');
    expect(doiOf('11.1000/182')).toBeUndefined();
  });

  it('accepts a complete profile and ignores empty rows', () => {
    expect(validateProfile(curie(), 2026)).toEqual([]);
  });

  it('requires a name', () => {
    expect(validateProfile(emptyProfile(), 2026)).toEqual([
      { field: 'givenName', key: 'profiles.validation.required' },
      { field: 'familyName', key: 'profiles.validation.required' },
    ]);
  });

  it('explains every invalid value by field', () => {
    const profile: Profile = {
      ...curie(),
      email: 'marie-at-example',
      phone: 'call me',
      orcid: '0000-0002-1825-0098',
      overview: 'x'.repeat(10001),
      websites: [{ url: 'ftp://example.org', label: '' }, { url: '', label: 'Blog' }],
      positions: [
        { title: '', type: 'Position', organization: '', start: '1934', end: '1906' },
        { title: 'Visitor', type: 'Position', organization: 'CERN', start: '2031', end: '19' },
      ],
      education: [{ degree: '', field: 'Physics', organization: '', end: 'soon' }],
      publications: [{ title: '', type: 'Document', venue: 'Nature', year: '1898', doi: 'nope' }],
    };
    expect(validateProfile(profile, 2026).map((issue) => `${issue.field} ${issue.key}`)).toEqual([
      'email profiles.validation.email',
      'phone profiles.validation.phone',
      'orcid profiles.validation.orcid',
      'overview profiles.validation.tooLong',
      'websites.0.url profiles.validation.url',
      'websites.1.url profiles.validation.required',
      'positions.0.title profiles.validation.required',
      'positions.0.organization profiles.validation.required',
      'positions.0.end profiles.validation.endBeforeStart',
      'positions.1.start profiles.validation.year',
      'positions.1.end profiles.validation.year',
      'education.0.degree profiles.validation.required',
      'education.0.organization profiles.validation.required',
      'education.0.end profiles.validation.year',
      'publications.0.title profiles.validation.required',
      'publications.0.doi profiles.validation.doi',
    ]);
    expect(validateProfile(profile, 2026).find((issue) => issue.field === 'positions.1.start')?.params).toEqual({ max: 2027 });
  });

  it('cleans a profile for saving', () => {
    const clean = normalizeProfile(curie());
    expect(clean.givenName).toBe('Marie');
    expect(clean.orcid).toBe('0000-0002-1825-0097');
    expect(clean.keywords).toEqual(['Polonium', 'radium']);
    expect(clean.researchAreas).toEqual(['Chemistry', 'Radioactivity']);
    expect(clean.websites).toHaveLength(1);
    expect(clean.positions).toHaveLength(2);
    expect(clean.publications[1].doi).toBe('10.1000/182');
  });

  it('writes the person, the vCard and the related individuals with the VIVO ontology', () => {
    const graph = stored(profileTurtle(curie()));
    const person = iri(S);
    expect(isProfile(graph, person)).toBe(true);
    expect(graph.types(person)).toEqual([FOAF.Person, `${VIVO.ns}FacultyMember`]);
    expect(graph.value(person, RDFS_LABEL)).toBe('Curie, Marie Skłodowska');
    expect(graph.value(person, VIVO.orcidId)).toBe('https://orcid.org/0000-0002-1825-0097');
    expect(graph.value(person, VIVO.overview)).toBe('Pioneer of "radioactivity".\nTwo Nobel prizes.');

    const card = iri(`${S}#vcard`);
    expect(graph.value(person, OBO.hasContactInfo)).toBe(card.value);
    expect(graph.value(iri(`${S}#vcard-name`), VCARD.familyName)).toBe('Curie');
    expect(graph.value(iri(`${S}#vcard-email`), VCARD.email)).toBe('marie@example.org');

    const position = iri(`${S}#position-1`);
    expect(graph.types(position)).toEqual([`${VIVO.ns}FacultyPosition`, VIVO.Position]);
    expect(graph.objects(position, VIVO.relates).map((term) => term.value)).toEqual([S, `${S}#position-1-org`]);
    expect(graph.value(iri(`${S}#position-1-start`), VIVO.dateTime)).toBe('1906-01-01T00:00:00');
    expect(graph.value(iri(`${S}#position-1-start`), VIVO.dateTimePrecision)).toBe(VIVO.yearPrecision);
    expect(graph.about(iri(`${S}#position-2-end`))).toEqual([]);

    expect(graph.hasType(iri(`${S}#education-1`), VIVO.EducationalProcess)).toBe(true);
    expect(graph.hasType(iri(`${S}#authorship-2`), VIVO.Authorship)).toBe(true);
    expect(graph.value(iri(`${S}#publication-2`), 'http://purl.org/ontology/bibo/doi')).toBe('10.1000/182');
  });

  it('writes new profiles relative to the resource the platform creates', () => {
    const turtle = profileTurtle(curie());
    expect(turtle).toContain(`<> <${RDF.type}> <${FOAF.Person}> .`);
    expect(turtle).toContain(`<> <${OBO.hasContactInfo}> <#vcard> .`);
    expect(turtle).not.toContain('urn:articulorium');
  });

  it('reads back what it writes', () => {
    const { profile, preserved } = readProfile(stored(profileTurtle(curie())), iri(S));
    expect(profile).toEqual(normalizeProfile(curie()));
    expect(preserved).toEqual([]);
  });

  it('keeps the statements it does not model and drops server-managed ones', () => {
    const extra =
      `<${S}> <http://purl.org/dc/terms/subject> "physics" .\n` +
      `<${S}> <${RDF.type}> <${LDP.basicContainer}> .\n` +
      `<${S}> <http://fedora.info/definitions/v4/repository#created> "2026-11-06T00:00:00Z" .\n` +
      `<${S}> <${LDP.contains}> <${S}/photo.jpg> .\n` +
      `<${S}> <${VIVO.relatedBy}> <${S}#advising-1> .\n` +
      `<${S}#advising-1> <${RDF.type}> <${VIVO.ns}AdvisingRelationship> .\n` +
      `<${S}> <${VIVO.relatedBy}> <http://elsewhere/grant/1> .\n`;
    const graph = new Graph([...stored(profileTurtle(curie())).triples, ...parseNTriples(extra)]);
    const document = readProfile(graph, iri(S));
    expect(document.preserved.map((triple) => triple.predicate.value)).toEqual([
      'http://purl.org/dc/terms/subject',
      VIVO.relatedBy,
      RDF.type,
      VIVO.relatedBy,
    ]);

    const edited = { ...document.profile, positions: [] };
    const turtle = profileTurtle(edited, S, document.preserved);
    expect(turtle).toContain('<> <http://purl.org/dc/terms/subject> "physics" .');
    expect(turtle).toContain(`<> <${VIVO.relatedBy}> <#advising-1> .`);
    expect(turtle).toContain(`<> <${VIVO.relatedBy}> <http://elsewhere/grant/1> .`);
    expect(turtle).not.toContain('position-1');
    expect(turtle).not.toContain(LDP.contains);
    expect(turtle).not.toContain('fedora');
  });

  it('reads profiles of plain persons without VIVO parts', () => {
    const graph = new Graph(parseNTriples(`<${S}> <${RDF.type}> <${FOAF.Person}> .\n<${S}> <${RDFS_LABEL}> "Curie, Marie" .\n`));
    const { profile, preserved } = readProfile(graph, iri(S));
    expect(profile.personType).toBe('Person');
    expect(profile.familyName).toBe('');
    expect(preserved).toEqual([]);
    expect(profileTurtle({ ...profile, givenName: 'Marie', familyName: 'Curie' }, S)).not.toContain('FacultyMember');
  });

  it('starts rows with sensible defaults', () => {
    expect(emptyPosition().type).toBe('FacultyPosition');
    expect(emptyPublication().type).toBe('AcademicArticle');
    expect(emptyEducation()).toEqual({ degree: '', field: '', organization: '', end: '' });
  });
});
