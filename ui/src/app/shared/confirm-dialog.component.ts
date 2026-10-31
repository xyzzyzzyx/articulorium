import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';

/** Content of a confirmation dialog. */
export interface ConfirmData {
  readonly title: string;
  readonly message: string;
  readonly confirm: string;
}

/**
 * Asks the user to confirm a destructive action. Closes with `true` when confirmed.
 */
@Component({
  selector: 'ui-confirm-dialog',
  imports: [MatButtonModule, MatDialogModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content>{{ data.message }}</mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button [mat-dialog-close]="false">Cancel</button>
      <button mat-flat-button color="warn" [mat-dialog-close]="true" cdkFocusInitial>{{ data.confirm }}</button>
    </mat-dialog-actions>
  `,
})
export class ConfirmDialogComponent {
  /** The dialog content. */
  readonly data = inject<ConfirmData>(MAT_DIALOG_DATA);
}
