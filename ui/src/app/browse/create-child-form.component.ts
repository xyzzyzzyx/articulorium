import { ChangeDetectionStrategy, Component, OnInit, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';

import { LdpService } from '../core/ldp/ldp.service';
import { ChildKind } from '../core/ldp/models';
import { Notifier } from '../shared/notifier.service';
import { childTemplate, isTemplate } from './child-templates';

/**
 * "Create New Child Resource": a basic, direct or indirect container described in Turtle, or a
 * binary uploaded from a file. An identifier creates the child at that path (PUT); without one
 * the platform mints it (POST). Containers outside archival groups can be created as archival
 * groups, which the platform stores (with everything later created inside) as one OCFL object.
 */
@Component({
  selector: 'ui-create-child-form',
  imports: [FormsModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatInputModule, MatProgressBarModule, MatSelectModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form (ngSubmit)="submit()" #form="ngForm" class="ui-form">
      <mat-form-field>
        <mat-label>Type</mat-label>
        <mat-select name="kind" [ngModel]="kind()" (ngModelChange)="changeKind($event)">
          <mat-option value="basic">Basic container</mat-option>
          <mat-option value="direct">Direct container</mat-option>
          <mat-option value="indirect">Indirect container</mat-option>
          <mat-option value="binary">Binary</mat-option>
        </mat-select>
      </mat-form-field>
      <mat-form-field>
        <mat-label>Identifier</mat-label>
        <input matInput name="slug" [(ngModel)]="slug" placeholder="(auto-generated identifier)" pattern="[^\\/\\?#]*" />
        <mat-hint>Leave empty to let the platform choose</mat-hint>
      </mat-form-field>
      @if (kind() === 'binary') {
        <div class="ui-file">
          <input type="file" name="file" (change)="pickFile($event)" required aria-label="File" />
        </div>
        <mat-form-field>
          <mat-label>MIME type</mat-label>
          <input matInput name="mimeType" [(ngModel)]="mimeType" placeholder="application/octet-stream" />
        </mat-form-field>
      } @else {
        <mat-form-field class="ui-code">
          <mat-label>RDF (Turtle)</mat-label>
          <textarea matInput name="turtle" rows="9" [(ngModel)]="turtle"></textarea>
        </mat-form-field>
        @if (allowArchivalGroup()) {
          <mat-checkbox name="archivalGroup" [(ngModel)]="archivalGroup" class="ui-archival-group">
            Archival group
          </mat-checkbox>
          <p class="ui-hint">Stores the container and everything created inside it as one OCFL object, versioned as a unit.</p>
        }
      }
      @if (busy()) {
        <mat-progress-bar mode="indeterminate" />
      }
      <button mat-flat-button color="primary" type="submit" [disabled]="busy() || form.invalid || (kind() === 'binary' && !file())">
        Add
      </button>
    </form>
  `,
})
export class CreateChildFormComponent implements OnInit {
  private readonly ldp = inject(LdpService);

  private readonly notifier = inject(Notifier);

  /** Path of the parent container. */
  readonly parentPath = input.required<string>();

  /** IRI of the parent container (used by the membership templates). */
  readonly parentIri = input.required<string>();

  /** Whether the new container may be an archival group (not inside another one). */
  readonly allowArchivalGroup = input(true);

  /** Emits the URL of the created resource. */
  readonly created = output<string>();

  protected readonly kind = signal<ChildKind>('basic');

  protected readonly file = signal<File | undefined>(undefined);

  protected readonly busy = signal(false);

  protected slug = '';

  protected mimeType = '';

  protected turtle = '';

  protected archivalGroup = false;

  /** Starts with the basic container template. */
  ngOnInit(): void {
    this.turtle = childTemplate(this.kind(), this.parentIri());
  }

  /**
   * Switches the kind, replacing the Turtle with the new kind's template unless it was edited.
   *
   * @param kind - the new kind
   */
  protected changeKind(kind: ChildKind): void {
    if (isTemplate(this.turtle, this.parentIri())) {
      this.turtle = childTemplate(kind, this.parentIri());
    }
    this.kind.set(kind);
  }

  /**
   * Remembers the chosen file and fills in its media type, asking the service to detect it when
   * the browser does not know it.
   *
   * @param event - the change event of the file input
   */
  protected pickFile(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    this.file.set(file);
    if (!file) {
      return;
    }
    if (file.type) {
      this.mimeType = file.type;
      return;
    }
    this.ldp.detectMediaType(file).subscribe({
      next: (detection) => (this.mimeType = this.mimeType || detection.mediaType),
      error: () => undefined,
    });
  }

  /** Creates the child. */
  protected submit(): void {
    this.busy.set(true);
    this.ldp
      .createChild(this.parentPath(), {
        kind: this.kind(),
        archivalGroup: this.kind() !== 'binary' && this.allowArchivalGroup() && this.archivalGroup,
        slug: this.slug,
        turtle: this.kind() === 'binary' ? undefined : this.turtle,
        file: this.kind() === 'binary' ? this.file() : undefined,
        filename: this.kind() === 'binary' ? this.file()?.name : undefined,
        mimeType: this.mimeType,
      })
      .subscribe({
        next: (location) => {
          this.busy.set(false);
          this.notifier.success('Resource created');
          this.slug = '';
          this.archivalGroup = false;
          this.created.emit(location);
        },
        error: (error: unknown) => {
          this.busy.set(false);
          this.notifier.error('Create', error);
        },
      });
  }
}
