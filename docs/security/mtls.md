# mTLS between services (option, P19)

**Default: not enabled.** Every service-to-service and user call already carries a JWT that the receiver verifies
itself (ES256, JWKS from identity, audience per service: ADR-0007/0009); the gateway strips client identity headers
(ADR-0011); data stores authenticate per service (Postgres roles, Redis ACL users, Vault tokens). mTLS adds
transport encryption and workload identity on the network — worth it when the cluster network is shared or a
compliance regime asks for it. Two ways, in order of preference.

## A. Service mesh (recommended in Kubernetes)
Linkerd or Istio in **strict** mode: every pod gets a SPIFFE identity, sidecars (or ambient ztunnel) do mTLS, the
application is unchanged. Policy: only the gateway may call services on their HTTP port; services may call identity
(JWKS, `/oauth2/token`); nothing calls the gateway from inside. Health probes are exempted by the mesh.

## B. In the application (Spring Boot SSL bundles), without a mesh
Each service gets a key pair + certificate from an internal CA (Vault PKI: `pki_int/issue/dexter-<svc>`, short
TTL, rendered by the Vault Agent next to the other secrets).

Server side (every service):
```yaml
spring:
  ssl:
    bundle:
      pem:
        dexter:
          keystore: { certificate: file:/vault/secrets/tls.crt, private-key: file:/vault/secrets/tls.key }
          truststore: { certificate: file:/vault/secrets/ca.crt }
          reload-on-update: true          # the Agent re-renders before expiry; no restart
server:
  ssl:
    bundle: dexter
    client-auth: need                     # only callers with a certificate from the internal CA
```
Client side: the gateway's Spring Cloud Gateway HTTP client
(`spring.cloud.gateway.server.webflux.httpclient.ssl` with the same bundle) and the JDK `HttpClient`s that call
identity (`HttpJwksSource`, `ServiceTokenClient`) built with the bundle's `SSLContext`. URIs move to `https://`
(`GATEWAY_URI_<SVC>`, `IDENTITY_JWKS_URI`). Authorisation stays with the JWT; the certificate only proves *which
workload* is calling — check the SAN in a filter if a route must accept one caller only.

Data stores: Postgres `sslmode=verify-full` with the CA; Kafka `SSL`/`SASL_SSL` listeners; Redis `rediss://`;
Vault `https` — each a configuration change only.

## Operations
- Certificates: TTL 72 h, renewed at 2/3 by the Agent; CA rotation by publishing the new CA in the truststore
  first, then issuing from it, then removing the old.
- Alert on certificate expiry < 24 h (Agent metrics) and on TLS handshake failures (5xx from the gateway with
  `SSLHandshakeException` in the trace).
- Turning it on: add the bundle with `client-auth: want` everywhere, switch clients to https, then `need`.
