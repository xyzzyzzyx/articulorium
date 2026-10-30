import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { MatTooltipModule } from '@angular/material/tooltip';

import { Graph, compact } from '../core/rdf/graph';
import { SubjectTerm } from '../core/rdf/term';
import { TermComponent } from './term.component';

/**
 * Lists the properties of one subject, one row per predicate (Fedora's "Properties" panel).
 */
@Component({
  selector: 'ui-property-table',
  imports: [MatTooltipModule, TermComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (groups().length === 0) {
      <p class="ui-empty">No properties.</p>
    } @else {
      <table class="ui-properties">
        <tbody>
          @for (group of groups(); track group.predicate) {
            <tr>
              <th scope="row">
                <a [href]="group.predicate" target="_blank" rel="noopener" [matTooltip]="group.predicate">
                  {{ group.label }}
                </a>
              </th>
              <td>
                <ul>
                  @for (object of group.objects; track $index) {
                    <li><ui-term [term]="object" [base]="base()" /></li>
                  }
                </ul>
              </td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
  styles: `
    .ui-properties { width: 100%; border-collapse: collapse; }
    th, td { text-align: left; vertical-align: top; padding: 6px 8px; border-bottom: 1px solid rgba(0, 0, 0, 0.08); }
    th { width: 30%; font-weight: 500; overflow-wrap: anywhere; }
    ul { list-style: none; margin: 0; padding: 0; }
    li + li { margin-top: 4px; }
    .ui-empty { color: #666; }
  `,
})
export class PropertyTableComponent {
  /** The graph holding the subject's triples. */
  readonly graph = input.required<Graph>();

  /** The subject to describe. */
  readonly subject = input.required<SubjectTerm>();

  /** The platform base. */
  readonly base = input.required<string>();

  /** Predicates with their display labels and objects. */
  protected readonly groups = computed(() =>
    this.graph()
      .properties(this.subject())
      .map((group) => ({ ...group, label: compact(group.predicate) ?? group.predicate })),
  );
}
