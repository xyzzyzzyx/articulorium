# Activity Streams

Library module for [W3C Activity Streams 2.0](https://www.w3.org/TR/activitystreams-core/):
the [Activity Vocabulary](https://www.w3.org/TR/activitystreams-vocabulary/) types, an immutable
object model, JSON serialization and validation. It is plain Java plus Jackson, with no framework
dependencies (the build fails if Spring reaches its compile or runtime class path). The platform
uses it for its COAR Notify inboxes and outboxes.

```bash
mvn -pl activitystreams verify
```

## Usage

```java
ActivityStreamsJson json = new ActivityStreamsJson();          // or new ActivityStreamsJson(springsMapper)

// read: strict JSON (one object, no duplicate properties), no Activity Streams checks
AsObject activity = json.read(bytes);
activity.type();                    // Optional[CREATE]
activity.actor().getFirst().id();   // Optional[https://example.org/sally]
activity.object().getFirst().isA(AsType.NOTE);
activity.published();               // Optional[2015-01-25T12:34:56Z] (OffsetDateTime)
activity.references("to");          // [https://www.w3.org/ns/activitystreams#Public, ...]

// validate
List<Violation> problems = ActivityStreamsValidator.strict().validate(activity);
// or read and validate in one step; throws ActivityStreamsException with the violations
AsObject valid = json.readValid(bytes, contentType);

// build and write
AsObject like = AsObject.builder(AsType.LIKE)
    .id("urn:uuid:5e8d2b0c-0f0e-4e5b-9d7a-6a3b7f1c2d10")
    .actor(AsObject.builder(AsType.PERSON).id("https://a.example/alice").name("Alice").build())
    .object("https://b.example/notes/1")
    .published(Instant.now())
    .build();
byte[] document = json.write(like);
// {"@context":"https://www.w3.org/ns/activitystreams","id":"urn:uuid:...","type":"Like",...}
```

| Class                      | What                                                                |
|----------------------------|---------------------------------------------------------------------|
| `ActivityStreams`          | Context and namespace IRIs, `as:Public`, media types (`application/activity+json`, `application/ld+json; profile="https://www.w3.org/ns/activitystreams"`) |
| `AsType`                   | The 54 types of the vocabulary, each with its kind: object, link, activity, intransitive activity, actor, collection |
| `AsObject`                 | An object, activity, actor, collection or link: the document's JSON, immutable, with typed accessors and a builder |
| `ActivityStreamsJson`      | Reads and writes documents                                          |
| `ActivityStreamsValidator` | Checks documents; `strict()` requires the context, `lenient()` implies it |
| `Violation`                | A problem: a property path (`object.attachment[0].href`) and a message |

## Model

Activity Streams is open: objects may carry extension properties, and most properties take an
IRI, an embedded object, or a list of both. `AsObject` therefore keeps the JSON exactly (an ordered
map of strings, numbers, booleans, `null`s, lists and maps) instead of mapping it to fixed classes,
so nothing an extension adds is lost. Accessors read the common properties without caring about
the shape: `actor()`, `object()`, `target()` and `objects(name)` return embedded objects (an IRI
becomes an object with only an `id`), `references(name)` and `url()` return IRIs (the `href` of a
link), `published()`, `updated()` and `dateTime(name)` return `OffsetDateTime`s. Values of the
wrong shape read as empty rather than failing; the validator reports them.

Writing is deterministic: `@context`, `id` and `type` first, the other properties in their order,
and the Activity Streams context added to documents without one. Reading what was written gives an
equal object and writing it again the same bytes.

## Validation

On the document and every embedded object (up to 32 levels):

* `@context` includes `https://www.w3.org/ns/activitystreams` (strict only) and holds IRIs or
  context objects;
* the document has a `type`; every `type` is a vocabulary term (also as `as:Note` or a full IRI),
  an extension type as a compact or absolute IRI (`coar-notify:ReviewAction`), or any term when
  `@context` adds further contexts;
* `id` and `href` are absolute IRIs; object properties (`actor`, `object`, `target`, `origin`,
  `result`, `instrument`, `to`, `cc`, `bto`, `bcc`, `audience`, `attributedTo`, `url`, `tag`,
  `attachment`, `items`, `orderedItems`, `inReplyTo`, `location`, `icon`, `image`, ...) hold IRIs,
  objects or lists of them;
* `published`, `updated`, `startTime`, `endTime` and `deleted` are RFC 3339 date-times with an
  offset; `duration` is an `xsd:duration`;
* `name`, `summary` and `content` are strings, their `…Map` forms objects of language tags to
  strings; `mediaType` is a media type, `hreflang` a language tag, `rel` link relations;
* `totalItems`, `startIndex`, `width` and `height` are non-negative integers; `accuracy` is
  0–100, `latitude` ±90, `longitude` ±180, `radius` non-negative, `units` one of the listed
  units or an IRI; `closed` a boolean, a date-time or an object;
* links (`Link`, `Mention`) have an `href`; intransitive activities (`Arrive`, `Travel`,
  `Question`) have no `object`; a `Question` does not have both `oneOf` and `anyOf`.

## Limitations

* No JSON-LD processing: documents are handled in their compacted form. Terms are recognised as
  written; a context that renames or redefines Activity Streams terms is not expanded, and
  extension contexts are not fetched (any type is accepted once a document names one).
* Recommendations the specifications leave to applications are not enforced: `id` and `actor` are
  optional (COAR Notify, for example, requires its own set on top), and the ActivityPub server and
  client rules (inbox delivery, `Follow` side effects, collections paging) are out of scope.
* Language maps are validated and kept, but the typed accessors read only the plain form
  (`name()`, not `nameMap`).
