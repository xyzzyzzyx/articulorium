import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { RouterLink } from '@angular/router';

import { LdpService, downloadName } from '../core/ldp/ldp.service';
import { BinaryInfo } from '../core/ldp/models';
import { routeTo } from '../core/ldp/paths';
import { Notifier } from '../shared/notifier.service';

/**
 * The content of a binary: technical metadata, "Download Content", "Fixity" and, when the
 * platform allows it, "Update Content".
 */
@Component({
  selector: 'ui-binary-content',
  imports: [DecimalPipe, FormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let info = binary();
    <dl class="ui-facts">
      <dt>File name</dt>
      <dd>{{ info.filename ?? '—' }}</dd>
      <dt>MIME type</dt>
      <dd>{{ info.mimeType ?? '—' }}</dd>
      <dt>Size</dt>
      <dd>{{ info.size === undefined ? '—' : (info.size | number) + ' bytes' }}</dd>
      <dt>Digest</dt>
      <dd class="ui-mono">{{ info.digest ?? '—' }}</dd>
    </dl>
    <div class="ui-row">
      <a mat-flat-button color="primary" [href]="info.url" [attr.download]="downloadName(info)"><mat-icon>download</mat-icon> Download content</a>
      <a mat-stroked-button [routerLink]="fixityRoute()"><mat-icon>verified</mat-icon> Fixity</a>
    </div>
    @if (writable()) {
      <h3>Update content</h3>
      <form (ngSubmit)="submit()" class="ui-form">
        <input type="file" name="file" (change)="pickFile($event)" aria-label="New content" />
        <mat-form-field>
          <mat-label>MIME type</mat-label>
          <input matInput name="mimeType" [(ngModel)]="mimeType" placeholder="application/octet-stream" />
        </mat-form-field>
        <button mat-flat-button color="primary" type="submit" [disabled]="busy() || !file()">Update</button>
      </form>
    }
  `,
})
export class BinaryContentComponent {
  private readonly ldp = inject(LdpService);

  private readonly notifier = inject(Notifier);

  /** Path of the binary. */
  readonly path = input.required<string>();

  /** Its details. */
  readonly binary = input.required<BinaryInfo>();

  /** Whether the content may be replaced. */
  readonly writable = input(false);

  /** Emits after the content was replaced. */
  readonly replaced = output<void>();

  protected readonly file = signal<File | undefined>(undefined);

  protected readonly busy = signal(false);

  protected mimeType = '';

  /** Name the content is saved under when downloaded. */
  protected readonly downloadName = downloadName;

  /** Route of the fixity view. */
  protected fixityRoute(): string[] {
    return routeTo('fixity', this.path());
  }

  /**
   * Remembers the chosen file and its media type.
   *
   * @param event - the change event of the file input
   */
  protected pickFile(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    this.file.set(file);
    this.mimeType = file?.type ?? this.mimeType;
  }

  /** Replaces the content. */
  protected submit(): void {
    const file = this.file();
    if (!file) {
      return;
    }
    this.busy.set(true);
    this.ldp.replaceBinary(this.path(), file, this.mimeType, file.name).subscribe({
      next: () => {
        this.busy.set(false);
        this.notifier.success('Content updated');
        this.replaced.emit();
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.notifier.error('Update content', error);
      },
    });
  }
}
