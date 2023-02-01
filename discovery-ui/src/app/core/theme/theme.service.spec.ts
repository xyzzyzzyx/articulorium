import { DOCUMENT } from '@angular/common';
import { TestBed } from '@angular/core/testing';

import { testConfig } from '../../../testing/config';
import { testProviders } from '../../../testing/providers';
import { ThemeService } from './theme.service';

describe('ThemeService', () => {
  it('writes the theme into the document once, however often it is applied', () => {
    TestBed.configureTestingModule({ providers: testProviders() });
    const document = TestBed.inject(DOCUMENT);
    const config = testConfig();
    config.theme.density = -2;
    config.theme.stylesheetUrl = 'http://x/branding.css';
    config.theme.typography.stylesheetUrl = 'http://fonts.example/css';
    config.site.faviconUrl = 'http://x/favicon.svg';
    const theme = TestBed.inject(ThemeService);

    theme.apply(config);
    theme.apply(config);

    expect(document.querySelectorAll('#disc-theme')).toHaveLength(1);
    expect(document.getElementById('disc-theme')?.textContent).toContain('--mat-sys-primary: #1b5e7a;');
    expect(document.getElementById('disc-theme-stylesheet')?.getAttribute('href')).toBe('http://x/branding.css');
    expect(document.getElementById('disc-theme-fonts')?.getAttribute('href')).toBe('http://fonts.example/css');
    expect(document.documentElement.classList.contains('disc-density-2')).toBe(true);
    expect(document.head.querySelector('link[rel="icon"]')?.getAttribute('href')).toBe('http://x/favicon.svg');

    config.theme.density = 0;
    delete config.theme.stylesheetUrl;
    theme.apply(config);
    expect(document.documentElement.classList.contains('disc-density-2')).toBe(false);
    expect(document.getElementById('disc-theme-stylesheet')).toBeNull();
  });
});
