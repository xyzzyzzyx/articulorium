import { Injectable, inject } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';

import { errorMessage } from '../core/ldp/problem';

/**
 * Shows short confirmations and error messages (the role of Fedora's error modal).
 */
@Injectable({ providedIn: 'root' })
export class Notifier {
  private readonly snackBar = inject(MatSnackBar);

  /**
   * Confirms a successful action.
   *
   * @param message - what happened
   */
  success(message: string): void {
    this.snackBar.open(message, 'OK', { duration: 4000, panelClass: 'ui-snack-success' });
  }

  /**
   * Reports a failure.
   *
   * @param action - what was attempted, for example `Delete`
   * @param error - the failure
   */
  error(action: string, error: unknown): void {
    this.snackBar.open(`${action} failed: ${errorMessage(error)}`, 'Dismiss', { panelClass: 'ui-snack-error' });
  }
}
