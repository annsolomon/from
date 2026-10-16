package in.dexter.lab.p02;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

/**
 * The P02 lab, only under the {@code dev} profile <em>and</em> {@code dexter.lab.p02.enabled=true} (ledger's dev
 * profile sets it). Building the beans touches no infrastructure; the lab connects on first use.
 */
@AutoConfiguration
@Profile("dev")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "dexter.lab.p02", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(LabProperties.class)
public class P02LabAutoConfiguration {

  @Bean
  P02Lab dexterP02Lab(LabProperties props) {
    return new P02Lab(props);
  }

  @Bean
  P02LabController dexterP02LabController(P02Lab lab) {
    return new P02LabController(lab);
  }
}
