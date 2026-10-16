package in.dexter.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Accounts, credentials, sessions, refresh tokens, MFA, JWKS, admins, RBAC. P00 skeleton: health endpoint only. */
@SpringBootApplication
public class IdentityApplication {

  public static void main(String[] args) {
    SpringApplication.run(IdentityApplication.class, args);
  }
}
