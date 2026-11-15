# Articulorium Security and Configuration Documentation

## Overview

This document provides comprehensive guidance for properly configuring Articulorium with secure authentication and access control settings, addressing security vulnerabilities identified in the codebase review.

## Reporting a Vulnerability

Please report vulnerabilities privately through GitHub's
[security advisories](https://github.com/xyzzyzzyx/articulorium/security/advisories/new) rather than
in a public issue, with the affected module, a description and, where possible, steps to reproduce.

## Security Vulnerabilities

The current default configuration uses a permissive root ACL (`root-acl.ttl`) that grants everyone full access to the repository. This is suitable for development but represents a significant security risk for production deployments.

## Secure Configuration Guide

### 1. Switching to Role-Based Access Control

To enable role-based access control, set the platform's root ACL. No rebuild is needed: set the
environment variable on the platform service (`docker-compose.yml` has it commented out on the
`platform` service; `docker-compose.acl.yml` sets the Consul variant):

```bash
ARTICULORIUM_WEBAC_ROOT_ACL=classpath:webac/root-acl-oidc.ttl
```

or the equivalent property in an external configuration file or Consul:

```yaml
articulorium:
  webac:
    root-acl: classpath:webac/root-acl-oidc.ttl
```

This switches from the default permissive ACL to one that uses OIDC groups for access control.

### 2. Understanding Access Control Roles

`keycloak/articulorium-realm.json` defines two realm roles, which the platform maps to the groups
`info:oidc/group/<role>` that `root-acl-oidc.ttl` names:

- **No role** (demo user `reader`): reads, like anonymous visitors
- **`editors`** (demo user `curator`): reads and writes resources
- **`administrators`** (demo user `administrator`, also an editor): reads, writes and controls access
  (changes ACLs)

The demo users and their passwords are for local use only; remove them from a production realm.

### 3. Production Security Configuration

#### Keycloak Realm Settings

The realm configuration in `keycloak/articulorium-realm.json` should be reviewed for production use:

1. **Password Security**: Replace hardcoded passwords with secure environment variables
2. **Token Lifespans**: Adjust token expiration times for your security requirements
3. **Client Configuration**: Review redirect URIs and CORS settings

#### Platform Security Settings

The defaults in `platform/src/main/resources/application.yml` (override them with the environment
variables they name):
```yaml
# Enable OIDC authentication
articulorium:
  webac:
    oidc:
      enabled: true
      # Ensure these are properly configured for your deployment
      issuer-uri: ${KEYCLOAK_PUBLIC_URL:http://localhost:8000/auth}/realms/${KEYCLOAK_REALM:articulorium}
      jwk-set-uri: ${KEYCLOAK_INTERNAL_URL:http://keycloak:8080/auth}/realms/${KEYCLOAK_REALM:articulorium}/protocol/openid-connect/certs
```

### 4. Environment Variable Configuration

For production deployments, use environment variables to override sensitive settings:

```bash
# In your .env file or deployment configuration
KEYCLOAK_PUBLIC_URL=http://your-domain.com/auth
KEYCLOAK_INTERNAL_URL=http://keycloak:8080/auth
KEYCLOAK_REALM=articulorium
POSTGRES_HOST=your-postgres-host
POSTGRES_DB=articulorium
POSTGRES_USER=articulorium
POSTGRES_PASSWORD=secure-password-here
```

### 5. Root ACL Configuration

The system provides two root ACL options:

#### Default Permissive ACL (`root-acl.ttl`)
```turtle
@prefix acl: <http://www.w3.org/ns/auth/acl#> .
@prefix foaf: <http://xmlns.com/foaf/0.1/> .

<#public>
    a acl:Authorization ;
    acl:agentClass foaf:Agent ;
    acl:accessTo <> ;
    acl:default <> ;
    acl:mode acl:Read, acl:Write, acl:Control .
```

#### Secure OIDC ACL (`root-acl-oidc.ttl`)
```turtle
@prefix acl: <http://www.w3.org/ns/auth/acl#> .
@prefix foaf: <http://xmlns.com/foaf/0.1/> .

<#public>
    a acl:Authorization ;
    acl:agentClass foaf:Agent ;
    acl:accessTo <> ;
    acl:default <> ;
    acl:mode acl:Read .

<#editors>
    a acl:Authorization ;
    acl:agentGroup <info:oidc/group/editors> ;
    acl:accessTo <> ;
    acl:default <> ;
    acl:mode acl:Read, acl:Write .

<#administrators>
    a acl:Authorization ;
    acl:agentGroup <info:oidc/group/administrators> ;
    acl:accessTo <> ;
    acl:default <> ;
    acl:mode acl:Read, acl:Write, acl:Control .
```

### 6. Deployment Best Practices

#### For Production Environments:

1. **Always Use Secure ACL Configuration**
   ```yaml
   articulorium:
     webac:
       root-acl: classpath:webac/root-acl-oidc.ttl
   ```

2. **Secure Keycloak Configuration**
   - Set strong passwords for all users
   - Configure proper SSL/TLS settings
   - Review and restrict client redirect URIs

3. **Database Security**
   - Use strong database credentials
   - Enable connection pooling with appropriate limits
   - Configure backup and recovery procedures

4. **Network Security**
   - Ensure HTTPS is enabled for all services
   - Configure proper firewall rules
   - Set up monitoring and alerting for security events
   - Behind a TLS-terminating proxy, restrict the gateway's `trusted-proxies` to that proxy and
     stop removing `X-Forwarded-Proto`/`-Host` (see the comments in `gateway/.../application.yml`)

5. **Replace the Compose Stack's Development Defaults**
   - Keycloak runs with `start-dev` and the bootstrap administrator `admin` / `admin`
     (`KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD`)
   - MinIO: `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD`; QLever: `QLEVER_ACCESS_TOKEN`; Fuseki:
     `FUSEKI_ADMIN_PASSWORD`; PostgreSQL: `POSTGRES_PASSWORD`
   - Kafka listens in plain text without authentication: keep it on a private network

6. **Authentication Providers**
   - `articulorium.webac.trusted-agent-header` trusts whatever the header says, and the gateway does
     not remove it: enable it only when the authenticating proxy is the platform's sole entry
     point and overwrites the header on every request
   - Consul ACL tokens are identified by their free-text description: let only administrators
     create tokens, and grant access to roles rather than to individual agents

### 7. Development vs Production Settings

#### Development (Default)
- Permissive root ACL (`root-acl.ttl`)
- Default Keycloak realm with test users
- Simplified configuration for easy setup

#### Production (Secure)
- Role-based root ACL (`root-acl-oidc.ttl`)
- Custom Keycloak realm with secure settings
- Environment variable overrides for all sensitive values

### 8. Testing Secure Configuration

After implementing security changes:

1. **Verify Authentication Flow**
   - Test login with different user roles
   - Confirm access control works as expected

2. **Test Resource Access**
   - Verify readers cannot modify resources
   - Confirm editors can create and modify content
   - Validate administrators have full control

3. **Check Token Validation**
   - Ensure tokens are properly validated
   - Test token expiration and refresh behavior

### 9. Troubleshooting Common Issues

#### Issue: Access Denied for Valid Users
- **Cause**: Root ACL not properly configured to use OIDC groups
- **Solution**: Verify `root-acl` property points to `webac/root-acl-oidc.ttl`

#### Issue: Authentication Fails
- **Cause**: Incorrect Keycloak configuration or token validation settings
- **Solution**: Check `issuer-uri` and `jwk-set-uri` values match your Keycloak setup

#### Issue: Role-Based Access Not Working
- **Cause**: Keycloak user roles not properly mapped to OIDC groups
- **Solution**: Verify realm roles are correctly configured in Keycloak and match the expected group names

### 10. Security Recommendations

1. **Regular Updates**: Keep all components (Keycloak, PostgreSQL, etc.) updated with security patches
2. **Access Logging**: Enable comprehensive logging for access control decisions
3. **Monitoring**: Set up monitoring for unusual authentication or access patterns
4. **Backup Strategy**: Implement regular backups of configuration and user data
5. **Audit Trail**: Maintain audit logs for all administrative actions

## Conclusion

This documentation provides the necessary guidance to configure Articulorium securely for production environments. The key changes involve switching from the default permissive ACL to role-based access control using OIDC groups, and implementing proper security practices throughout the system.

The development setup remains functional for testing, but production deployments must follow these security recommendations to ensure proper access control and data protection.