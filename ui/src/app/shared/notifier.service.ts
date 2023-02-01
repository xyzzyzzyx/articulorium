import { Injectable, inject } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';

import { I18nService } from '../core/i18n/i18n.service';
import { errorMessage } from '../core/ldp/problem';

/**
 * Shows short confirmations and error messages (the role of Fedora's error modal), in the user
 * interface's language.
 */
@Injectable({ providedIn: 'root' })
export class Notifier {
  private readonly snackBar = inject(MatSnackBar);

  private readonly i18n = inject(I18nService);

  /**
   * Confirms a successful action.
   *
   * @param message - what happened, already translated
   */
  success(message: string): void {
    this.snackBar.open(message, this.i18n.t('common.ok'), { duration: 4000, panelClass: 'ui-snack-success' });
  }

  /**
   * Reports a failure.
   *
   * @param action - what was attempted, already translated, for example `Delete`
   * @param error - the failure
   */
  error(action: string, error: unknown): void {
    const message = errorMessage(error, { t: (key, params) => this.i18n.t(key, params), locale: this.i18n.locale() });
    this.snackBar.open(this.i18n.t('error.failed', { action, message }), this.i18n.t('common.dismiss'), {
      panelClass: 'ui-snack-error',
      politeness: 'assertive',
    });
  }

  /**
   * The readable message of a failure, translated like {@link error}'s.
   *
   * @param error - the failure
   * @returns the message
   */
  message(error: unknown): string {
    return errorMessage(error, { t: (key, params) => this.i18n.t(key, params), locale: this.i18n.locale() });
  }
}
