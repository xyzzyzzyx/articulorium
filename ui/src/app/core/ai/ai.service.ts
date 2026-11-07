import { HttpClient } from '@angular/common/http';
import { Injectable, Signal, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Observable, catchError, of, shareReplay } from 'rxjs';

import { ConfigService } from '../config.service';
import { Topology } from '../topology/topology';
import {
  AiCapabilities,
  AiCapability,
  MediaMetadataSuggestion,
  NO_AI,
  ProfileSuggestion,
  TopicModel,
  TopologySuggestion,
  TopologySuggestionRequest,
} from './ai';

/**
 * Client of the optional AI agent (`/ai/` on the gateway). The agent only suggests: everything it
 * returns is reviewed by the user and applied with the existing APIs. It answers callers who may
 * change what a suggestion is about (write access), with their own credentials.
 *
 * What the agent offers is read once; when it is off, has no model or is not deployed, nothing is
 * offered and the user interface shows no AI actions.
 */
@Injectable({ providedIn: 'root' })
export class AiService {
  private readonly http = inject(HttpClient);

  private readonly config = inject(ConfigService);

  private readonly capabilities$ = this.http.get<AiCapabilities>(`${this.config.aiBase()}capabilities`).pipe(
    catchError(() => of(NO_AI)),
    shareReplay({ bufferSize: 1, refCount: false }),
  );

  /** What the agent offers, `undefined` until known. */
  readonly capabilities: Signal<AiCapabilities | undefined> = toSignal(this.capabilities$);

  /**
   * Tells whether a capability is offered now.
   *
   * @param capability - the capability
   * @returns a signal, `false` until the agent answered
   */
  offers(capability: AiCapability): Signal<boolean> {
    return computed(() => this.capabilities()?.capabilities[capability] === true);
  }

  /**
   * Suggests a topology for some material.
   *
   * @param request - the material
   * @returns an existing topology or a draft
   */
  suggestTopology(request: TopologySuggestionRequest): Observable<TopologySuggestion> {
    return this.http.post<TopologySuggestion>(`${this.config.aiBase()}topology-suggestions`, request);
  }

  /**
   * Suggests metadata for an image, a video or a PDF.
   *
   * @param path - the binary's path
   * @returns statements to review
   */
  describeMedia(path: string): Observable<MediaMetadataSuggestion> {
    return this.http.post<MediaMetadataSuggestion>(`${this.config.aiBase()}media-metadata`, { path });
  }

  /**
   * Suggests the topics and a summary of a community or collection.
   *
   * @param path - the community or collection
   * @returns the topic model
   */
  modelTopics(path: string): Observable<TopicModel> {
    return this.http.post<TopicModel>(`${this.config.aiBase()}topic-models`, { path });
  }

  /**
   * Suggests an overview, research areas and keywords for an academic profile.
   *
   * @param path - the profile
   * @returns the suggestions
   */
  suggestProfile(path: string): Observable<ProfileSuggestion> {
    return this.http.post<ProfileSuggestion>(`${this.config.aiBase()}profile-suggestions`, { path });
  }
}

/**
 * Hands a topology draft from the suggestion dialog to the topology editor.
 */
@Injectable({ providedIn: 'root' })
export class TopologyDraftStore {
  private pending: Topology | undefined;

  /**
   * Keeps a draft for the editor.
   *
   * @param draft - the draft
   */
  offer(draft: Topology): void {
    this.pending = draft;
  }

  /**
   * Takes the draft (once).
   *
   * @returns the draft, or `undefined`
   */
  take(): Topology | undefined {
    const draft = this.pending;
    this.pending = undefined;
    return draft;
  }
}
