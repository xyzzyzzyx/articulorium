import { DOCUMENT } from '@angular/common';
import { Injectable, inject } from '@angular/core';

import { DiscoveryConfig } from '../config/discovery-config';
import { densityClass, themeCss } from './theme-css';

const STYLE_ID = 'disc-theme';
const FONTS_ID = 'disc-theme-fonts';
const STYLESHEET_ID = 'disc-theme-stylesheet';

/**
 * Applies the deployment's theme to the document: its tokens and CSS in a style element, the
 * fonts' and the deployment's style sheets, the density class and the favicon. The server renders
 * them into the page, so the first frame is already themed; in browsers the same elements are
 * found again and updated instead of duplicated.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly document = inject(DOCUMENT);

  /**
   * Applies a deployment's theme.
   *
   * @param config - the configuration
   */
  apply(config: DiscoveryConfig): void {
    const head = this.document.head;
    const fonts = config.theme.typography?.stylesheetUrl;
    this.link(FONTS_ID, fonts, head.querySelector('link[rel="stylesheet"]'));
    const style = this.element(STYLE_ID, 'style');
    style.textContent = themeCss(config.theme);
    head.appendChild(style);
    // the deployment's style sheet comes last, so it overrides the built-in styles and tokens
    this.link(STYLESHEET_ID, config.theme.stylesheetUrl, null);
    const html = this.document.documentElement;
    for (const name of Array.from(html.classList).filter((c) => c.startsWith('disc-density-'))) {
      html.classList.remove(name);
    }
    const density = densityClass(config.theme.density);
    if (density) {
      html.classList.add(density);
    }
    if (config.site.faviconUrl) {
      const icon = head.querySelector<HTMLLinkElement>('link[rel="icon"]') ?? this.document.createElement('link');
      icon.rel = 'icon';
      icon.removeAttribute('type');
      icon.href = config.site.faviconUrl;
      head.appendChild(icon);
    }
  }

  private element<K extends 'style' | 'link'>(id: string, tag: K): HTMLElementTagNameMap[K] {
    const existing = this.document.getElementById(id);
    if (existing) {
      return existing as HTMLElementTagNameMap[K];
    }
    const created = this.document.createElement(tag);
    created.id = id;
    return created;
  }

  private link(id: string, href: string | undefined, before: Element | null): void {
    const existing = this.document.getElementById(id);
    if (!href) {
      existing?.remove();
      return;
    }
    const link = this.element(id, 'link');
    link.rel = 'stylesheet';
    link.href = href;
    if (!link.parentNode) {
      if (before) {
        this.document.head.insertBefore(link, before);
      } else {
        this.document.head.appendChild(link);
      }
    } else if (!before) {
      this.document.head.appendChild(link);
    }
  }
}
