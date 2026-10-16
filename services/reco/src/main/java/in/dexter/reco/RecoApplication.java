package in.dexter.reco;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spend profile, catalog projection, scoring, wallet optimizer, best-card, SSE. P00 skeleton: health endpoint only. */
@SpringBootApplication
public class RecoApplication {

  public static void main(String[] args) {
    SpringApplication.run(RecoApplication.class, args);
  }
}
