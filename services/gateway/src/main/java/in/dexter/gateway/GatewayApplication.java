package in.dexter.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Edge: routing, JWT pre-check, rate limit, request-id, header hygiene. P00 skeleton: health endpoint only. */
@SpringBootApplication
public class GatewayApplication {

  public static void main(String[] args) {
    SpringApplication.run(GatewayApplication.class, args);
  }
}
