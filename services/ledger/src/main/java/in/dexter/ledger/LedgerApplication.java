package in.dexter.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Expenses, categories, merchants, daily totals, calendar, imports. P00 skeleton: health endpoint only. */
@SpringBootApplication
public class LedgerApplication {

  public static void main(String[] args) {
    SpringApplication.run(LedgerApplication.class, args);
  }
}
