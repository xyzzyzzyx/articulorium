import { miradorConfig, miradorLanguage } from './mirador-config';
import { pdfPlugin, pdfResource } from './mirador-pdf-plugin';

describe('Mirador configuration', () => {
  const settings = { enabled: true, height: '70vh', sideBarOpen: true, thumbnailNavigation: 'far-right' as const, allowFullscreen: false, pdf: true };

  it('presents one resource in the interface language and colors', () => {
    const config = miradorConfig({ id: 'v', manifest: 'http://x/iiif/a', collection: false, settings, locale: 'ko', dark: true, primary: '#123456' });

    expect(config).toMatchObject({
      id: 'v',
      language: 'kr',
      selectedTheme: 'dark',
      windows: [{ manifestId: 'http://x/iiif/a', thumbnailNavigationPosition: 'far-right', sideBarOpen: true }],
      window: { allowClose: false, allowMaximize: false, allowFullscreen: false },
      workspace: { allowNewWindows: false },
      workspaceControlPanel: { enabled: false },
    });
    expect((config['themes'] as Record<string, { palette: Record<string, unknown> }>)['dark'].palette['primary']).toEqual({ main: '#123456' });
  });

  it('opens collections with their picker', () => {
    const config = miradorConfig({ id: 'v', manifest: 'http://x/iiif/c', collection: true, settings, locale: 'en', dark: false });
    expect((config['windows'] as Record<string, unknown>[])[0]['collectionDialogOn']).toBe(true);
  });

  it('maps locales to Mirador languages', () => {
    expect(miradorLanguage('pt-BR')).toBe('pt-BR');
    expect(miradorLanguage('de-AT')).toBe('de');
    expect(miradorLanguage('es')).toBe('en');
  });
});

describe('Mirador PDF plugin', () => {
  const pdf = { id: 'http://x/a.pdf', getFormat: () => 'application/pdf' };
  const text = { id: 'http://x/a.txt', getFormat: () => 'text/plain' };
  const target = () => null;

  it('finds painted PDFs', () => {
    expect(pdfResource([text, pdf])).toBe(pdf);
    expect(pdfResource([{ id: 'http://x/b.pdf', getProperty: () => 'APPLICATION/PDF' }])?.id).toBe('http://x/b.pdf');
    expect(pdfResource([text])).toBeUndefined();
  });

  it('presents PDFs in an object element and keeps Mirador for other texts', () => {
    const plugin = pdfPlugin({ document: 'Document: A', open: 'Open the PDF' });
    expect(plugin).toMatchObject({ target: 'TextViewer', mode: 'wrap' });

    const shown = plugin.component({ TargetComponent: target, targetProps: { textResources: [pdf] } });
    const object = (shown.props as { children: { type: string; props: Record<string, unknown> }[] }).children[0];
    expect(object.type).toBe('object');
    expect(object.props).toMatchObject({ data: 'http://x/a.pdf', type: 'application/pdf', title: 'Document: A' });

    const other = plugin.component({ TargetComponent: target, targetProps: { textResources: [text] } });
    expect(other.type).toBe(target);
  });

  it('never embeds PDFs of other schemes', () => {
    const plugin = pdfPlugin({ document: 'D', open: 'O' });
    const shown = plugin.component({
      TargetComponent: target,
      targetProps: { textResources: [{ id: 'javascript:alert(1)', getFormat: () => 'application/pdf' }] },
    });
    expect(shown.type).toBe(target);
  });
});
