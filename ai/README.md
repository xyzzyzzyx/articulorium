# Articulorium AI agent

An optional agent built on [Spring AI](https://docs.spring.io/spring-ai/reference/) that helps
people working with the repository. It **only suggests**: every answer goes back to the caller, who
reviews it and applies what is right with the existing APIs (the platform's LDP API, the topology
API, the harvest review API). The agent never writes to the repository.

```bash
# the agent, Ollama and its model (several GB on first start); the arxiv source harvests on start
# and its records are classified for review on the Harvest page
docker compose --profile ai up --build
curl -s http://localhost:8000/ai/capabilities
# without classifying harvested records
ARTICULORIUM_HARVEST_CLASSIFICATION=false docker compose --profile ai up --build
```

Without the `ai` profile the service is not deployed: the gateway answers `/ai/**` with 503 and the
user interface hides every AI action. Records are still harvested; with classification enabled
(the compose default) their classifications are recorded as failed, to retry from the review queue
once the agent runs.

## Capabilities

| Capability       | Agent name               | API                          | In the user interface |
|------------------|--------------------------|------------------------------|-----------------------|
| `topology`       | `ai-topology-assistant`  | `POST /topology-suggestions` | **Topologies → Suggest a topology**: describe the material (and optionally name a container holding an example); the agent recommends an existing topology or drafts a new one, which opens in the topology editor to change and save. |
| `media`          | `ai-media-describer`     | `POST /media-metadata`       | **AI metadata suggestions** on images, videos and PDFs: technical and embedded metadata read from the file (EXIF/IPTC/XMP, video containers, PDF information and text) and descriptive metadata from the model; the statements kept are added to the binary's description. |
| `topics`         | `ai-topic-modeler`       | `POST /topic-models`         | **AI topics** on communities and collections: topics (with keywords and member items) and a summary from the items' texts; the topics kept become `dcterms:subject`, the summary `dcterms:abstract`. |
| `profiles`       | `ai-profile-enhancer`    | `POST /profile-suggestions`  | **AI profile suggestions** on academic (VIVO) profiles: an overview, research areas and keywords drawn from the profile's positions, degrees and publications, leaving out what it has. What is kept is merged into the profile and saved as the profile editor saves it (one replacement guarded by the entity tag), so research areas become the editor's own `skos:Concept`s and a profile changed meanwhile is never overwritten. |
| `classification` | `ai-harvest-classifier`  | `POST /classifications`      | Used by the harvest service (see [Classifying harvested records](#classifying-harvested-records)). |

`GET /capabilities` (public: it reveals no repository content) tells whether the agent is enabled,
whether a model is configured, the model's name and which capabilities can be used now. The OpenAPI
description is at `/ai/v3/api-docs` and in the gateway's Swagger UI.

## Capabilities are Articulorium agents

The [agent library](../agent/README.md) is the one catalogue of automated actors, and the AI
capabilities are agents in it. Each capability is a `CapabilityAgent`: an `AgentContributor` (agent
library) that answers its `Agent` while `articulorium.ai.enabled` and its own
`articulorium.ai.<key>.enabled` are on. The [agent starter](../agent-spring-boot-starter/README.md)
collects them into the `AgentRegistry`, which `/actuator/info` reports and from which
`GET /capabilities` answers (a capability is offered when its agent is registered and a model is
configured), so configuration, registry, info and user interface always agree.

A new capability is one `CapabilityAgent` subclass, a `Capability` constant (key, agent name,
description), its settings under `articulorium.ai.<key>`, an endpoint and its user interface; the
model calls, access check, limits, metrics and errors are shared (`AgentModel`, `AgentAccess`,
`PlatformReader`, `AgentExceptionHandler`).

## Access and privacy

- **Who may ask.** A suggestion about a resource needs `write` access to the resource it would
  change (a binary's description, a community or collection, the container of custom topologies,
  the container of harvested records), as the platform's Web Access Control decides for the
  caller's own credentials. Anonymous callers get 401 and readers 403, so model calls are spent
  only on people who could act on the answer.
- **What the model sees.** Everything the agent reads while preparing a suggestion is read with the
  caller's credentials: a caller never learns, through the model, about content it may not read.
- **Which resource is asked about.** Paths with `.` or `..` segments, queries, fragments or
  characters URIs do not allow are refused (400) before anything is read, so the resource whose
  access is checked is the resource that is read (`ResourcePaths.isSafe`, shared with the harvest
  review API).
- **What the model can write.** Media suggestions use a fixed set of Dublin Core and EBUCore terms;
  classifications choose only among the collections and topologies the model was shown (any other
  answer becomes "no placement" for a person to decide). Proposed communities, collections and
  topologies are only proposals until a person accepts them.
- **Where content goes.** The default provider is Ollama inside the deployment, so repository
  content never leaves it. Prompts are never logged: logs and the `ai.model.calls` timer record
  the capability, outcome, duration and prompt size only. Failures of the model or the provider are
  logged with their details; callers get the reason only, never internal hosts or provider messages.

## Errors

Errors are RFC 9457 problem documents with a `reason`; the user interface explains each one and
the rest of the page keeps working.

| Status | `reason`               | When |
|--------|------------------------|------|
| 400    | `INVALID_REQUEST`      | The request is missing or malformed, or the path is not a resource path. |
| 401    | `UNAUTHENTICATED`      | Anonymous caller without write access. |
| 403    | `FORBIDDEN`            | Known caller without write access. |
| 404    | `NOT_FOUND`            | The resource does not exist (or the caller may not read it). |
| 413    | `TOO_LARGE`            | An image or PDF over `media.max-bytes` (larger videos are described from their context). |
| 415    | `UNSUPPORTED`          | Not an image, video or PDF; not a community or collection; not a profile. |
| 429    | `BUSY`                 | Every model slot stayed busy for `model.queue-timeout`. |
| 502    | `MODEL_ERROR`          | The model failed or timed out. |
| 502    | `INVALID_OUTPUT`       | The model's answer could not be used. |
| 503    | `DISABLED`             | `articulorium.ai.enabled=false`. |
| 503    | `CAPABILITY_DISABLED`  | The capability is turned off. |
| 503    | `MODEL_UNAVAILABLE`    | No chat model configured (`spring.ai.model.chat=none`). |
| 503    | `PLATFORM_UNAVAILABLE` | The platform or topology service could not be reached. |

## Classifying harvested records

With `articulorium.harvest.classification.enabled=true` (compose: on by default,
`ARTICULORIUM_HARVEST_CLASSIFICATION=false` turns it off) the harvest service ingests records exactly as before,
then asks the agent where each new record belongs:

1. The agent compares the record (texts, types, sets, file media types) with the community
   structure and topologies the harvester may read, and suggests an existing collection, or a new
   collection or community, and the topology of its item, with a confidence and its reasons.
2. The suggestion is stored below the record (`<record>/classification`, typed
   `art:ReviewPending` while open). A record the agent cannot classify (not deployed, no model,
   failing) is stored as `FAILED` with the reason, to retry later: **the harvest never fails because
   of the agent**.
3. A person reviews the queue on the **Harvest** page: accept the suggestion, place the record in
   another (or an alternative) collection, create the proposed collection or community and accept,
   reject (the record stays where it was harvested), or classify again. Suggestions below
   `review-threshold` are flagged as uncertain.
4. An accepted record becomes an item of its collection, built with its topology like any other
   item; the harvested copy becomes a pointer to the item (`art:HarvestPlacement`) so the record is
   held once and later harvests update the item. Every write uses the credentials of whoever
   accepted, so the platform decides whether they may add items there.

`auto-accept-threshold` (unset by default) accepts suggestions of an existing collection with an
existing topology (or none) at least that sure without a person; proposals of new structures and
uncertain suggestions always wait for review. Sources whose records do not belong in collections
(VIVO profiles) set `classify: false`. The `harvest.classifications` counter records outcomes
(`pending`, `auto-accepted`, `failed`, `unrecorded`) by source. The review API is described in
[harvest/README.md](../harvest/README.md#the-harvest-api-and-the-user-interface).

## Configuration

The chat model is Spring AI's own configuration; choose the provider with `spring.ai.model.chat`
(`ARTICULORIUM_AI_PROVIDER`):

| Provider | Settings |
|----------|----------|
| `ollama` (compose default) | `SPRING_AI_OLLAMA_BASE_URL`, `SPRING_AI_OLLAMA_CHAT_MODEL` (`ARTICULORIUM_AI_MODEL`, default `qwen2.5vl:3b`). A vision model (qwen2.5vl, llama3.2-vision) describes images; others describe them from their metadata. Models are pulled by the `ollama-model` service, never at start-up. |
| `openai` | OpenAI or any OpenAI-compatible endpoint (vLLM, LM Studio, LiteLLM, a hosted provider): `SPRING_AI_OPENAI_API_KEY`, `SPRING_AI_OPENAI_BASE_URL`, `SPRING_AI_OPENAI_CHAT_MODEL`. Keep keys in an `.env` file or Consul, **never in source control**. Build without the OpenAI SDK with `-Dai.openai.skip`. |
| `none` (application default) | No model: the agent answers 503 `MODEL_UNAVAILABLE` and the user interface hides it. |

Agent settings (`articulorium.ai.*`):

| Setting | Default | Meaning |
|---------|---------|---------|
| `enabled` | `false` (`ARTICULORIUM_AI_ENABLED`) | Whether the agent answers at all. |
| `platform-url`, `topology-url` | Consul services | Where the platform's LDP root and the topology API are reached (`load-balanced: true`). |
| `communities`, `topologies` | `communities`, `topologies` | Containers of the community structure and of custom topologies. |
| `model.max-concurrency` | `2` | Model calls at once; more wait up to `model.queue-timeout` (`30s`), then 429. |
| `<capability>.enabled` | `true` | Turns one capability off (`topology`, `media`, `topics`, `profiles`, `classification`). |
| `topology.max-sample-resources` | `40` | Children of a sample container shown to the model. |
| `topology.max-description-length` | `4000` | Longest description of the material. |
| `media.max-bytes` | `50MB` | Largest binary read. |
| `media.image-input` / `media.max-image-bytes` | `true` / `10MB` | Whether (and up to which size) JPEG, PNG, GIF and WebP images are sent to the model. |
| `media.max-pdf-pages` / `media.max-text-length` | `20` / `12000` | PDF text read and shown to the model. |
| `topics.max-documents` / `max-document-length` / `max-topics` | `100` / `600` / `8` | Items read, characters per item, topics returned. |
| `profiles.max-research-areas` / `max-keywords` | `8` / `12` | Research areas and keywords suggested at most. |
| `profiles.max-entries` | `50` | Positions, degrees and publications of a profile shown to the model, each. |
| `profiles.max-overview-length` | `2000` | Longest overview suggested. |
| `classification.max-candidates` | `200` | Collections offered to the model. |
| `classification.catalog-ttl` | `1m` | How long the structure read for one caller is reused. |
| `classification.max-record-length` | `2000` | Characters of a record shown to the model. |

Harvest settings (`articulorium.harvest.classification.*`): `enabled` (`false`), `ai-url`,
`topology-url`, `timeout` (`2m`, after which a classification is recorded as failed),
`auto-accept-threshold` (unset), `review-threshold` (`0.5`).

## Limitations and follow-ups

- Model output is not deterministic; the end-to-end tests stub the agent's answers and check the
  review and apply flows against the real services. Prompt quality is best checked by hand with the
  model you deploy.
- Videos are never shown to the model, only their container metadata and context.
- Profile suggestions draw on what the profile records; publications held in the repository but not
  listed on the profile are not looked up yet.
- Topic models read at most `topics.max-documents` items; very large collections are summarised
  from a sample (the answer says so with `truncated`).
- A classification that proposes a new topology is not created from the review queue: a person
  drafts the topology on the Topologies page, then places the record with **Place elsewhere**.
