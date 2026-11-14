import {
  AngularNodeAppEngine,
  createNodeRequestHandler,
  isMainModule,
  writeResponseToNodeResponse,
} from '@angular/ssr/node';
import express from 'express';
import { join } from 'node:path';

import { discoveryApiUrl } from './server/api-url';
import { SitemapResource, SitemapSettings, robotsTxt, sitemapXml } from './server/sitemap';

/**
 * The Node.js server of the discovery layer: renders every page on the server (Angular SSR),
 * serves the application's static files, robots.txt and the sitemap. The gateway forwards
 * `/discover/**` here unchanged.
 *
 * Environment:
 *  - `PORT` (4000)
 *  - `DISCOVERY_API_URL`: the discovery API from this server (`http://gateway:9000/discover/api/`)
 *  - `NG_ALLOWED_HOSTS`: host names requests may be addressed to (`Host`, `X-Forwarded-Host`)
 *  - `NG_TRUST_PROXY_HEADERS`: proxy headers to trust; default the gateway's `X-Forwarded-*`
 */
const BASE = '/discover';
const browserDistFolder = join(import.meta.dirname, '../browser');

const app = express();
app.disable('x-powered-by');
app.set('trust proxy', true);

const angularApp = new AngularNodeAppEngine({
  trustProxyHeaders: process.env['NG_TRUST_PROXY_HEADERS']
    ? undefined
    : ['x-forwarded-host', 'x-forwarded-proto', 'x-forwarded-port', 'x-forwarded-prefix'],
});

app.use((_req, res, next) => {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin');
  res.setHeader('X-Frame-Options', 'SAMEORIGIN');
  next();
});

/** Liveness of this server (not routed by the gateway). */
app.get('/healthz', (_req, res) => {
  res.type('text/plain').send('ok');
});

interface Cached<T> {
  value: T;
  until: number;
}

let settingsCache: Cached<SitemapSettings> | undefined;
let sitemapCache: Cached<string> | undefined;

async function fetchJson<T>(path: string): Promise<T> {
  const response = await fetch(discoveryApiUrl() + path, { headers: { Accept: 'application/json' } });
  if (!response.ok) {
    throw new Error(`The discovery API answered ${response.status} for ${path}`);
  }
  return (await response.json()) as T;
}

async function settings(): Promise<SitemapSettings> {
  if (settingsCache && settingsCache.until > Date.now()) {
    return settingsCache.value;
  }
  const config = await fetchJson<{
    urls: { publicUrl: string };
    locales: { defaultLocale: string; available: { tag: string }[] };
    seo: { sitemap: boolean; noindex: boolean };
    layout?: { collections?: { enabled: boolean } };
  }>('config');
  const value: SitemapSettings = {
    publicUrl: config.urls.publicUrl,
    defaultLocale: config.locales.defaultLocale,
    locales: config.locales.available.map((locale) => locale.tag),
    sitemap: config.seo.sitemap,
    noindex: config.seo.noindex,
    collections: config.layout?.collections?.enabled ?? false,
  };
  settingsCache = { value, until: Date.now() + 60_000 };
  return value;
}

app.get(`${BASE}/robots.txt`, async (_req, res) => {
  try {
    res.type('text/plain').set('Cache-Control', 'public, max-age=3600').send(robotsTxt(await settings()));
  } catch (error) {
    console.error('robots.txt:', error);
    res.status(503).set('Cache-Control', 'no-store').type('text/plain').send('User-agent: *\nDisallow:\n');
  }
});

app.get(`${BASE}/sitemap.xml`, async (_req, res) => {
  try {
    const current = await settings();
    if (!current.sitemap || current.noindex) {
      res.status(404).type('text/plain').send('No sitemap');
      return;
    }
    if (!sitemapCache || sitemapCache.until <= Date.now()) {
      const resources = await fetchJson<SitemapResource[]>('sitemap');
      sitemapCache = { value: sitemapXml(current, resources), until: Date.now() + 600_000 };
    }
    res.type('application/xml').set('Cache-Control', 'public, max-age=600').send(sitemapCache.value);
  } catch (error) {
    console.error('sitemap.xml:', error);
    res.status(503).set('Cache-Control', 'no-store').type('text/plain').send('The sitemap is unavailable');
  }
});

/** The application's files: hashed names, cached for a year. */
app.use(
  BASE,
  express.static(browserDistFolder, {
    maxAge: '1y',
    index: false,
    redirect: false,
  }),
);

/** Every other request below the base renders the application. */
app.use((req, res, next) => {
  angularApp
    .handle(req)
    .then((response) => {
      if (!response) {
        next();
        return;
      }
      if (!response.headers.has('cache-control')) {
        // public, anonymous pages: short-lived, so changes to the collections show up quickly
        response.headers.set('Cache-Control', 'public, max-age=60');
      }
      return writeResponseToNodeResponse(response, res);
    })
    .catch(next);
});

if (isMainModule(import.meta.url) || process.env['pm_id']) {
  const port = process.env['PORT'] || 4000;
  app.listen(port, (error) => {
    if (error) {
      throw error;
    }
    console.log(`Articulorium discovery listening on http://localhost:${port}${BASE}/ (API: ${discoveryApiUrl()})`);
  });
}

/** Request handler of the Angular CLI's development server and build. */
export const reqHandler = createNodeRequestHandler(app);
