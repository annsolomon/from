package in.dexter.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Identity (MASTER_PLAN 2.1, P04): accounts, registration, login, sessions, rotating refresh tokens, JWKS and
 * service tokens. MFA, admins and RBAC follow in P05.
 */
@SpringBootApplication
public class IdentityApplication {

  public static void main(String[] args) {
    SpringApplication.run(IdentityApplication.class, args);
  }
}
