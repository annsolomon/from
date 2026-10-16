package in.dexter.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Issuers, card products, versioned reward rules, offers, ingestion. P00 skeleton: health endpoint only. */
@SpringBootApplication
public class CatalogApplication {

  public static void main(String[] args) {
    SpringApplication.run(CatalogApplication.class, args);
  }
}
