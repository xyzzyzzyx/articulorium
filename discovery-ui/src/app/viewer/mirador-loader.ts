import Mirador from 'mirador';

import { PdfLabels, pdfPlugin } from './mirador-pdf-plugin';

/** A running Mirador. */
export interface MiradorInstance {
  unmount(): void;
}

/**
 * Starts Mirador. This module (Mirador, React, Material UI) is loaded only in browsers, only on
 * pages with a viewer, as its own chunk.
 *
 * @param config - Mirador's configuration
 * @param pdf - whether PDFs are presented by the PDF plugin, and its labels
 */
export function startMirador(config: Record<string, unknown>, pdf: PdfLabels | null): MiradorInstance {
  return Mirador.viewer(config, pdf ? [pdfPlugin(pdf)] : []) as MiradorInstance;
}
