import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { TranslatePipe } from '@ngx-translate/core';

import { AuthService } from '../core/auth/auth.service';
import { I18nService } from '../core/i18n/i18n.service';
import { WebAcService } from '../core/webac/webac.service';
import { Notifier } from './notifier.service';

/**
 * Asks for a token (with `auth.provider: token`, typically a Consul ACL token), checks it with the
 * platform's WebAC endpoint and keeps it for the session. Closes with the agent's identifier.
 */
@Component({
  selector: 'ui-sign-in-dialog',
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    TranslatePipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2 mat-dialog-title>{{ 'auth.signIn' | translate }}</h2>
    <form (ngSubmit)="submit()">
      <mat-dialog-content class="ui-form">
        <p>{{ 'auth.tokenHelp' | translate }}</p>
        <mat-form-field>
          <mat-label>{{ 'auth.token' | translate }}</mat-label>
          <input matInput name="token" type="password" autocomplete="off" required [(ngModel)]="token" />
        </mat-form-field>
        @if (busy()) {
          <mat-progress-bar mode="indeterminate" [attr.aria-label]="'common.loading' | translate" />
        }
        @if (error(); as message) {
          <p class="ui-error-text" role="alert">{{ message }}</p>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>{{ 'common.cancel' | translate }}</button>
        <button mat-flat-button color="primary" type="submit" [disabled]="busy() || !token.trim()">
          {{ 'auth.signIn' | translate }}
        </button>
      </mat-dialog-actions>
    </form>
  `,
})
export class SignInDialogComponent {
  private readonly auth = inject(AuthService);

  private readonly webac = inject(WebAcService);

  private readonly i18n = inject(I18nService);

  private readonly notifier = inject(Notifier);

  private readonly dialog = inject<MatDialogRef<SignInDialogComponent, string>>(MatDialogRef);

  protected token = '';

  protected readonly busy = signal(false);

  protected readonly error = signal<string | undefined>(undefined);

  /** Stores the token, verifies it and closes with the agent, or forgets it again on failure. */
  protected async submit(): Promise<void> {
    this.busy.set(true);
    this.error.set(undefined);
    await this.auth.signIn(this.token);
    this.webac.access('').subscribe({
      next: (info) => {
        this.busy.set(false);
        if (!info.agent.authenticated) {
          void this.auth.forget();
          this.error.set(this.i18n.t('auth.tokenRejected'));
          return;
        }
        this.dialog.close(info.agent.webId ?? 'signed in');
      },
      error: (error: unknown) => {
        this.busy.set(false);
        void this.auth.forget();
        this.error.set(this.notifier.message(error));
      },
    });
  }
}
