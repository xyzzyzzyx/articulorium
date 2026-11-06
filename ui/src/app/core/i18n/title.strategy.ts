import { Injectable, inject } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { RouterStateSnapshot, TitleStrategy } from '@angular/router';
import { TranslateService } from '@ngx-translate/core';

import { ConfigService } from '../config.service';

/**
 * Document titles from route titles, which are translation keys (`route.browse`): `<view> ·
 * <repository title>`, updated again when the language changes.
 */
@Injectable({ providedIn: 'root' })
export class LocalizedTitleStrategy extends TitleStrategy {
  private readonly title = inject(Title);

  private readonly translate = inject(TranslateService);

  private readonly config = inject(ConfigService);

  private last?: RouterStateSnapshot;

  constructor() {
    super();
    this.translate.onLangChange.subscribe(() => {
      if (this.last) {
        this.updateTitle(this.last);
      }
    });
  }

  override updateTitle(snapshot: RouterStateSnapshot): void {
    this.last = snapshot;
    const key = this.buildTitle(snapshot);
    const repository = this.config.config().title;
    this.title.setTitle(key ? `${this.translate.instant(key) as string} · ${repository}` : repository);
  }
}
