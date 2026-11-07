import { Provider, WritableSignal, computed, signal } from '@angular/core';
import { throwError } from 'rxjs';

import { AiCapabilities, AiCapability, NO_AI } from '../core/ai/ai';
import { AiService } from '../core/ai/ai.service';

/**
 * What the AI agent answers `GET capabilities` with when it offers some capabilities.
 *
 * @param offered - the capabilities offered
 * @returns the answer
 */
export function aiCapabilities(offered: readonly AiCapability[]): AiCapabilities {
  if (offered.length === 0) {
    return NO_AI;
  }
  return {
    enabled: true,
    model: true,
    modelName: 'test:model',
    capabilities: Object.fromEntries(offered.map((capability) => [capability, true])),
  };
}

/** The part of {@link AiService} the pages use; tests may change what it offers. */
export type FakeAiService = Pick<AiService, 'offers' | 'suggestTopology' | 'describeMedia' | 'modelTopics' | 'suggestProfile'> & {
  readonly capabilities: WritableSignal<AiCapabilities | undefined>;
};

/**
 * A stand-in for the AI agent that sends no request for its capabilities; asking it anything
 * fails. A test changes what it offers with `capabilities.set(aiCapabilities([...]))`.
 *
 * @param offered - the capabilities it offers (none by default: the agent is off)
 * @returns the stand-in
 */
export function fakeAiService(offered: readonly AiCapability[] = []): FakeAiService {
  const capabilities = signal<AiCapabilities | undefined>(aiCapabilities(offered));
  const unavailable = () => throwError(() => new Error('the AI agent is not available in this test'));
  return {
    capabilities,
    offers: (capability) => computed(() => capabilities()?.capabilities[capability] === true),
    suggestTopology: unavailable,
    describeMedia: unavailable,
    modelTopics: unavailable,
    suggestProfile: unavailable,
  };
}

/**
 * Replaces the AI agent for tests of pages that only show or hide its actions (see
 * {@link fakeAiService}). Tests of the agent's own components use the real {@link AiService} and
 * answer its requests instead.
 *
 * @param offered - the capabilities the agent offers (none by default: the agent is off)
 * @returns the provider
 */
export function provideTestAi(offered: readonly AiCapability[] = []): Provider {
  return { provide: AiService, useValue: fakeAiService(offered) };
}

/**
 * Makes the stand-in provided by {@link provideTestAi} offer capabilities.
 *
 * @param ai - the injected stand-in
 * @param offered - the capabilities
 */
export function offerAi(ai: unknown, offered: readonly AiCapability[]): void {
  (ai as FakeAiService).capabilities.set(aiCapabilities(offered));
}
