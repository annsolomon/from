package in.dexter.billing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Plans, subscriptions, entitlements, payments adapter, invoices, webhooks. P00 skeleton: health endpoint only. */
@SpringBootApplication
public class BillingApplication {

  public static void main(String[] args) {
    SpringApplication.run(BillingApplication.class, args);
  }
}
