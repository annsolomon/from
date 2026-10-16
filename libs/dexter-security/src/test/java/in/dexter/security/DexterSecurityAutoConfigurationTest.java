package in.dexter.security;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.security.autoconfigure.DexterSecurityAutoConfiguration;
import in.dexter.security.autoconfigure.DexterSecurityWebAutoConfiguration;
import in.dexter.security.client.ServiceTokenClient;
import in.dexter.security.jwt.JwtVerifier;
import in.dexter.security.web.SecurityInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class DexterSecurityAutoConfigurationTest {
  final WebApplicationContextRunner web = new WebApplicationContextRunner().withConfiguration(
      AutoConfigurations.of(DexterSecurityAutoConfiguration.class, DexterSecurityWebAutoConfiguration.class));

  @Test
  void offWithoutJwksUri() {
    web.run(ctx -> {
      assertThat(ctx).doesNotHaveBean(JwtVerifier.class);
      assertThat(ctx).doesNotHaveBean(SecurityInterceptor.class);
      assertThat(ctx).doesNotHaveBean(ServiceTokenClient.class);
    });
  }

  @Test
  void jwksUriWiresVerifierAndInterceptor() {
    web.withPropertyValues("dexter.security.jwks-uri=http://identity:8081/.well-known/jwks.json",
            "dexter.security.issuer=https://identity.dexter.test", "spring.application.name=ledger")
        .run(ctx -> {
          assertThat(ctx).hasSingleBean(JwtVerifier.class);
          assertThat(ctx.getBean(JwtVerifier.class).settings().audience()).isEqualTo("ledger");
          assertThat(ctx).hasSingleBean(SecurityInterceptor.class);
        });
  }

  @Test
  void issuerIsRequired() {
    web.withPropertyValues("dexter.security.jwks-uri=http://identity:8081/.well-known/jwks.json",
            "spring.application.name=ledger")
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  void tokenUriWiresServiceTokenClient() {
    web.withPropertyValues("dexter.security.client.token-uri=http://identity:8081/oauth2/token",
            "dexter.security.client.client-id=reco", "dexter.security.client.client-secret=s3cret")
        .run(ctx -> assertThat(ctx).hasSingleBean(ServiceTokenClient.class));
  }
}
