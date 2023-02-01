import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { emptyNode } from '../topology/topology';
import { TEST_AI, provideTestConfig } from '../../testing/test-config';
import { NO_AI } from './ai';
import { AiService, TopologyDraftStore } from './ai.service';

describe('AiService', () => {
  let http: HttpTestingController;
  let service: AiService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideTestConfig()] });
    http = TestBed.inject(HttpTestingController);
    service = TestBed.inject(AiService);
  });

  afterEach(() => http.verify());

  it('reads what the agent offers once', () => {
    const topology = service.offers('topology');
    const media = service.offers('media');
    expect(topology()).toBe(false);
    http.expectOne({ method: 'GET', url: `${TEST_AI}capabilities` }).flush({
      enabled: true,
      model: true,
      modelName: 'ollama:qwen2.5vl:3b',
      capabilities: { topology: true, media: false, topics: true, classification: true },
    });
    expect(topology()).toBe(true);
    expect(media()).toBe(false);
    expect(service.offers('topics')()).toBe(true);
    // a second reader shares the first answer
    expect(service.capabilities()?.modelName).toBe('ollama:qwen2.5vl:3b');
  });

  it('offers nothing when the agent is not deployed', () => {
    const topics = service.offers('topics');
    http.expectOne(`${TEST_AI}capabilities`).flush('Not found', { status: 404, statusText: 'Not Found' });
    expect(service.capabilities()).toEqual(NO_AI);
    expect(topics()).toBe(false);
  });

  it('asks for topology suggestions, media metadata, topic models and profile suggestions', () => {
    http.expectOne(`${TEST_AI}capabilities`).flush(NO_AI);

    service.suggestTopology({ description: 'letters', sample: 'collections/a', context: null }).subscribe();
    const topology = http.expectOne({ method: 'POST', url: `${TEST_AI}topology-suggestions` });
    expect(topology.request.body).toEqual({ description: 'letters', sample: 'collections/a', context: null });
    topology.flush({});

    service.describeMedia('books/cover.png').subscribe();
    const media = http.expectOne({ method: 'POST', url: `${TEST_AI}media-metadata` });
    expect(media.request.body).toEqual({ path: 'books/cover.png' });
    media.flush({});

    service.modelTopics('communities/lib').subscribe();
    const topics = http.expectOne({ method: 'POST', url: `${TEST_AI}topic-models` });
    expect(topics.request.body).toEqual({ path: 'communities/lib' });
    topics.flush({});

    service.suggestProfile('profiles/curie').subscribe();
    const profile = http.expectOne({ method: 'POST', url: `${TEST_AI}profile-suggestions` });
    expect(profile.request.body).toEqual({ path: 'profiles/curie' });
    profile.flush({});
  });
});

describe('TopologyDraftStore', () => {
  it('hands a draft to the editor once', () => {
    const store = new TopologyDraftStore();
    const draft = { id: 'letters', name: 'Letters', builtIn: false, root: emptyNode('letter', 'Letter') };
    expect(store.take()).toBeUndefined();
    store.offer(draft);
    expect(store.take()).toBe(draft);
    expect(store.take()).toBeUndefined();
  });
});
