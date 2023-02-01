import { ThemeConfig } from '../config/discovery-config';
import { densityClass, themeCss } from './theme-css';

const theme = (overrides: Partial<ThemeConfig>): ThemeConfig => ({
  colorScheme: 'light',
  colors: {},
  darkColors: {},
  typography: {},
  density: 0,
  ...overrides,
});

describe('themeCss', () => {
  it('writes colors, fonts, sizes and shape as tokens', () => {
    const css = themeCss(
      theme({
        colors: { primary: '#1b5e7a', surface: 'white' },
        darkColors: { primary: '#8fd0ee' },
        typography: { brandFamily: '"Source Serif 4", serif', plainFamily: 'Inter, sans-serif', baseSize: '18px' },
        contentWidth: '1400px',
        cornerRadius: 8,
      }),
    );
    expect(css).toContain('color-scheme: light;');
    expect(css).toContain('--mat-sys-primary: light-dark(#1b5e7a, #8fd0ee);');
    expect(css).toContain('--mat-sys-surface: white;');
    expect(css).toContain('--disc-brand-font: "Source Serif 4", serif;');
    expect(css).toContain('--disc-plain-font: Inter, sans-serif;');
    expect(css).toContain('font-size: 18px;');
    expect(css).toContain('--disc-content-width: 1400px;');
    expect(css).toContain('--mat-sys-corner-small: 8px;');
    expect(css).toContain('--mat-sys-corner-large: 16px;');
  });

  it('follows the visitor with the system color scheme', () => {
    expect(themeCss(theme({ colorScheme: 'system' }))).toContain('color-scheme: light dark;');
    expect(themeCss(theme({ colorScheme: 'dark' }))).toContain('color-scheme: dark;');
  });

  it('drops unknown tokens and values that could leave their declaration', () => {
    const css = themeCss(
      theme({
        colors: { primary: 'red; } body { display: none', brand: '#fff', secondary: 'url(x) /* */' },
        typography: { brandFamily: 'x}</style><script>' },
        customCss: '</style><script>alert(1)</script>',
      }),
    );
    expect(css).not.toContain('display: none');
    expect(css).not.toContain('--mat-sys-brand');
    expect(css).not.toContain('--mat-sys-secondary');
    expect(css).not.toContain('<');
  });

  it('appends the deployment CSS', () => {
    expect(themeCss(theme({ customCss: '.disc-hero { padding: 64px; }' }))).toContain('.disc-hero { padding: 64px; }');
  });
});

describe('densityClass', () => {
  it('names compact densities', () => {
    expect(densityClass(0)).toBeNull();
    expect(densityClass(-2)).toBe('disc-density-2');
    expect(densityClass(-9)).toBeNull();
  });
});
