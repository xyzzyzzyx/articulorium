// Unit tests of the marketing site checks: node --test .github/scripts/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { checkSite, markdownAnchors, parseTags } from './check-site.mjs';

const REPO = 'example/repo';

const page = ({ head = '', body = '<h1>Title</h1>' } = {}) => `<!doctype html>
<html lang="en">
<head>
  <title>Site</title>
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="description" content="A site.">
  <link rel="canonical" href="https://example.github.io/repo/">
  <link rel="stylesheet" href="styles.css">
  <meta property="og:title" content="Site">
  <meta property="og:description" content="A site.">
  <meta property="og:url" content="https://example.github.io/repo/">
  <meta property="og:image" content="https://example.github.io/repo/og-image.png">
  <meta name="twitter:card" content="summary_large_image">
  ${head}
</head>
<body><main>${body}</main></body>
</html>`;

/** A checkout with docs/ (the site), a README and a LICENSE; returns the problems found. */
function check(html, { omit = [] } = {}) {
  const repoRoot = mkdtempSync(join(tmpdir(), 'site-'));
  const siteDir = join(repoRoot, 'docs');
  mkdirSync(siteDir);
  const files = {
    'docs/index.html': html,
    'docs/styles.css': '',
    'docs/og-image.png': '',
    'README.md': '# Repo\n\n## Storage (OCFL)\n\n```bash\n# not a heading\n```\n',
    'LICENSE': '',
  };
  for (const [name, content] of Object.entries(files)) {
    if (!omit.includes(name)) writeFileSync(join(repoRoot, name), content);
  }
  return checkSite({ siteDir, repoRoot, repo: REPO }).map((p) => p.message);
}

test('a sound page has no problems', () => {
  const body = `<h1 id="top">Title</h1><h2>Section</h2>
    <a href="#top">Top</a>
    <a href="https://github.com/${REPO}">Source</a>
    <a href="https://github.com/${REPO}/blob/main/LICENSE">License</a>
    <a href="https://github.com/${REPO}/blob/main/README.md#storage-ocfl">Storage</a>
    <script type="application/ld+json">{"@type": "SoftwareSourceCode"}</script>`;
  assert.deepEqual(check(page({ body })), []);
});

test('reports a missing index.html', () => {
  assert.deepEqual(check('', { omit: ['docs/index.html'] }), ['index.html is missing']);
});

test('requires the metadata used for search and sharing', () => {
  const html = page().replace(/<meta property="og:image"[^>]*>/, '').replace(/<meta name="description"[^>]*>/, '');
  assert.deepEqual(check(html), [
    '<meta name="description"> is missing or has no content',
    '<meta property="og:image"> is missing or has no content',
  ]);
});

test('rejects scripts, inline handlers and external resources', () => {
  const problems = check(page({
    head: '<link rel="stylesheet" href="https://cdn.example.com/x.css">',
    body: '<h1 onclick="go()">Title</h1><script src="app.js"></script><img src="//cdn.example.com/a.png" alt="">',
  }));
  assert.deepEqual(problems, [
    'the page must work without JavaScript: remove this <script>',
    'inline event handler onclick on <h1>',
    '<link> loads an external resource: https://cdn.example.com/x.css',
    '<img> loads an external resource: //cdn.example.com/a.png',
  ]);
});

test('rejects invalid JSON-LD', () => {
  const [problem] = check(page({ body: '<h1>Title</h1><script type="application/ld+json">{oops}</script>' }));
  assert.match(problem, /^the JSON-LD block is not valid JSON/);
});

test('reports site files that are referenced but missing', () => {
  assert.deepEqual(check(page(), { omit: ['docs/styles.css', 'docs/og-image.png'] }), [
    '<link> refers to a file that is not in the site: styles.css',
    'og:image points at a file that is not in the site: og-image.png',
  ]);
});

test('checks the heading outline, landmarks, alt texts and ids', () => {
  const html = page({ body: '<h1 id="a">One</h1><h1>Two</h1><h3 id="a">Deep</h3><img src="og-image.png">' })
    .replace(/<\/?main>/g, '');
  assert.deepEqual(check(html), [
    'expected exactly one <h1>, found 2',
    'heading level jumps from h1 to h3',
    'the page needs a <main> landmark',
    '<img> needs an alt attribute',
    'duplicate id "a"',
  ]);
});

test('checks fragment, relative and external links', () => {
  const body = `<h1>Title</h1>
    <a href="#nowhere">Gone</a>
    <a href="/swagger-ui.html">API</a>
    <a href="missing.html">Missing</a>
    <a href="http://example.com/">Plain http</a>
    <a href="https://example.com/" target="_blank">New tab</a>
    <a>No href</a>`;
  assert.deepEqual(check(page({ body })), [
    'no element has the id "nowhere"',
    'root-relative link breaks under the project path: /swagger-ui.html',
    'link to a file that is not in the site: missing.html',
    'external links must use https: http://example.com/',
    'links that open a new tab need rel="noopener"',
    '<a> without an href',
  ]);
});

test('resolves links into the repository against the checkout', () => {
  const body = `<h1>Title</h1>
    <a href="https://github.com/${REPO}/blob/main/docs/GONE.md">Gone</a>
    <a href="https://github.com/${REPO}/blob/main/README.md#no-such-section">Section</a>
    <a href="https://github.com/${REPO}/blob/main/README.md#not-a-heading">Fenced</a>
    <a href="https://github.com/${REPO}/blob/develop/README.md">Branch</a>
    <a href="https://github.com/other/project/blob/main/NOPE.md">Elsewhere</a>`;
  assert.deepEqual(check(page({ body })), [
    'linked file is not in the repository: docs/GONE.md',
    'README.md has no heading for the anchor #no-such-section',
    'README.md has no heading for the anchor #not-a-heading',
    `repository links must point at the main branch: https://github.com/${REPO}/blob/develop/README.md`,
  ]);
});

test('markdownAnchors follows GitHub heading anchors', () => {
  const anchors = markdownAnchors([
    '# Articulorium',
    '## Access control (WebAC)',
    '## Notifications (COAR Notify)',
    '### `fcr:versions` and [links](https://example.com)',
    '## OAI-PMH',
    '## OAI-PMH',
    '~~~',
    '## fenced',
    '~~~',
  ].join('\n'));
  assert.deepEqual([...anchors], [
    'articulorium',
    'access-control-webac',
    'notifications-coar-notify',
    'fcrversions-and-links',
    'oai-pmh',
    'oai-pmh-1',
  ]);
});

test('parseTags reads attributes and line numbers and skips comments', () => {
  const tags = parseTags('<p class="a b">\n<!-- <img src="x"> -->\n<a href=\'#x\' hidden>x</a>');
  assert.deepEqual(tags.map(({ name, attrs, line }) => ({ name, attrs, line })), [
    { name: 'p', attrs: { class: 'a b' }, line: 1 },
    { name: 'a', attrs: { href: '#x', hidden: '' }, line: 3 },
  ]);
});
