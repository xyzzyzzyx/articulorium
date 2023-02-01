# Service

Hosts the Angular user interface (`ui` module) and the small REST API it uses:

| Path                 | What                                                       |
|----------------------|------------------------------------------------------------|
| `/`, client routes   | the single page application (HTML5 routing)                |
| `/api/config`        | settings for the UI (platform, API docs, topology and transform URLs, title, community path) |
| `/api/agents`        | the agents known to the service (`agent` module)           |
| `/api/media-type`    | media type detection of an uploaded file (Apache Tika)     |
| `/api/swagger`       | Swagger UI; the OpenAPI document is at `/api/docs`         |
| `:8000/actuator/...` | health, info, metrics                                      |

## Development

```bash
mvn -pl service -am spring-boot:run -Dspring-boot.run.arguments=--spring.cloud.consul.enabled=false
npm --prefix ui run watch        # rebuilds the UI; devtools reloads it
```

Behind the gateway the UI reaches the platform at `platform/` (relative to the UI).
