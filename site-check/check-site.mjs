#!/usr/bin/env node
// Checks the static marketing site (docs/) before it is published to GitHub Pages.
// No dependencies: node site-check/check-site.mjs [--site docs] [--root .] [--repo owner/name]
//
// It verifies what an HTML validator cannot: that the page stands alone (no scripts, no external
// resources), carries its metadata, has a sound heading outline, and that every link into this
// repository points at a file - and, for Markdown, a heading - that exists in the checkout.

import { existsSync, readFileSync, appendFileSync } from 'node:fs';
import { join, resolve, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const DEFAULT_REPO = 'xyzzyzzyx/articulorium';

const REQUIRED_META = [
  ['name', 'description'],
  ['name', 'viewport'],
  ['property', 'og:title'],
  ['property', 'og:description'],
  ['property', 'og:url'],
  ['property', 'og:image'],
  ['name', 'twitter:card'],
];

/** Start tags of an HTML document with their attributes and line numbers (comments skipped). */
export function parseTags(html) {
  const tags = [];
  const stripped = html.replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, ' '));
  const tagPattern = /<([a-zA-Z][a-zA-Z0-9-]*)((?:\s+[^\s"'<>\/=]+(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s"'<>=`]+))?)*)\s*\/?>/g;
  const attrPattern = /([^\s"'<>\/=]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'<>=`]+)))?/g;
  for (let m; (m = tagPattern.exec(stripped)); ) {
    const attrs = {};
    for (let a; (a = attrPattern.exec(m[2])); ) {
      attrs[a[1].toLowerCase()] = a[2] ?? a[3] ?? a[4] ?? '';
    }
    tags.push({
      name: m[1].toLowerCase(),
      attrs,
      index: m.index,
      end: m.index + m[0].length,
      line: stripped.slice(0, m.index).split('\n').length,
    });
  }
  return tags;
}

/** The anchors GitHub generates for the headings of a Markdown file. */
export function markdownAnchors(markdown) {
  const anchors = new Set();
  const seen = new Map();
  let fence = null;
  for (const line of markdown.split(/\r?\n/)) {
    const fenceMatch = line.match(/^\s*(```+|~~~+)/);
    if (fenceMatch) {
      if (!fence) fence = fenceMatch[1][0];
      else if (fenceMatch[1][0] === fence) fence = null;
      continue;
    }
    if (fence) continue;
    const heading = line.match(/^#{1,6}\s+(.*?)\s*#*\s*$/);
    if (!heading) continue;
    const text = heading[1]
      .replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1') // links and images: keep the text
      .replace(/[`*]/g, '');
    const slug = text
      .trim()
      .toLowerCase()
      .replace(/[^\p{L}\p{N}\s_-]/gu, '')
      .replace(/\s/g, '-');
    const count = seen.get(slug) ?? 0;
    seen.set(slug, count + 1);
    anchors.add(count ? `${slug}-${count}` : slug);
  }
  return anchors;
}

/**
 * Checks the site in siteDir. repoRoot is the checkout that links to `repo` on GitHub are
 * resolved against. Returns a list of { file, line, message }; empty when the site is sound.
 */
export function checkSite({ siteDir, repoRoot, repo = DEFAULT_REPO }) {
  const problems = [];
  const file = join(siteDir, 'index.html');
  const report = (line, message) => problems.push({ file, line, message });

  if (!existsSync(file)) {
    report(1, 'index.html is missing');
    return problems;
  }
  const html = readFileSync(file, 'utf8');
  const tags = parseTags(html);
  const first = (name) => tags.find((t) => t.name === name);
  const all = (name) => tags.filter((t) => t.name === name);

  // --- document basics and metadata
  if (!/^\s*<!doctype html>/i.test(html)) report(1, 'the document must start with <!doctype html>');
  if (!first('html')?.attrs.lang) report(1, '<html> needs a lang attribute');
  const title = html.match(/<title>([^<]*)<\/title>/i)?.[1].trim();
  if (!title) report(1, '<title> is missing or empty');
  for (const [attr, value] of REQUIRED_META) {
    const meta = all('meta').find((t) => t.attrs[attr] === value);
    if (!meta?.attrs.content?.trim()) report(meta?.line ?? 1, `<meta ${attr}="${value}"> is missing or has no content`);
  }
  const canonical = all('link').find((t) => t.attrs.rel === 'canonical')?.attrs.href;
  if (!canonical) report(1, '<link rel="canonical"> is missing');
  else if (!/^https:\/\/.+\/$/.test(canonical)) report(1, 'the canonical URL must be an https URL ending in "/"');

  // --- stands alone: no scripts, no inline handlers, no external resources
  for (const script of all('script')) {
    if (script.attrs.type !== 'application/ld+json') {
      report(script.line, 'the page must work without JavaScript: remove this <script>');
      continue;
    }
    const close = html.indexOf('</script>', script.end);
    try {
      JSON.parse(html.slice(script.end, close));
    } catch (e) {
      report(script.line, `the JSON-LD block is not valid JSON: ${e.message}`);
    }
  }
  for (const tag of tags) {
    const handler = Object.keys(tag.attrs).find((name) => /^on[a-z]+$/.test(name));
    if (handler) report(tag.line, `inline event handler ${handler} on <${tag.name}>`);
  }
  const resources = [
    ...all('link').filter((t) => /\b(stylesheet|icon|preload)\b/.test(t.attrs.rel ?? '')).map((t) => [t, t.attrs.href]),
    ...['img', 'source', 'video', 'audio', 'iframe', 'embed'].flatMap((n) => all(n)).map((t) => [t, t.attrs.src]),
  ];
  for (const [tag, url] of resources) {
    if (!url) continue;
    if (/^([a-z][a-z0-9+.-]*:)?\/\//i.test(url)) report(tag.line, `<${tag.name}> loads an external resource: ${url}`);
    else if (!url.startsWith('data:') && !existsSync(join(siteDir, url.split(/[?#]/)[0]))) {
      report(tag.line, `<${tag.name}> refers to a file that is not in the site: ${url}`);
    }
  }
  const ogImage = all('meta').find((t) => t.attrs.property === 'og:image')?.attrs.content;
  if (ogImage && canonical) {
    if (!ogImage.startsWith(canonical)) report(1, 'og:image must be an absolute URL below the canonical URL');
    else if (!existsSync(join(siteDir, ogImage.slice(canonical.length)))) {
      report(1, `og:image points at a file that is not in the site: ${ogImage.slice(canonical.length)}`);
    }
  }

  // --- structure and accessibility basics
  const headings = tags.filter((t) => /^h[1-6]$/.test(t.name));
  const h1s = headings.filter((t) => t.name === 'h1');
  if (h1s.length !== 1) report(h1s[1]?.line ?? 1, `expected exactly one <h1>, found ${h1s.length}`);
  let level = 0;
  for (const heading of headings) {
    const next = Number(heading.name[1]);
    if (level && next > level + 1) report(heading.line, `heading level jumps from h${level} to h${next}`);
    level = next;
  }
  if (!first('main')) report(1, 'the page needs a <main> landmark');
  for (const img of all('img')) {
    if (!('alt' in img.attrs)) report(img.line, '<img> needs an alt attribute');
  }
  const ids = new Map();
  for (const tag of tags) {
    if (!tag.attrs.id) continue;
    if (ids.has(tag.attrs.id)) report(tag.line, `duplicate id "${tag.attrs.id}"`);
    ids.set(tag.attrs.id, tag);
  }

  // --- links
  const repoPrefix = `https://github.com/${repo}`;
  const markdownCache = new Map();
  for (const a of all('a')) {
    const href = a.attrs.href;
    if (href === undefined || href === '') {
      report(a.line, '<a> without an href');
      continue;
    }
    if (a.attrs.target === '_blank' && !/\bnoopener\b/.test(a.attrs.rel ?? '')) {
      report(a.line, 'links that open a new tab need rel="noopener"');
    }
    if (href.startsWith('#')) {
      if (href.length > 1 && !ids.has(href.slice(1))) report(a.line, `no element has the id "${href.slice(1)}"`);
      continue;
    }
    if (/^(mailto|tel):/.test(href)) continue;
    if (!/^[a-z][a-z0-9+.-]*:/i.test(href)) {
      if (href.startsWith('/')) report(a.line, `root-relative link breaks under the project path: ${href}`);
      else if (!existsSync(join(siteDir, href.split(/[?#]/)[0]))) report(a.line, `link to a file that is not in the site: ${href}`);
      continue;
    }
    if (!href.startsWith('https://')) {
      report(a.line, `external links must use https: ${href}`);
      continue;
    }
    if (!repoRoot || !href.startsWith(`${repoPrefix}/blob/`) && !href.startsWith(`${repoPrefix}/tree/`)) continue;
    const [, branch, path = '', anchor] =
      href.slice(repoPrefix.length).match(/^\/(?:blob|tree)\/([^/#]+)\/?([^#]*)(?:#(.*))?$/) ?? [];
    if (branch !== 'main') {
      report(a.line, `repository links must point at the main branch: ${href}`);
      continue;
    }
    const target = join(repoRoot, decodeURIComponent(path));
    if (!existsSync(target)) {
      report(a.line, `linked file is not in the repository: ${path}`);
      continue;
    }
    if (anchor && /\.md$/i.test(path)) {
      if (!markdownCache.has(target)) markdownCache.set(target, markdownAnchors(readFileSync(target, 'utf8')));
      if (!markdownCache.get(target).has(anchor)) report(a.line, `${path} has no heading for the anchor #${anchor}`);
    }
  }

  return problems;
}

function main(argv) {
  const option = (name, fallback) => {
    const i = argv.indexOf(`--${name}`);
    return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
  };
  const repoRoot = resolve(option('root', '.'));
  const siteDir = resolve(option('site', join(repoRoot, 'docs')));
  const problems = checkSite({ siteDir, repoRoot, repo: option('repo', DEFAULT_REPO) });
  const shown = (p) => relative(repoRoot, p.file).replaceAll('\\', '/');

  for (const p of problems) {
    console.error(`${shown(p)}:${p.line}: ${p.message}`);
    if (process.env.GITHUB_ACTIONS) console.log(`::error file=${shown(p)},line=${p.line},title=Marketing site::${p.message}`);
  }
  const summary = problems.length
    ? `### Marketing site: ${problems.length} problem(s)\n\n${problems.map((p) => `- \`${shown(p)}:${p.line}\` ${p.message}`).join('\n')}\n`
    : '### Marketing site: all checks passed\n';
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, summary);
  if (!problems.length) console.log(`${relative(repoRoot, siteDir) || '.'}: all checks passed`);
  return problems.length ? 1 : 0;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exitCode = main(process.argv.slice(2));
}
