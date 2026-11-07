import { createElement, type ComponentType, type ReactElement } from 'react';

/** A text resource of a Mirador canvas (a manifesto.js resource). */
interface TextResource {
  id?: string;
  getFormat?: () => string | null;
  getProperty?: (name: string) => unknown;
}

/** What Mirador hands a plugin wrapping its `TextViewer`. */
interface TextViewerProps {
  TargetComponent: ComponentType<Record<string, unknown>>;
  targetProps: Record<string, unknown> & { textResources?: TextResource[]; windowId?: string };
}

/** Labels of the plugin in the interface's language. */
export interface PdfLabels {
  document: string;
  open: string;
}

/**
 * The PDF of a canvas: a `Text` resource painted with format `application/pdf` (the transform
 * service paints the PDF of objects without images or media).
 */
export function pdfResource(resources: TextResource[] | undefined): TextResource | undefined {
  return (resources ?? []).find((resource) => {
    const format = resource.getFormat?.() ?? resource.getProperty?.('format');
    return typeof format === 'string' && format.toLowerCase() === 'application/pdf' && !!resource.id;
  });
}

/**
 * A Mirador plugin presenting PDFs: Mirador hands canvases painted with text resources to its
 * `TextViewer`, which this plugin wraps. For a PDF it shows the browser's own PDF viewer (with a
 * link to open the document where browsers cannot embed it) above Mirador's canvas navigation;
 * other text resources keep Mirador's own viewer.
 *
 * @param labels - the plugin's labels in the interface's language
 */
export function pdfPlugin(labels: PdfLabels) {
  const component = ({ TargetComponent, targetProps }: TextViewerProps): ReactElement => {
    const pdf = pdfResource(targetProps.textResources);
    if (!pdf?.id || !/^https?:\/\//i.test(pdf.id)) {
      return createElement(TargetComponent, targetProps);
    }
    const link = createElement('a', { href: pdf.id, target: '_blank', rel: 'noopener' }, labels.open);
    return createElement(
      'div',
      { className: 'disc-pdf', style: { display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0, width: '100%' } },
      createElement(
        'object',
        {
          data: pdf.id,
          type: 'application/pdf',
          title: labels.document,
          'aria-label': labels.document,
          style: { flex: 1, width: '100%', minHeight: 0, border: 0 },
        },
        createElement('p', { style: { padding: 16 } }, link),
      ),
      createElement('p', { className: 'disc-pdf-open', style: { margin: 0, padding: '4px 8px', textAlign: 'end' } }, link),
      createElement(TargetComponent, { ...targetProps, textResources: [] }),
    );
  };
  return { target: 'TextViewer', mode: 'wrap', component, name: 'ArticuloriumPdfViewer' };
}
