#!/usr/bin/env node
// Checks the discovery layer's translations (npm run i18n:check; Maven runs it before the build).
//
//  * every locale of the shipped manifest
//    (i18n-spring-boot-starter/src/main/resources/articulorium/locales.properties) has
//    src/i18n/<tag>.json, and every file belongs to a shipped locale;
//  * every translation has exactly the keys of en.json (the default texts) and no empty text;
//  * every text uses the same {{placeholders}} as its English text (plural forms may leave the
//    number out, as in "one result");
//  * plural forms ({ "one": ..., "other": ... }) have exactly the categories the locale's plural
//    rules use (Intl.PluralRules), so "1 Datei" / "2 Dateien" and Arabic's six forms are all there.
//
// Exits with status 1 and one line per problem when anything is wrong.
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const directory = join(root, 'src', 'i18n');
const manifestFile = join(root, '..', 'i18n-spring-boot-starter', 'src', 'main', 'resources', 'articulorium', 'locales.properties');
const DEFAULT = 'en';
const CATEGORIES = new Set(['zero', 'one', 'two', 'few', 'many', 'other']);

/** Tags of the shipped manifest, in order. */
export function manifestTags(text) {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith('#'))
    .map((line) => line.substring(0, line.indexOf('=')).trim().replace(/_/g, '-'));
}

const isPlural = (value) =>
  value !== null && typeof value === 'object' && Object.keys(value).length > 0 && Object.keys(value).every((key) => CATEGORIES.has(key));

const placeholders = (text) => [...text.matchAll(/\{\{\s*([\w.]+)\s*\}\}/g)].map((match) => match[1]).sort().join(',');

/** Lists the problems of one translation compared with the default texts. */
export function compare(tag, reference, translation, path = '') {
  const problems = [];
  if (isPlural(reference)) {
    if (!isPlural(translation)) {
      return [`${tag}: ${path} must be plural forms`];
    }
    const wanted = new Intl.PluralRules(tag).resolvedOptions().pluralCategories;
    const present = Object.keys(translation);
    for (const category of wanted) {
      if (!present.includes(category)) {
        problems.push(`${tag}: ${path} lacks the plural form "${category}"`);
      }
    }
    for (const category of present) {
      if (!wanted.includes(category)) {
        problems.push(`${tag}: ${path} has the plural form "${category}", which ${tag} does not use`);
      }
    }
    // a plural form may spell the number out ("one result", Arabic "نتيجتان") but must not use
    // placeholders the English text does not have
    const allowed = new Set(placeholders(reference.other).split(',').filter(Boolean));
    for (const [category, text] of Object.entries(translation)) {
      if (typeof text !== 'string' || !text.trim()) {
        problems.push(`${tag}: ${path}.${category} is empty`);
        continue;
      }
      const unknown = placeholders(text).split(',').filter((name) => name && !allowed.has(name));
      if (unknown.length > 0) {
        problems.push(`${tag}: ${path}.${category} uses unknown placeholders {{${unknown.join(',')}}}`);
      }
    }
    return problems;
  }
  for (const key of Object.keys(reference)) {
    const keyPath = path ? `${path}.${key}` : key;
    const value = reference[key];
    const translated = translation?.[key];
    if (translated === undefined) {
      problems.push(`${tag}: missing ${keyPath}`);
    } else if (typeof value === 'string') {
      problems.push(...textProblems(tag, keyPath, placeholders(value), translated));
    } else if (typeof translated !== 'object' || translated === null) {
      problems.push(`${tag}: ${keyPath} must be a group of texts`);
    } else {
      problems.push(...compare(tag, value, translated, keyPath));
    }
  }
  for (const key of Object.keys(translation ?? {})) {
    if (!(key in reference)) {
      problems.push(`${tag}: unknown key ${path ? `${path}.${key}` : key}`);
    }
  }
  return problems;
}

function textProblems(tag, path, expected, text) {
  if (typeof text !== 'string' || !text.trim()) {
    return [`${tag}: ${path} is empty`];
  }
  const found = placeholders(text);
  return found === expected ? [] : [`${tag}: ${path} uses placeholders {{${found}}} instead of {{${expected}}}`];
}

function main() {
  const problems = [];
  const tags = manifestTags(readFileSync(manifestFile, 'utf8'));
  const files = readdirSync(directory).filter((file) => file.endsWith('.json'));
  const reference = JSON.parse(readFileSync(join(directory, `${DEFAULT}.json`), 'utf8'));
  problems.push(...compare(DEFAULT, reference, reference).filter((problem) => !problem.includes('plural form')));
  for (const tag of tags) {
    if (!files.includes(`${tag}.json`)) {
      problems.push(`${tag}: src/i18n/${tag}.json is missing (the locale is in ${manifestFile})`);
    }
  }
  for (const file of files) {
    const tag = file.replace(/\.json$/, '');
    if (!tags.includes(tag)) {
      problems.push(`${tag}: src/i18n/${file} belongs to no shipped locale (add it to the manifest)`);
      continue;
    }
    let translation;
    try {
      translation = JSON.parse(readFileSync(join(directory, file), 'utf8'));
    } catch (error) {
      problems.push(`${tag}: src/i18n/${file} is not valid JSON (${error.message})`);
      continue;
    }
    if (tag !== DEFAULT) {
      problems.push(...compare(tag, reference, translation));
    }
  }
  if (problems.length > 0) {
    console.error(problems.join('\n'));
    console.error(`\n${problems.length} translation problem(s)`);
    process.exit(1);
  }
  console.log(`Translations complete: ${tags.join(', ')}`);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  main();
}
