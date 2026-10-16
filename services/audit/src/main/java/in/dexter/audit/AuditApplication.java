package in.dexter.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Hash-chained audit log, activity analytics, notifications, privacy saga. P00 skeleton: health endpoint only. */
@SpringBootApplication
public class AuditApplication {

  public static void main(String[] args) {
    SpringApplication.run(AuditApplication.class, args);
  }
}
