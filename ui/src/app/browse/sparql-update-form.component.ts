import { ChangeDetectionStrategy, Component, computed, inject, input, linkedSignal, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

import { LdpService } from '../core/ldp/ldp.service';
import { Graph, prefixPreamble } from '../core/rdf/graph';
import { Notifier } from '../shared/notifier.service';

/**
 * "Update Properties": edits an RDF source (or a binary's description) with SPARQL Update. The
 * request starts with the `PREFIX`es of the namespaces the resource uses, like Fedora's form.
 */
@Component({
  selector: 'ui-sparql-update-form',
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form (ngSubmit)="submit()" class="ui-form">
      <mat-form-field class="ui-code">
        <mat-label>SPARQL Update</mat-label>
        <textarea matInput name="update" rows="12" [ngModel]="update()" (ngModelChange)="update.set($event)"></textarea>
      </mat-form-field>
      <div class="ui-row">
        <button mat-flat-button color="primary" type="submit" [disabled]="busy()">Update</button>
        <button mat-button type="button" (click)="update.set(template())">Reset</button>
      </div>
    </form>
  `,
})
export class SparqlUpdateFormComponent {
  private readonly ldp = inject(LdpService);

  private readonly notifier = inject(Notifier);

  /** URL of the RDF source to update. */
  readonly target = input.required<string>();

  /** The current representation (for the prefix preamble). */
  readonly graph = input.required<Graph>();

  /** Entity tag the edit is based on. */
  readonly etag = input<string>();

  /** Emits after a successful update. */
  readonly updated = output<void>();

  /** The empty request for the current representation. */
  protected readonly template = computed(() => {
    const preamble = prefixPreamble(this.graph());
    return `${preamble ? `${preamble}\n\n` : ''}DELETE { }\nINSERT { }\nWHERE { }\n`;
  });

  /** The request being edited; reset whenever the resource changes. */
  protected readonly update = linkedSignal(() => this.template());

  protected readonly busy = signal(false);

  /** Sends the update. */
  protected submit(): void {
    this.busy.set(true);
    this.ldp.patch(this.target(), this.update(), this.etag()).subscribe({
      next: () => {
        this.busy.set(false);
        // the template may be unchanged (same prefixes), so reset explicitly; when the reloaded
        // representation brings new prefixes, the linked signal follows the new template
        this.update.set(this.template());
        this.notifier.success('Properties updated');
        this.updated.emit();
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.notifier.error('Update', error);
      },
    });
  }
}
