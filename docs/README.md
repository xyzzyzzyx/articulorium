# Articulorium marketing site

The public page at <https://xyzzyzzyx.github.io/articulorium/>. It is a single static page with no
build step, no JavaScript and no dependency on the Angular applications or the backend services.

| File            | What                                                                 |
|-----------------|----------------------------------------------------------------------|
| `index.html`    | The page                                                             |
| `styles.css`    | Its styles (light and dark, responsive, system fonts only)           |
| `favicon.svg`   | Browser tab icon                                                     |
| `og-image.png`  | 1200 x 630 image shown when the page is shared                       |
| `README.md`     | This file; it is not published                                       |

## Enabling GitHub Pages (once)

In the repository on GitHub: **Settings > Pages > Build and deployment > Source: GitHub Actions**.

Nothing else is needed. The first deployment creates the `github-pages` environment, which by
default only accepts deployments from the default branch. Until the setting is made, the `deploy`
job of the workflow fails with an error that points at the Pages settings; pull request checks
are unaffected.

## How it is published

[.github/workflows/pages.yml](../.github/workflows/pages.yml) runs when `docs/`, the checks or the
workflow change:

* **Pull requests**: the `validate` job checks the page and uploads it as the `site-preview`
  artifact. Nothing is deployed, and the job has read-only permissions.
* **Pushes to `main`** (a merged pull request) and manual runs on `main`: the same checks, then the
  `deploy` job publishes the page. It is the only job with `pages: write` and `id-token: write`.

A failed check stops the deployment; the site that is already published stays as it is.

## Previewing a change

Locally, open `docs/index.html` in a browser, or serve the directory:

```bash
python3 -m http.server 8080 --directory docs   # http://localhost:8080/
```

For a pull request, open its **pages** workflow run, download the `site-preview` artifact, unpack
it and open `index.html`.

## Checking a change

The same checks the workflow runs (Node.js 22 or later, no install step):

```bash
node --test site-check/check-site.test.mjs         # unit tests of the checks
node site-check/check-site.mjs                      # metadata, structure, links
npx --yes html-validate@11.21.1 --config site-check/htmlvalidate.json docs/index.html
```

[check-site.mjs](../site-check/check-site.mjs) fails when the page contains a script or loads
an external resource, lacks its search and sharing metadata, has a broken heading outline, or
links to something that does not exist: an `id` on the page, a file in `docs/`, or a file or
README section in this repository. On GitHub the problems are annotated on the lines that cause
them and listed in the run summary.

## Updating the page

* Describe only what the repository documents, and link each claim to the README section or
  module README that backs it. Link to files as
  `https://github.com/xyzzyzzyx/articulorium/blob/main/<path>#<heading>` so the checks can verify
  them. Renaming a README heading that the page links to fails the checks on that pull request.
* Addresses on `localhost` are shown as code, not links: they only work on a running stack.
* Keep the page working without JavaScript and without requests to other hosts (fonts, analytics,
  CDNs).
* If the title or description changes, change the Open Graph tags too. `og-image.png` must stay
  1200 x 630.
