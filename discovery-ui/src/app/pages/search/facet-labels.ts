import { FacetConfig } from '../../core/config/discovery-config';
import { LocaleService } from '../../core/i18n/locale.service';
import { localName } from '../../shared/links';

/**
 * The label of a facet value: the configured label, a year range, a language name, or the value
 * itself (the local name of an IRI).
 *
 * @param facet - the facet
 * @param value - the value
 * @param locales - the current locale (texts, language names)
 */
export function facetValueLabel(facet: FacetConfig, value: string, locales: LocaleService): string {
  const configured = facet.values?.[value];
  if (configured) {
    const text = locales.text(configured);
    if (text) {
      return text;
    }
  }
  if (facet.type === 'YEAR_RANGE') {
    const [from, to] = value.split(/-(?=-?\d)/);
    return from === to ? from : `${from}–${to}`;
  }
  if (facet.id === 'language' && /^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$/.test(value)) {
    return locales.languageName(value);
  }
  return /^https?:\/\//.test(value) ? localName(value) : value;
}
