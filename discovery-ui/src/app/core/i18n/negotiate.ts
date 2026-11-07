/**
 * Picks the locale an `Accept-Language` header prefers among the enabled ones.
 *
 * @param header - the header, for example `de-AT,de;q=0.9,en;q=0.5`
 * @param available - the enabled locales' tags
 * @param fallback - the default locale's tag
 * @returns an exact match, else a match of the language alone (`de-AT` → `de`, `pt` → `pt-BR`),
 *   else the default
 */
export function negotiateLocale(header: string | null | undefined, available: string[], fallback: string): string {
  const preferences = (header ?? '')
    .split(',')
    .map((part, index) => {
      const [range, ...parameters] = part.trim().split(';');
      const q = parameters.map((p) => p.trim()).find((p) => p.startsWith('q='));
      const weight = q ? Number(q.substring(2)) : 1;
      return { range: range.trim().toLowerCase(), weight: Number.isFinite(weight) ? weight : 0, index };
    })
    .filter((preference) => preference.range && preference.range !== '*' && preference.weight > 0)
    .sort((a, b) => b.weight - a.weight || a.index - b.index);
  for (const { range } of preferences) {
    const exact = available.find((tag) => tag.toLowerCase() === range);
    if (exact) {
      return exact;
    }
    const language = range.split('-')[0];
    const partial = available.find((tag) => tag.toLowerCase().split('-')[0] === language);
    if (partial) {
      return partial;
    }
  }
  return fallback;
}
