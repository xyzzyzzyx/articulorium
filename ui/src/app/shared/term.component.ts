import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';

import { pathOf, routeTo } from '../core/ldp/paths';
import { compact } from '../core/rdf/graph';
import { Term } from '../core/rdf/term';

/**
 * Renders one RDF term: repository IRIs link to their browse view, other IRIs open externally
 * (shown with their prefixed name), literals show their language or datatype.
 */
@Component({
  selector: 'ui-term',
  imports: [MatTooltipModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let t = term();
    @switch (t.kind) {
      @case ('iri') {
        @if (route(); as commands) {
          <a class="ui-term-iri" [routerLink]="commands" [matTooltip]="t.value">{{ label() }}</a>
        } @else {
          <a class="ui-term-iri" [href]="t.value" target="_blank" rel="noopener" [matTooltip]="t.value">{{ label() }}</a>
        }
      }
      @case ('bnode') {
        <span class="ui-term-bnode">_:{{ t.value }}</span>
      }
      @default {
        <span class="ui-term-literal">{{ t.value }}</span>
        @if (annotation(); as note) {
          <span class="ui-term-annotation">{{ note }}</span>
        }
      }
    }
  `,
  styles: `
    :host { overflow-wrap: anywhere; }
    .ui-term-literal { white-space: pre-wrap; }
    .ui-term-bnode, .ui-term-annotation { color: var(--ui-muted, #666); font-size: 0.85em; }
    .ui-term-annotation { margin-left: 0.4em; }
  `,
})
export class TermComponent {
  /** The term to show. */
  readonly term = input.required<Term>();

  /** The platform base, to recognise repository IRIs. */
  readonly base = input.required<string>();

  /** Browse route of a repository IRI, if it is one. */
  protected readonly route = computed(() => {
    const t = this.term();
    if (t.kind !== 'iri') {
      return undefined;
    }
    const path = pathOf(this.base(), t.value);
    return path === undefined ? undefined : routeTo('browse', path);
  });

  /** Display text of an IRI: its prefixed name, path or the IRI. */
  protected readonly label = computed(() => {
    const value = this.term().value;
    const path = pathOf(this.base(), value);
    if (path !== undefined) {
      return `/${path}`;
    }
    return compact(value) ?? value;
  });

  /** Language tag or datatype of a literal. */
  protected readonly annotation = computed(() => {
    const t = this.term();
    if (t.kind !== 'literal') {
      return undefined;
    }
    if (t.language) {
      return `@${t.language}`;
    }
    if (t.datatype && t.datatype !== 'http://www.w3.org/2001/XMLSchema#string') {
      return `^^${compact(t.datatype) ?? t.datatype}`;
    }
    return undefined;
  });
}
