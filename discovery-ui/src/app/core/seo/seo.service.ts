import { DOCUMENT } from '@angular/common';
import { Injectable, inject } from '@angular/core';
import { Meta, Title } from '@angular/platform-browser';

import { ConfigService } from '../config/config.service';
import { LocaleService } from '../i18n/locale.service';
import { JsonLd, serializeJsonLd } from './structured-data';

/** What a page tells search engines and social networks about itself. */
export interface PageMeta {
  /** The page's own title; empty for the home page (the site's name alone). */
  title: string;
  /** Its description (`meta description`, Open Graph, Twitter cards). */
  description?: string;
  /** Its path below the locale (`''`, `search`, `items/<path>`). */
  path: string;
  /** Whether search engines should index it (`false` for search results and errors). */
  index: boolean;
  /** Its preview image; default: the site's default image. */
  image?: string;
  /** Text alternative of the image. */
  imageAlt?: string;
  /** Open Graph type. */
  type?: 'website' | 'article' | 'book' | 'profile';
  /** Structured data (schema.org JSON-LD). */
  structuredData?: JsonLd[];
  /** Whether the page has a version in every locale (alternate language links). */
  alternates?: boolean;
}

const JSON_LD_ID = 'disc-structured-data';

/**
 * Writes the metadata of the current page into the document head: title, description, canonical
 * URL, alternate language versions (`hreflang`), robots directives, Open Graph and Twitter card
 * properties, and JSON-LD structured data. The server renders them into the page, which is what
 * crawlers and link previews read.
 */
@Injectable({ providedIn: 'root' })
export class SeoService {
  private readonly title = inject(Title);

  private readonly meta = inject(Meta);

  private readonly document = inject(DOCUMENT);

  private readonly config = inject(ConfigService);

  private readonly locale = inject(LocaleService);

  /**
   * The canonical URL of a page.
   *
   * @param path - the path below the locale
   * @param locale - the locale, default the current one
   */
  url(path: string, locale = this.locale.locale()): string {
    return `${this.config.config().urls.publicUrl}${locale}/${encodePath(path)}`;
  }

  /**
   * Describes the current page.
   *
   * @param page - the page's metadata
   */
  update(page: PageMeta): void {
    const config = this.config.config();
    const siteName = this.locale.text(config.site.name);
    const title = page.title ? `${page.title} | ${siteName}` : siteName;
    const description = (page.description ?? '').replace(/\s+/g, ' ').trim().slice(0, 300);
    const url = this.url(page.path);
    const image = page.image ?? config.site.defaultImageUrl;
    const robots = config.seo.noindex ? 'noindex, nofollow' : page.index ? config.seo.robots : config.seo.searchRobots;

    this.title.setTitle(title);
    this.name('description', description);
    this.name('robots', robots);
    this.property('og:site_name', siteName);
    this.property('og:title', page.title || siteName);
    this.property('og:description', description);
    this.property('og:url', url);
    this.property('og:type', page.type ?? 'website');
    this.property('og:locale', this.locale.locale().replace('-', '_'));
    this.property('og:image', image);
    this.property('og:image:alt', image ? (page.imageAlt ?? page.title) || siteName : undefined);
    this.name('twitter:card', image ? config.seo.twitterCard : 'summary');
    this.name('twitter:title', page.title || siteName);
    this.name('twitter:description', description);
    this.name('twitter:image', image);
    this.name('twitter:site', config.site.twitterSite);

    this.meta.getTags('property="og:locale:alternate"').forEach((tag) => this.meta.removeTagElement(tag));
    this.links('alternate');
    this.links('canonical');
    this.link('canonical', url);
    if (page.alternates !== false && !config.seo.noindex) {
      for (const locale of config.locales.available) {
        this.link('alternate', this.url(page.path, locale.tag), locale.tag);
        if (locale.tag !== this.locale.locale()) {
          this.meta.addTag({ property: 'og:locale:alternate', content: locale.tag.replace('-', '_') });
        }
      }
      this.link('alternate', this.url(page.path, config.locales.defaultLocale), 'x-default');
    }
    this.structuredData(page.structuredData ?? []);
  }

  private name(name: string, content: string | undefined): void {
    if (content) {
      this.meta.updateTag({ name, content });
    } else {
      this.meta.removeTag(`name="${name}"`);
    }
  }

  private property(property: string, content: string | undefined): void {
    if (content) {
      this.meta.updateTag({ property, content });
    } else {
      this.meta.removeTag(`property="${property}"`);
    }
  }

  private links(rel: string): void {
    this.document.head.querySelectorAll(`link[rel="${rel}"]`).forEach((link) => link.remove());
  }

  private link(rel: string, href: string, hreflang?: string): void {
    const link = this.document.createElement('link');
    link.setAttribute('rel', rel);
    link.setAttribute('href', href);
    if (hreflang) {
      link.setAttribute('hreflang', hreflang);
    }
    this.document.head.appendChild(link);
  }

  private structuredData(data: JsonLd[]): void {
    this.document.getElementById(JSON_LD_ID)?.remove();
    if (data.length === 0) {
      return;
    }
    const script = this.document.createElement('script');
    script.id = JSON_LD_ID;
    script.setAttribute('type', 'application/ld+json');
    script.textContent = serializeJsonLd(data);
    this.document.head.appendChild(script);
  }
}

/**
 * Encodes a path for a URL, segment by segment.
 *
 * @param path - the path, `/` separated
 */
export function encodePath(path: string): string {
  return path
    .split('/')
    .map((segment) => encodeURIComponent(segment))
    .join('/');
}
