# Articulorium i18n Spring Boot starter

Locale support for every Articulorium service, in one place. Add the dependency and a service
answers in the locale of each request:

```xml
<dependency>
  <groupId>com.articulorium</groupId>
  <artifactId>i18n-spring-boot-starter</artifactId>
</dependency>
```

## What it configures

| Bean | Purpose |
|------|---------|
| `SupportedLocales` | The enabled locales: the shipped manifest [`articulorium/locales.properties`](src/main/resources/articulorium/locales.properties), narrowed by `articulorium.i18n.locales`, and the default locale. Negotiates `Accept-Language` (exact tag, then same language, then the default). |
| `localeResolver` (Spring MVC only) | `RequestLocaleResolver`: `?lang=`, the `articulorium-locale` cookie, `Accept-Language`, the default locale. Every request gets an enabled locale; `LocaleContextHolder` and Thymeleaf's `#{...}` use it. |
| `messageSource` | `i18n/messages` (the service's own texts) then `i18n/common` (shared texts, such as summaries of HTTP statuses), UTF-8 `MessageFormat` patterns, falling back to the default locale, never to the server's system locale. |
| `Messages` | `messages.get("code", args)` in the request's locale; `status(404)`; `descriptor()` (tag, name, direction) for `lang`/`dir` and `Content-Language`. `Messages.of(locales)` builds one without Spring (tests, tools). |
| `AcceptLanguageInterceptor` | Adds the request's locale as `Accept-Language` to calls to other services (`RestClient.Builder#requestInterceptor`). |
| start-up check | Logs every translation missing from the service's and the shared bundles (`articulorium.i18n.verify-bundles`). |
| native hints | The manifest and every `i18n/*.properties` are included in GraalVM native images. |

## Settings

| Property | Default | Meaning |
|----------|---------|---------|
| `articulorium.i18n.enabled` | `true` | Turns the auto-configuration off. |
| `articulorium.i18n.default-locale` | `en` | Locale of unsupported requests and of texts a translation lacks; must be enabled. |
| `articulorium.i18n.locales` | every shipped locale | Enabled locales (BCP 47 tags) in menu order. A tag missing from the manifest is accepted and uses the default locale's texts. |
| `articulorium.i18n.cookie-name` | `articulorium-locale` | Cookie remembering the user's choice (the user interface writes it with path `/`). |
| `articulorium.i18n.parameter-name` | `lang` | Query parameter choosing the locale of one request. |
| `articulorium.i18n.verify-bundles` | `true` | Logs missing translations at start-up. |

Use the same values in every service (Consul key/value configuration shares them).

## Message bundles

A service keeps its texts in `src/main/resources/i18n/messages.properties` (English, the default
locale) and one `messages_<tag>.properties` per shipped locale (`messages_pt_BR.properties` for
`pt-BR`). Every text is a `java.text.MessageFormat` pattern, also without arguments: write an
apostrophe as `''`. Test completeness in the module:

```java
final List<Locale> locales = LocaleManifest.load().stream().map(LocaleDescriptor::locale).toList();
assertThat(MessageBundles.verify(I18nAutoConfiguration.MESSAGES, locales, Locale.ENGLISH)).isEmpty();
```

`MessageBundles.verify` reports missing files, missing and unknown keys, different arguments and
single apostrophes.

## Adding a shipped locale

Add a line to the manifest (`sv = Svenska`, with `, rtl` for right-to-left scripts) and the
translations it requires; see [Internationalization](../README.md#internationalization).
