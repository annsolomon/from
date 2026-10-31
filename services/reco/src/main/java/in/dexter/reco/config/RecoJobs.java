package in.dexter.reco.config;

import in.dexter.reco.app.Sweeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The sweeper's schedule (MASTER_PLAN P14 step 2). Every instance runs it; {@code SKIP LOCKED} in the claim means
 * they never collide, so there is no leader election here.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "dexter.reco.sweeper", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RecoJobs {
  private static final Logger log = LoggerFactory.getLogger(RecoJobs.class);
  private final Sweeper sweeper;

  public RecoJobs(Sweeper sweeper) {
    this.sweeper = sweeper;
  }

  @Scheduled(fixedDelayString = "${dexter.reco.sweeper.every:1s}",
      initialDelayString = "${dexter.reco.sweeper.every:1s}")
  public void sweep() {
    try {
      sweeper.sweep();
    } catch (RuntimeException e) {
      // a failed pass must not kill the schedule: the accounts stay dirty and the next pass claims them again
      log.error("RECO_SWEEP_FAILED error={}", e.toString());
    }
  }
}
