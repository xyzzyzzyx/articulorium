import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { ConfigService } from '../config.service';
import { platformUrl } from '../ldp/paths';
import { AccessInfo, AuthorizationDraft, aclTurtle } from './webac';

/**
 * Web Access Control from the user interface: who the user is, what it may do, and the access
 * control lists of resources (`<resource>/fcr:acl`).
 */
@Injectable({ providedIn: 'root' })
export class WebAcService {
  private readonly http = inject(HttpClient);

  private readonly config = inject(ConfigService);

  /**
   * Reports the requesting agent and its access to a resource.
   *
   * @param path - the resource path (empty for the root)
   * @returns the access information
   */
  access(path: string): Observable<AccessInfo> {
    return this.http.get<AccessInfo>(`${this.config.platformBase()}.well-known/webac`, {
      params: new HttpParams().set('resource', path),
    });
  }

  /**
   * Creates or replaces the ACL of a resource.
   *
   * @param path - the resource path
   * @param resourceIri - the resource's IRI (the authorizations' `acl:accessTo`)
   * @param drafts - the authorizations
   * @returns completes when stored
   */
  save(path: string, resourceIri: string, drafts: readonly AuthorizationDraft[]): Observable<void> {
    return this.http
      .put(platformUrl(this.config.platformBase(), path, 'fcr:acl'), aclTurtle(resourceIri, drafts), {
        headers: { 'Content-Type': 'text/turtle' },
        responseType: 'text',
      })
      .pipe(map(() => undefined));
  }

  /**
   * Deletes the ACL of a resource, which then inherits again.
   *
   * @param path - the resource path
   * @returns completes when deleted
   */
  remove(path: string): Observable<void> {
    return this.http
      .delete(platformUrl(this.config.platformBase(), path, 'fcr:acl'), { responseType: 'text' })
      .pipe(map(() => undefined));
  }
}
