---
name: angular-best-practices
description: How to build Angular 22 / TypeScript features in Articulorium's ui and discovery-ui — zoneless signals, standalone components, services over HttpClient, routing, forms, Angular Material, i18n, SSR and accessibility, and Vitest/Cypress tests. Use when writing or changing TypeScript, templates or styles in either front end.
---

# Angular and TypeScript best practices (Articulorium)

How to design front-end changes. The `code-quality` skill holds the rules shared with the rest of
the repository and the verification commands. Read the component next to the one you are adding
before you write it, and match it.

## The two applications

| | `ui` | `discovery-ui` |
|---|---|---|
| Purpose | repository management (browse, edit, permissions, harvest, AI) | public search and item pages |
| Prefix | `ui-` | `disc-` |
| Rendering | browser only, OIDC login | server-side rendering + hydration (`withEventReplay`, HTTP transfer cache) |
| State | signals in components and root services (the NgRx store is provided but holds no features: do not add NgRx state) | signals and services |

Both: Angular 22, `provideZonelessChangeDetection()`, `provideHttpClient(withFetch())`,
ngx-translate, Angular Material, Vitest, ESLint + Prettier.

## Components

- Standalone, `ChangeDetectionStrategy.OnPush`, dependencies through `inject()` in field
  initializers (`private readonly http = inject(HttpClient)`).
- Inputs and outputs with `input()`, `input.required()`, `output()`, `model()`; no `@Input` /
  `@Output` decorators. Route parameters arrive as inputs (`withComponentInputBinding()`).
- State is `signal()`; derived values are `computed()`; side effects that must follow a signal
  are `effect()`; loading data is `rxResource` (or `resource`) keyed by the signals it depends on.
- Built-in control flow (`@if`, `@for` with `track`, `@switch`), never `*ngIf`/`*ngFor`.
- Zoneless means the view only updates from signals, events and `async` bindings: write results
  into signals, never into plain fields from a subscription.
- One-shot actions (save, delete, ask the AI) subscribe once, set a `busy` signal and clear it in
  `finalize`, report errors through `Notifier`. Long-lived streams use `rxResource` or
  `toSignal`, so nothing needs manual unsubscription.
- Keep components focused: data access in `core/<feature>/<feature>.service.ts`, pure helpers
  and types in `core/<feature>/<feature>.ts` (tested on their own), dialogs and shared widgets in
  `shared/`.
- Styles stay in the component, classes carry the app prefix, colours and spacing use the
  existing CSS variables, layouts use CSS grid/flex and logical properties so right-to-left works.

## Services and HTTP

- `@Injectable({ providedIn: 'root' })`, one service per backend API, methods return typed
  `Observable`s from `HttpClient` (`this.http.get<HarvestSources>(…)`).
- Service base URLs come from `ConfigService`; build URLs with `encodeURIComponent` per segment
  and `HttpParams` for queries, never string-joined user input.
- Cross-cutting concerns are functional interceptors (`authInterceptor`, `localeInterceptor`):
  the bearer token is only attached to configured service bases.
- Errors: let `HttpErrorResponse` reach the caller; show the problem document's `detail` when
  present, a translated generic message otherwise; treat 401/403 as "sign in / not allowed", not
  as a crash.
- Types for API payloads are TypeScript interfaces matching the Java records; no `any`, prefer
  `unknown` + narrowing for untyped input, `Readonly`/`readonly` for constants.

## Routing

- Every page is lazy: `loadComponent: () => import('./x/x-page.component').then((m) => m.X)`.
- Route `title`s are translation keys resolved by `LocalizedTitleStrategy`.
- Resource paths have any number of segments: use `viewMatcher(...)`, not `**` hacks.
- Guards and resolvers are functions using `inject()`.

## Forms

- Existing forms use template-driven `FormsModule` bound to signals; follow that for small forms.
  A large form with cross-field rules may use typed reactive forms (`NonNullableFormBuilder`).
- Every field has a `mat-label`, validation messages are translated, and submit is disabled while
  busy.

## Angular Material and accessibility

- Material components and the established theme first; import the Material modules each
  component uses.
- Icons get text alternatives or `aria-hidden="true"` when decorative; icon-only buttons get an
  `aria-label` (translated).
- Dialogs go through `MatDialog` with the shared `ConfirmDialogComponent` for confirmations.
- Lint runs the Angular template accessibility rules; discovery-ui also keeps landmarks, one
  `h1`, `aria-current` and visible focus.

## Internationalization

- Every visible string is a key in `src/i18n/en.json` and the 11 other locale files, with the
  same `{{placeholders}}` and the plural categories each language needs (`{ "one", "other" }`,
  Polish `few`/`many`, Arabic six).
- Dates, numbers and durations through the localized pipes (`LocalizedDatePipe`,
  `LocalizedNumberPipe`) / `Intl`, never `toLocaleString()` with no locale or hand formatting.
- Run `npm run i18n:check` after touching texts.

## discovery-ui on the server

- Reach the page through `inject(DOCUMENT)`, the request through `inject(REQUEST, { optional:
  true })`; no `window`, `localStorage`, timers that never end, or browser-only libraries at
  import time (load Mirador and similar lazily in the browser).
- Data fetched on the server is reused by the HTTP transfer cache; do not refetch on hydration.
- Set page metadata through the SEO service; respect `NG_ALLOWED_HOSTS`.

## TypeScript

- `strict` everywhere: no non-null assertions to silence the compiler, no `as` casts of API data
  without a check, exhaustive `switch` over union types with a `never` default.
- Small pure functions exported from `core/<feature>/<feature>.ts` for anything computed (states,
  severities, durations) so they can be unit tested without TestBed.
- TSDoc on exported symbols (`@param name - …`, `@returns …`), as in `app.routes.ts`.

## Tests

- **Services:** TestBed with `provideHttpClient()`, `provideHttpClientTesting()`,
  `provideTestConfig()`; assert method, URL and body with `HttpTestingController.expectOne`, flush
  a realistic body, `http.verify()` in `afterEach`.
- **Components:** render with TestBed, drive through the DOM (buttons, inputs), flush HTTP, then
  `await fixture.whenStable()` (zoneless) before asserting; cover loading, error and empty states.
  Override tunables via their `InjectionToken` (poll intervals) instead of waiting.
- **Pure helpers:** plain Vitest tests.
- **E2E:** a changed user flow gets a Cypress case in `e2e/cypress/e2e/<page>.cy.ts`, selecting
  by role, label or visible text; stub only states the stack cannot produce, with the real
  response shape.
