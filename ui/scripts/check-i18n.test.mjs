// Tests of the translation check (run by "npm run i18n:check" before the check itself).
import assert from 'node:assert/strict';
import { test } from 'node:test';

import { compare, manifestTags } from './check-i18n.mjs';

const EN = {
  nav: { browse: 'Browse', language: 'Language: {{language}}' },
  results: { one: '{{formatted}} result', other: '{{formatted}} results' },
};

test('reads the shipped locales from the manifest', () => {
  assert.deepEqual(manifestTags('# comment\nen = English\n\npt_BR = Português (Brasil)\nar = العربية, rtl\n'), [
    'en',
    'pt-BR',
    'ar',
  ]);
});

test('accepts a complete translation', () => {
  const de = { nav: { browse: 'Durchsuchen', language: 'Sprache: {{language}}' }, results: { one: '{{formatted}} Treffer', other: '{{formatted}} Treffer' } };
  assert.deepEqual(compare('de', EN, de), []);
});

test('reports missing, unknown and empty keys and wrong placeholders', () => {
  const broken = { nav: { language: 'Sprache: {{lang}}', extra: 'x' }, results: { one: ' ', other: '{{formatted}}' } };
  assert.deepEqual(compare('de', EN, broken).sort(), [
    'de: missing nav.browse',
    'de: nav.language uses placeholders {{lang}} instead of {{language}}',
    'de: results.one is empty',
    'de: unknown key nav.extra',
  ]);
});

test('requires the plural forms of the locale', () => {
  assert.deepEqual(compare('ja', EN, { nav: EN.nav, results: { other: '{{formatted}} 件' } }), []);
  assert.deepEqual(compare('ja', EN, { nav: EN.nav, results: { one: '1 件', other: '{{formatted}} 件' } }), [
    'ja: results has the plural form "one", which ja does not use',
  ]);
  const polish = compare('pl', EN, { nav: EN.nav, results: { one: 'jeden wynik', other: '{{formatted}} wyniku' } });
  assert.deepEqual(polish.sort(), ['pl: results lacks the plural form "few"', 'pl: results lacks the plural form "many"']);
  assert.deepEqual(compare('de', EN, { nav: EN.nav, results: { one: '{{count}} Treffer', other: '{{formatted}}' } }), [
    'de: results.one uses unknown placeholders {{count}}',
  ]);
});
