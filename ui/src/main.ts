import { ApplicationConfig, InjectionToken } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import * as fingerprint from '@fingerprintjs/fingerprintjs';
import Bowser from 'bowser';

import { AppComponent } from './app/app.component';
import { appConfig } from './app/app.config';

export interface Client {
  id: string;
  browser: {
    name?: string;
    version?: string;
  };
  os: {
    name?: string;
    version?: string;
    versionName?: string;
  };
  platform: {
    type?: string;
    vendor?: string;
    model?: string;
  };
  engine: {
    name?: string;
    version?: string;
  };
}

export const CLIENT = new InjectionToken<Client>('CLIENT');

const config = (result: fingerprint.GetResult): ApplicationConfig =>
  appConfig([
    {
      provide: CLIENT,
      useValue: {
        id: result.visitorId,
        ...Bowser.parse(window.navigator.userAgent),
      },
    },
  ]);

fingerprint
  .load()
  .then((fp: fingerprint.Agent) => fp.get()
    .then((result: fingerprint.GetResult) =>
      bootstrapApplication(AppComponent, config(result))
        .catch((err) => console.error(err)),
    ).catch((err) => console.error(err)),
  ).catch((err) => console.error(err));
