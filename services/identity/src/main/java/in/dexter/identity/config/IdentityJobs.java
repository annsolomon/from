package in.dexter.identity.config;

import in.dexter.identity.app.KekRewrapService;
import in.dexter.identity.app.SigningKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Scheduled maintenance: signing-key rotation check (MASTER_PLAN 7.1; one instance acts, advisory lock) and the KEK
 * re-wrap (P19, ADR-0026; compare-and-set, safe on every instance; off with dexter.identity.kek.rewrap-enabled=false).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class IdentityJobs {
  private static final Logger log = LoggerFactory.getLogger(IdentityJobs.class);
  private final SigningKeyService keys;
  private final KekRewrapService rewrap;
  private final boolean rewrapEnabled;

  public IdentityJobs(SigningKeyService keys, KekRewrapService rewrap,
      @org.springframework.beans.factory.annotation.Value("${dexter.identity.kek.rewrap-enabled:true}")
      boolean rewrapEnabled) {
    this.keys = keys;
    this.rewrap = rewrap;
    this.rewrapEnabled = rewrapEnabled;
  }

  @Scheduled(fixedDelayString = "${dexter.identity.kek.rewrap-every:10m}", initialDelayString = "20s")
  public void rewrapKeks() {
    if (!rewrapEnabled) {
      return;
    }
    try {
      rewrap.run(null, 200);
    } catch (RuntimeException e) {
      log.error("KEK_REWRAP_JOB_FAILED error={}", e.toString());
    }
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
