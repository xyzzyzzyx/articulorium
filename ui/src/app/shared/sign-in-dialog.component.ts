import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';

import { AuthService } from '../core/auth/auth.service';
import { errorMessage } from '../core/ldp/problem';
import { WebAcService } from '../core/webac/webac.service';

/**
 * Asks for a Consul ACL token, checks it with the platform's WebAC endpoint and keeps it for the
 * session. Closes with the agent's identifier.
 */
@Component({
  selector: 'ui-sign-in-dialog',
  imports: [FormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatProgressBarModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2 mat-dialog-title>Sign in</h2>
    <form (ngSubmit)="submit()">
      <mat-dialog-content class="ui-form">
        <p>
          Paste a Consul ACL token (its secret ID). The repository maps the token's description to your agent and its
          roles and policies to groups used by access control lists.
        </p>
        <mat-form-field>
          <mat-label>Consul ACL token</mat-label>
          <input matInput name="token" type="password" autocomplete="off" required [(ngModel)]="token" />
        </mat-form-field>
        @if (busy()) {
          <mat-progress-bar mode="indeterminate" />
        }
        @if (error(); as message) {
          <p class="ui-error-text" role="alert">{{ message }}</p>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancel</button>
        <button mat-flat-button color="primary" type="submit" [disabled]="busy() || !token.trim()">Sign in</button>
      </mat-dialog-actions>
    </form>
  `,
})
export class SignInDialogComponent {
  private readonly auth = inject(AuthService);

  private readonly webac = inject(WebAcService);

  private readonly dialog = inject<MatDialogRef<SignInDialogComponent, string>>(MatDialogRef);

  protected token = '';

  protected readonly busy = signal(false);

  protected readonly error = signal<string | undefined>(undefined);

  /** Stores the token, verifies it and closes with the agent, or forgets it again on failure. */
  protected submit(): void {
    this.busy.set(true);
    this.error.set(undefined);
    this.auth.signIn(this.token);
    this.webac.access('').subscribe({
      next: (info) => {
        this.busy.set(false);
        if (!info.agent.authenticated) {
          this.auth.signOut();
          this.error.set('The repository did not accept the token (Consul ACL tokens may be disabled).');
          return;
        }
        this.dialog.close(info.agent.webId ?? 'signed in');
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.auth.signOut();
        this.error.set(errorMessage(error));
      },
    });
  }
}
