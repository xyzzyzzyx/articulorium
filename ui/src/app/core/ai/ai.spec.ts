import { HttpErrorResponse } from '@angular/common/http';

import { emptyProfile } from '../profile/profile';

import {
  MetadataStatement,
  aiErrorMessage,
  aiReason,
  completeTopology,
  insertStatementsUpdate,
  mergeProfileSuggestion,
  percent,
  sparqlObject,
  suggestionHasMaterial,
  topicsUpdate,
} from './ai';

const S = 'http://host/platform/books/cover.png';

const literal = (value: string, extra: Partial<MetadataStatement> = {}): MetadataStatement => ({
  predicate: 'http://purl.org/dc/terms/title',
  value,
  kind: 'LITERAL',
  origin: 'MODEL',
  ...extra,
});

describe('AI helpers', () => {
  describe('sparqlObject', () => {
    it('writes IRIs, language-tagged and typed literals', () => {
      expect(sparqlObject(literal('http://example.org/x', { kind: 'IRI' }))).toBe('<http://example.org/x>');
      expect(sparqlObject(literal('Moby Dick', { language: 'en' }))).toBe('"Moby Dick"@en');
      expect(sparqlObject(literal('1920', { datatype: 'http://www.w3.org/2001/XMLSchema#gYear' }))).toBe(
        '"1920"^^<http://www.w3.org/2001/XMLSchema#gYear>',
      );
    });

    it('drops a language tag that is not a BCP 47 tag rather than writing it into the update', () => {
      expect(sparqlObject(literal('x', { language: 'en" . <a> <b> <c' }))).toBe('"x"');
    });

    it('escapes quotes and new lines in values', () => {
      expect(sparqlObject(literal('say "hi"\nthere'))).toBe('"say \\"hi\\"\\nthere"');
    });
  });

  describe('insertStatementsUpdate', () => {
    it('adds every statement about the subject', () => {
      const update = insertStatementsUpdate(S, [literal('Cover', { language: 'en' }), literal('A whale', { predicate: 'http://purl.org/dc/terms/description' })]);
      expect(update).toBe(
        `INSERT DATA { <${S}> <http://purl.org/dc/terms/title> "Cover"@en . <${S}> <http://purl.org/dc/terms/description> "A whale" . }`,
      );
    });

    it('is empty when nothing is kept', () => {
      expect(insertStatementsUpdate(S, [])).toBe('');
    });
  });

  describe('topicsUpdate', () => {
    const C = 'http://host/platform/communities/lib';

    it('adds topics as subjects and replaces the abstract with the summary', () => {
      const update = topicsUpdate(C, ['Whaling', 'Sea'], 'en', ' Books about the sea. ');
      expect(update).toBe(
        `DELETE WHERE { <${C}> <http://purl.org/dc/terms/abstract> ?o } ;\n` +
          `INSERT DATA { <${C}> <http://purl.org/dc/terms/subject> "Whaling"@en . ` +
          `<${C}> <http://purl.org/dc/terms/subject> "Sea"@en . ` +
          `<${C}> <http://purl.org/dc/terms/abstract> "Books about the sea."@en . }`,
      );
    });

    it('keeps an existing abstract when no summary is kept', () => {
      expect(topicsUpdate(C, ['Sea'], null)).toBe(`INSERT DATA { <${C}> <http://purl.org/dc/terms/subject> "Sea" . }`);
    });

    it('is empty when nothing is kept', () => {
      expect(topicsUpdate(C, [], 'en', '  ')).toBe('');
    });
  });

  describe('completeTopology', () => {
    it('fills what a model left out with the topology API defaults, never built in', () => {
      const topology = completeTopology({
        id: 'letters',
        builtIn: true,
        root: {
          key: 'letter',
          pcdmType: 'Collection',
          files: [{ use: 'original' }],
          children: [{ node: { key: 'page', label: 'Page', pcdmType: 'Thing' } }, { min: 1 }],
        },
      });
      expect(topology.builtIn).toBe(false);
      expect(topology.name).toBe('');
      expect(topology.root.pcdmType).toBe('Collection');
      expect(topology.root.files).toEqual([{ use: 'original', label: '', mediaTypes: [] }]);
      // children without a node are dropped, unknown PCDM types become objects
      expect(topology.root.children).toHaveLength(1);
      expect(topology.root.children[0]).toMatchObject({ min: 0, max: -1, initial: 0, slugPattern: null });
      expect(topology.root.children[0].node.pcdmType).toBe('Object');
      expect(topology.root.children[0].node.metadata).toEqual([]);
    });
  });

  describe('aiReason', () => {
    const problem = (status: number, error: unknown) => new HttpErrorResponse({ status, error });

    it('reads the reason of the agent problem documents', () => {
      expect(aiReason(problem(503, { reason: 'MODEL_UNAVAILABLE', detail: 'no model' }))).toBe('MODEL_UNAVAILABLE');
      expect(aiReason(new Error('wrapped', { cause: problem(429, { reason: 'BUSY', detail: 'busy' }) }))).toBe('BUSY');
    });

    it('tells an agent that is not there from other failures', () => {
      expect(aiReason(problem(0, null))).toBe('UNREACHABLE');
      expect(aiReason(problem(404, '<html>'))).toBe('UNREACHABLE');
      expect(aiReason(problem(502, { detail: 'the gateway says so' }))).toBeUndefined();
      expect(aiReason(problem(500, { reason: 'SOMETHING_ELSE' }))).toBeUndefined();
      expect(aiReason('not an error')).toBeUndefined();
    });

    it('explains known reasons and falls back otherwise', () => {
      const t = (key: string) => `[${key}]`;
      expect(aiErrorMessage(problem(429, { reason: 'BUSY', detail: 'busy' }), t, () => 'fallback')).toBe('[ai.reason.BUSY]');
      expect(aiErrorMessage(problem(500, {}), t, () => 'fallback')).toBe('fallback');
    });
  });

  describe('profile suggestions', () => {
    const profile = { ...emptyProfile(), givenName: 'Marie', overview: 'Physicist.', keywords: ['radium'], researchAreas: ['Radioactivity'] };

    it('replaces the overview and adds research areas and keywords, without repeats', () => {
      const merged = mergeProfileSuggestion(profile, {
        overview: '  Studies radioactivity.  ',
        researchAreas: ['Radiochemistry', 'Radioactivity'],
        keywords: ['polonium'],
      });
      expect(merged.overview).toBe('Studies radioactivity.');
      expect(merged.researchAreas).toEqual(['Radioactivity', 'Radiochemistry']);
      expect(merged.keywords).toEqual(['polonium', 'radium']);
      expect(merged.givenName).toBe('Marie');
    });

    it('keeps the overview when none is kept', () => {
      expect(mergeProfileSuggestion(profile, { researchAreas: [], keywords: [] }).overview).toBe('Physicist.');
      expect(mergeProfileSuggestion(profile, { overview: ' ', researchAreas: [], keywords: [] }).overview).toBe('Physicist.');
    });

    it('tells whether the profile had anything to draw from', () => {
      const suggestion = { path: 'p', overview: '', hasOverview: false, researchAreas: [], keywords: [] };
      expect(suggestionHasMaterial({ ...suggestion, basedOn: { positions: 0, education: 0, publications: 0 } })).toBe(false);
      expect(suggestionHasMaterial({ ...suggestion, basedOn: { positions: 0, education: 1, publications: 0 } })).toBe(true);
    });
  });

  it('shows confidences as bounded percentages', () => {
    expect(percent(0.876)).toBe(88);
    expect(percent(1.5)).toBe(100);
    expect(percent(-1)).toBe(0);
    expect(percent(null)).toBe(0);
  });
});
