import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';

import { LocalizedNumberPipe } from '../core/i18n/format.pipes';
import { I18nService } from '../core/i18n/i18n.service';
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
  imports: [
    FormsModule,
    LocalizedNumberPipe,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    RouterLink,
    TranslatePipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let info = binary();
    <dl class="ui-facts">
      <dt>{{ 'binary.filename' | translate }}</dt>
      <dd>{{ info.filename ?? ('common.noValue' | translate) }}</dd>
      <dt>{{ 'binary.mimeType' | translate }}</dt>
      <dd>{{ info.mimeType ?? ('common.noValue' | translate) }}</dd>
      <dt>{{ 'binary.size' | translate }}</dt>
      <dd>
        {{ info.size === undefined ? ('common.noValue' | translate) : ('binary.bytes' | translate: { count: info.size | l10nNumber }) }}
      </dd>
      <dt>{{ 'binary.digest' | translate }}</dt>
      <dd class="ui-mono">{{ info.digest ?? ('common.noValue' | translate) }}</dd>
    </dl>
    <div class="ui-row">
      <a mat-flat-button color="primary" [href]="info.url" [attr.download]="downloadName(info)">
        <mat-icon aria-hidden="true">download</mat-icon> {{ 'binary.download' | translate }}
      </a>
      <a mat-stroked-button [routerLink]="fixityRoute()"><mat-icon aria-hidden="true">verified</mat-icon> {{ 'binary.fixity' | translate }}</a>
    </div>
    @if (writable()) {
      <h3>{{ 'binary.update' | translate }}</h3>
      <form (ngSubmit)="submit()" class="ui-form">
        <input type="file" name="file" (change)="pickFile($event)" [attr.aria-label]="'binary.newContent' | translate" />
        <mat-form-field>
          <mat-label>{{ 'binary.mimeType' | translate }}</mat-label>
          <input matInput name="mimeType" [(ngModel)]="mimeType" placeholder="application/octet-stream" />
        </mat-form-field>
        <button mat-flat-button color="primary" type="submit" [disabled]="busy() || !file()">
          {{ 'binary.updateButton' | translate }}
        </button>
      </form>
    }
  `,
})
export class BinaryContentComponent {
  private readonly ldp = inject(LdpService);

  private readonly notifier = inject(Notifier);

  private readonly i18n = inject(I18nService);

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
        this.notifier.success(this.i18n.t('binary.updated'));
        this.replaced.emit();
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.notifier.error(this.i18n.t('binary.update'), error);
      },
    });
  }
}
