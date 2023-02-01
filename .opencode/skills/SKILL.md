## Enhanced Patterns for Articulorium

### Spring Boot Application Structure
- **Module-based architecture**: Articulorium uses a Maven reactor POM with clearly defined modules (platform, transform, index, triplestore, topology, harvest, ai, etc.)
- **Shared dependencies management**: Centralized dependency management through `spring-boot-starter-parent` and `spring-cloud-dependencies`
- **Configuration patterns**: Using `application.yml` with environment variable overrides and Consul-based configuration
- **Service discovery**: Integration with Spring Cloud Consul for service registration and discovery

### Microservices Patterns
- **Event-driven architecture**: Utilizes Spring Cloud Bus and Spring Integration for event dispatching
- **Circuit breaker patterns**: Implemented through Spring Cloud Circuit Breaker
- **API gateway pattern**: Gateway module handles routing, security, and monitoring
- **Service-to-service communication**: Uses Spring Cloud LoadBalancer for service discovery

### Spring AI Integration Patterns
- **Agent-based design**: The `ai` module implements the agent library pattern with capability agents
- **Model configuration patterns**: Supports multiple providers (Ollama, OpenAI) through Spring AI configuration
- **Access control integration**: AI capabilities respect Web Access Control for repository content
- **Error handling patterns**: RFC 9457 problem documents for AI-related errors
- **Async processing**: Non-blocking model calls with concurrency limits

### Domain-Specific Patterns
- **Linked Data Platform**: Implementation of LDP (Linked Data Platform) specifications
- **OCFL Storage**: Oxford Common File Layout integration for content storage
- **SPARQL Store**: Integration with QLever for triple store functionality
- **Internationalization**: Full 12-language support with proper locale handling
- **Notification patterns**: COAR Notify implementation for Linked Data Notifications

### Testing Patterns
- **Integration testing**: Multi-module test strategy using Spring Boot Test
- **End-to-end testing**: Including API tests and integration with external services
- **Performance testing**: With load balancer and concurrency considerations
- **Mocking patterns**: Using Mockito for service isolation in tests

### Security Patterns
- **Web Access Control (WebAC)**: Implementation of platform security through WebAC
- **OpenID Connect integration**: JWT validation using Nimbus JOSE + JWT
- **Secure configuration management**: Sensitive data kept out of source control
- **API security patterns**: Proper authentication and authorization for each service

### Performance and Scalability Patterns
- **Database optimization**: Hibernate with proper DDL auto-update
- **Caching strategies**: Application-level caching with proper invalidation
- **Resource management**: Proper lifecycle management of services
- **Monitoring and observability**: Micrometer metrics integration

### Configuration Management
- **Environment-specific configuration**: Profile-based configuration (`default`, `native`)
- **Externalized configuration**: Consul key/value store for shared settings
- **Configuration validation**: Runtime checks for valid configurations
- **Secrets management**: Keeping sensitive data out of source control

## Integration with Articulorium's AI Capabilities

The skill integrates with the project's AI agent by providing:
- Patterns for implementing AI capabilities as `CapabilityAgent` subclasses
- Best practices for integrating AI with existing LDP APIs
- Guidelines for handling AI-generated content within repository constraints
- Recommendations for configuring different AI providers (Ollama, OpenAI)
- Error handling patterns specific to AI model interactions

## Context-Aware Suggestions

This skill automatically adapts to:
- The programming language and frameworks in use (Java 25, Spring Boot 4.1, Spring Cloud)
- The specific services in the Articulorium codebase
- The microservices architecture patterns being implemented
- The AI capabilities and their integration points
- The internationalization requirements of the application

## Usage Guidelines

When working with this project:
1. Follow the established module structure for new features
2. Use Spring Boot's auto-configuration where possible
3. Implement proper service discovery through Consul
4. Respect Web Access Control in all AI interactions
5. Configure external services through environment variables or Consul
6. Ensure proper error handling with RFC 9457 problem documents
7. Follow internationalization best practices for all user-facing content

## Additional Considerations

- **GraalVM native image support**: Profile-based compilation for performance
- **Multi-language support**: Complete internationalization with proper text handling
- **Distributed system patterns**: Proper handling of service communication and failures
- **Content management**: OCFL storage integration for repository content
- **Metadata handling**: Rich metadata support through RDF and Linked Data principles
