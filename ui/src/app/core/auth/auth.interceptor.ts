import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';

import { ConfigService } from '../config.service';
import { AuthService } from './auth.service';

/**
 * Adds `Authorization: Bearer <token>` to requests for the platform, the topology service and the
 * transform service while a token is set. Requests elsewhere (and requests that already carry
 * credentials) are left alone, so the token never leaves the repository's services.
 *
 * @param request - the outgoing request
 * @param next - the next handler
 * @returns the response stream
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const token = inject(AuthService).token();
  if (!token || request.headers.has('Authorization')) {
    return next(request);
  }
  const config = inject(ConfigService);
  const targets = [config.platformBase(), config.topologyBase(), config.transformBase()];
  const url = new URL(request.url, document.baseURI).toString();
  if (!targets.some((base) => url.startsWith(base))) {
    return next(request);
  }
  return next(request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));
};
