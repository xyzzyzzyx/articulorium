import { ThemeConfig } from '../config/discovery-config';

/** Material 3 system color tokens a theme may set (`--mat-sys-<token>`). */
export const COLOR_TOKENS = new Set([
  'primary', 'on-primary', 'primary-container', 'on-primary-container',
  'secondary', 'on-secondary', 'secondary-container', 'on-secondary-container',
  'tertiary', 'on-tertiary', 'tertiary-container', 'on-tertiary-container',
  'error', 'on-error', 'error-container', 'on-error-container',
  'background', 'on-background', 'surface', 'on-surface', 'surface-variant', 'on-surface-variant',
  'surface-container-lowest', 'surface-container-low', 'surface-container', 'surface-container-high',
  'surface-container-highest', 'surface-bright', 'surface-dim', 'surface-tint',
  'outline', 'outline-variant', 'inverse-surface', 'inverse-on-surface', 'inverse-primary', 'shadow', 'scrim',
]);

/** A value that cannot leave a declaration: no `;`, braces, `<` or comments. */
const SAFE_VALUE = /^[^;{}<>\\]*$/;

const safe = (value: string | undefined | null): value is string =>
  typeof value === 'string' && value.trim().length > 0 && SAFE_VALUE.test(value) && !value.includes('/*');

/**
 * The CSS of a deployment's theme: Material 3 system tokens (colors, shape), the fonts and sizes
 * the built-in styles read (`--disc-*`), the color scheme, and the deployment's own CSS.
 *
 * The discovery service validates every value at start-up; values that could still leave their
 * declaration are dropped here as well, since this CSS is written into the page.
 *
 * @param theme - the theme settings
 * @returns the style sheet
 */
export function themeCss(theme: ThemeConfig): string {
  const declarations: string[] = [];
  const scheme = theme.colorScheme === 'system' ? 'light dark' : theme.colorScheme === 'dark' ? 'dark' : 'light';
  declarations.push(`color-scheme: ${scheme};`);
  const tokens = new Set([...Object.keys(theme.colors ?? {}), ...Object.keys(theme.darkColors ?? {})]);
  for (const token of tokens) {
    if (!COLOR_TOKENS.has(token)) {
      continue;
    }
    const light = theme.colors?.[token];
    const dark = theme.darkColors?.[token];
    if (safe(light) && safe(dark)) {
      declarations.push(`--mat-sys-${token}: light-dark(${light.trim()}, ${dark.trim()});`);
    } else if (safe(light) || safe(dark)) {
      declarations.push(`--mat-sys-${token}: ${(safe(light) ? light : dark)!.trim()};`);
    }
  }
  if (safe(theme.typography?.brandFamily)) {
    declarations.push(`--disc-brand-font: ${theme.typography.brandFamily!.trim()};`);
  }
  if (safe(theme.typography?.plainFamily)) {
    declarations.push(`--disc-plain-font: ${theme.typography.plainFamily!.trim()};`);
  }
  if (safe(theme.typography?.baseSize)) {
    declarations.push(`font-size: ${theme.typography.baseSize!.trim()};`);
  }
  if (safe(theme.contentWidth)) {
    declarations.push(`--disc-content-width: ${theme.contentWidth!.trim()};`);
  }
  if (typeof theme.cornerRadius === 'number' && theme.cornerRadius >= 0 && theme.cornerRadius <= 64) {
    const r = theme.cornerRadius;
    declarations.push(
      `--mat-sys-corner-extra-small: ${Math.round(r / 2)}px;`,
      `--mat-sys-corner-small: ${r}px;`,
      `--mat-sys-corner-medium: ${Math.round(r * 1.5)}px;`,
      `--mat-sys-corner-large: ${r * 2}px;`,
      `--mat-sys-corner-extra-large: ${r * 3}px;`,
    );
  }
  let css = `html {\n  ${declarations.join('\n  ')}\n}\n`;
  if (theme.customCss && !theme.customCss.includes('<')) {
    css += `\n/* deployment styles (articulorium.discovery.theme.custom-css) */\n${theme.customCss}\n`;
  }
  return css;
}

/**
 * The density class of the document element (`disc-density-1` … `disc-density-4`).
 *
 * @param density - the Material density scale, 0 to -4
 * @returns the class, `null` for the default density
 */
export function densityClass(density: number): string | null {
  const level = Math.round(-density);
  return level >= 1 && level <= 4 ? `disc-density-${level}` : null;
}
