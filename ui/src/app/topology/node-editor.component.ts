import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';

import { FILE_USES, splitList } from '../core/topology/topology';

/** A metadata field as edited. */
export interface EditableField {
  predicate: string;
  label: string;
  required: boolean;
  repeatable: boolean;
  defaultValue?: string | null;
  datatype?: string | null;
}

/** A file use as edited. */
export interface EditableFile {
  use: string;
  label: string;
  mediaTypes: string[];
}

/** A child rule as edited. */
export interface EditableRule {
  min: number;
  max: number;
  initial: number;
  slugPattern?: string | null;
  node: EditableNode;
}

/** A topology node as edited (the editor mutates it in place). */
export interface EditableNode {
  key: string;
  label: string;
  pcdmType: 'Collection' | 'Object';
  types: string[];
  metadata: EditableField[];
  files: EditableFile[];
  children: EditableRule[];
}

/**
 * Edits one node of a topology and, recursively, its children: key, label, PCDM type,
 * additional types, metadata fields, kinds of files and child rules.
 */
@Component({
  selector: 'ui-node-editor',
  imports: [FormsModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let n = node();
    <fieldset class="ui-node-editor" [disabled]="readonly()" [attr.data-node]="n.key">
      <div class="ui-inline-fields">
        <mat-form-field>
          <mat-label>Key</mat-label>
          <input matInput [name]="prefix() + 'key'" [(ngModel)]="n.key" required />
        </mat-form-field>
        <mat-form-field>
          <mat-label>Label</mat-label>
          <input matInput [name]="prefix() + 'label'" [(ngModel)]="n.label" required />
        </mat-form-field>
        <mat-form-field class="ui-narrow">
          <mat-label>PCDM type</mat-label>
          <mat-select [name]="prefix() + 'pcdm'" [(ngModel)]="n.pcdmType" [disabled]="readonly()">
            <mat-option value="Collection">Collection</mat-option>
            <mat-option value="Object">Object</mat-option>
          </mat-select>
        </mat-form-field>
      </div>
      <mat-form-field class="ui-full">
        <mat-label>Additional types (IRIs, comma separated)</mat-label>
        <input matInput [name]="prefix() + 'types'" [ngModel]="n.types.join(', ')" (ngModelChange)="n.types = split($event)" />
      </mat-form-field>

      <h4>Metadata fields</h4>
      @for (field of n.metadata; track $index) {
        <div class="ui-inline-fields">
          <mat-form-field>
            <mat-label>Predicate IRI</mat-label>
            <input matInput [name]="prefix() + 'mp' + $index" [(ngModel)]="field.predicate" />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Label</mat-label>
            <input matInput [name]="prefix() + 'ml' + $index" [(ngModel)]="field.label" />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Default</mat-label>
            <input matInput [name]="prefix() + 'md' + $index" [(ngModel)]="field.defaultValue" />
          </mat-form-field>
          <mat-checkbox [name]="prefix() + 'mr' + $index" [(ngModel)]="field.required">Required</mat-checkbox>
          <mat-checkbox [name]="prefix() + 'mm' + $index" [(ngModel)]="field.repeatable">Repeatable</mat-checkbox>
          @if (!readonly()) {
            <button mat-icon-button type="button" aria-label="Remove field" (click)="n.metadata.splice($index, 1)">
              <mat-icon>delete</mat-icon>
            </button>
          }
        </div>
      }
      @if (!readonly()) {
        <button mat-button type="button" class="ui-add-field" (click)="addField()"><mat-icon>add</mat-icon> Field</button>
      }

      <h4>Files</h4>
      @for (file of n.files; track $index) {
        <div class="ui-inline-fields">
          <mat-form-field>
            <mat-label>Use</mat-label>
            <mat-select [name]="prefix() + 'fu' + $index" [(ngModel)]="file.use" [disabled]="readonly()">
              @for (use of uses; track use.iri) {
                <mat-option [value]="use.iri">{{ use.label }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Label</mat-label>
            <input matInput [name]="prefix() + 'fl' + $index" [(ngModel)]="file.label" />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Media types</mat-label>
            <input matInput [name]="prefix() + 'fm' + $index" [ngModel]="file.mediaTypes.join(', ')" (ngModelChange)="file.mediaTypes = split($event)" />
          </mat-form-field>
          @if (!readonly()) {
            <button mat-icon-button type="button" aria-label="Remove file" (click)="n.files.splice($index, 1)">
              <mat-icon>delete</mat-icon>
            </button>
          }
        </div>
      }
      @if (!readonly()) {
        <button mat-button type="button" class="ui-add-file" (click)="addFile()"><mat-icon>add</mat-icon> File</button>
      }

      <h4>Children</h4>
      @for (rule of n.children; track $index) {
        <div class="ui-child-rule">
          <div class="ui-inline-fields">
            <mat-form-field class="ui-narrow">
              <mat-label>Min</mat-label>
              <input matInput type="number" min="0" [name]="prefix() + 'cmin' + $index" [(ngModel)]="rule.min" />
            </mat-form-field>
            <mat-form-field class="ui-narrow">
              <mat-label>Max (-1: ∞)</mat-label>
              <input matInput type="number" min="-1" [name]="prefix() + 'cmax' + $index" [(ngModel)]="rule.max" />
            </mat-form-field>
            <mat-form-field class="ui-narrow">
              <mat-label>Initial</mat-label>
              <input matInput type="number" min="0" [name]="prefix() + 'cini' + $index" [(ngModel)]="rule.initial" />
            </mat-form-field>
            <mat-form-field>
              <mat-label>Name pattern</mat-label>
              <input matInput [name]="prefix() + 'cslug' + $index" [(ngModel)]="rule.slugPattern" placeholder="page-{n}" />
            </mat-form-field>
            @if (!readonly()) {
              <button mat-icon-button type="button" aria-label="Remove child" (click)="n.children.splice($index, 1)">
                <mat-icon>delete</mat-icon>
              </button>
            }
          </div>
          <ui-node-editor [node]="rule.node" [readonly]="readonly()" [prefix]="prefix() + rule.node.key + '-'" />
        </div>
      }
      @if (!readonly()) {
        <button mat-button type="button" class="ui-add-child" (click)="addChild()"><mat-icon>add</mat-icon> Child level</button>
      }
    </fieldset>
  `,
  styles: `
    fieldset { border: none; margin: 0; }
    h4 { margin: 8px 0 4px; }
    .ui-full { width: 100%; }
  `,
})
export class NodeEditorComponent {
  /** The node, edited in place. */
  readonly node = input.required<EditableNode>();

  /** Whether editing is disabled (built-in topologies). */
  readonly readonly = input(false);

  /** Prefix making form control names unique across nested editors. */
  readonly prefix = input('root-');

  /** Emitted when the node changed structurally (reserved for parents). */
  readonly changed = output<void>();

  protected readonly uses = FILE_USES;

  protected split(value: string): string[] {
    return splitList(value);
  }

  protected addField(): void {
    this.node().metadata.push({ predicate: 'http://purl.org/dc/terms/', label: '', required: false, repeatable: false });
  }

  protected addFile(): void {
    this.node().files.push({ use: FILE_USES[0].iri, label: 'Original file', mediaTypes: [] });
  }

  protected addChild(): void {
    const n = this.node();
    const key = `${n.key}-child-${n.children.length + 1}`;
    n.children.push({
      min: 0,
      max: -1,
      initial: 1,
      slugPattern: `${key}-{n}`,
      node: {
        key,
        label: 'Child',
        pcdmType: 'Object',
        types: [],
        metadata: [{ predicate: 'http://purl.org/dc/terms/title', label: 'Title', required: true, repeatable: false }],
        files: [],
        children: [],
      },
    });
    this.changed.emit();
  }
}
