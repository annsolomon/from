package in.dexter.billing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Plans, subscriptions, entitlements, payments through a provider adapter, invoices, webhooks (MASTER_PLAN P16). */
@SpringBootApplication
public class BillingApplication {

  public static void main(String[] args) {
    SpringApplication.run(BillingApplication.class, args);
  }
}
