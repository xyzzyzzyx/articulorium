import { DatePipe, DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { LdpService } from '../core/ldp/ldp.service';
import { SEARCH_FIELDS, SEARCH_OPERATORS, SearchCondition, SearchItem, SearchQuery } from '../core/ldp/models';
import { pathOf, routeTo } from '../core/ldp/paths';
import { errorMessage } from '../core/ldp/problem';
import { compact } from '../core/rdf/graph';
import { paramsFromQuery, queryFromParams } from './search-query';

/** An editable search condition. */
interface ConditionRow {
  field: SearchCondition['field'];
  operator: SearchCondition['operator'];
  value: string;
}

/**
 * The search page (Fedora's `fcr:search` view): any number of conditions on the resource IRI,
 * dates, size, media type or RDF type, with ordering and paging. The search lives in the URL.
 */
@Component({
  selector: 'ui-search-page',
  imports: [
    DatePipe,
    DecimalPipe,
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatPaginatorModule,
    MatProgressBarModule,
    MatSelectModule,
    MatTableModule,
    RouterLink,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './search-page.component.html',
})
export class SearchPageComponent {
  private readonly ldp = inject(LdpService);

  private readonly router = inject(Router);

  private readonly route = inject(ActivatedRoute);

  protected readonly fields = SEARCH_FIELDS;

  protected readonly operators = SEARCH_OPERATORS;

  protected readonly columns = ['fedora_id', 'created', 'modified', 'content_size', 'mime_type', 'rdf_type'];

  /** The search described by the URL. */
  protected readonly query = computed(() => queryFromParams(this.params() ?? {}));

  /** Conditions being edited. */
  protected readonly rows = signal<ConditionRow[]>([]);

  protected orderBy: SearchQuery['orderBy'] | '' = '';

  protected order: 'asc' | 'desc' = 'asc';

  private readonly params = toSignal(this.route.queryParams);

  /** Results of the search in the URL. */
  protected readonly results = rxResource({
    params: () => this.query(),
    stream: ({ params }) => this.ldp.search(params),
  });

  protected readonly error = computed(() => {
    const error = this.results.error();
    return error ? errorMessage(error) : undefined;
  });

  constructor() {
    // show the URL's search in the form
    effect(() => {
      const query = this.query();
      this.rows.set(
        query.conditions.length > 0
          ? query.conditions.map((condition) => ({ ...condition }))
          : [{ field: 'fedora_id', operator: '=', value: '' }],
      );
      this.orderBy = query.orderBy ?? '';
      this.order = query.order ?? 'asc';
    });
  }

  /** Adds an empty condition. */
  protected addCondition(): void {
    this.rows.update((rows) => [...rows, { field: 'fedora_id', operator: '=', value: '' }]);
  }

  /**
   * Removes a condition.
   *
   * @param index - its position
   */
  protected removeCondition(index: number): void {
    this.rows.update((rows) => rows.filter((_, i) => i !== index));
  }

  /** Runs the search from the first page. */
  protected submit(): void {
    this.navigate({ ...this.query(), conditions: this.rows(), offset: 0, orderBy: this.orderBy || undefined, order: this.order });
  }

  /**
   * Moves to another page.
   *
   * @param event - the paginator event
   */
  protected changePage(event: PageEvent): void {
    this.navigate({ ...this.query(), maxResults: event.pageSize, offset: event.pageIndex * event.pageSize });
  }

  /**
   * Route of a result's browse view.
   *
   * @param item - the result
   * @returns the commands, or `undefined` for foreign IRIs
   */
  protected itemRoute(item: SearchItem): string[] | undefined {
    const path = pathOf(this.ldp.base(), item.fedora_id);
    return path === undefined ? undefined : routeTo('browse', path);
  }

  /**
   * Display name of a type.
   *
   * @param type - the type IRI
   * @returns its prefixed name
   */
  protected typeName(type: string): string {
    return compact(type) ?? type;
  }

  private navigate(query: SearchQuery): void {
    void this.router.navigate(['/search'], { queryParams: paramsFromQuery(query) });
  }
}
