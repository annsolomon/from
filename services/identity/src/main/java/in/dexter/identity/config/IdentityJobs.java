package in.dexter.identity.config;

import in.dexter.identity.app.SigningKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Scheduled maintenance: signing-key rotation check (MASTER_PLAN 7.1); one instance acts (advisory lock). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class IdentityJobs {
  private static final Logger log = LoggerFactory.getLogger(IdentityJobs.class);
  private final SigningKeyService keys;

  public IdentityJobs(SigningKeyService keys) {
    this.keys = keys;
  }

  @Scheduled(fixedDelayString = "${dexter.identity.keys.check-every:10m}",
      initialDelayString = "${dexter.identity.keys.check-every:10m}")
  public void rotateSigningKeys() {
    try {
      keys.rotateIfDue();
    } catch (RuntimeException e) {
      log.error("SIGNING_KEY_MAINTENANCE_FAILED error={}", e.toString());
    }
  }
}
