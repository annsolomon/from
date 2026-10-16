package in.dexter.planning;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Budgets, spend projections, liabilities, sandbox scenarios, alerts. P00 skeleton: health endpoint only. */
@SpringBootApplication
public class PlanningApplication {

  public static void main(String[] args) {
    SpringApplication.run(PlanningApplication.class, args);
  }
}
