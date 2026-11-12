package in.dexter.observability;

import in.dexter.events.EventTracing;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * Installs {@link MicrometerEventTracing} as the event library's tracing once the context has its {@link Tracer}
 * and {@link Propagator} (Spring Boot's OpenTelemetry auto-configuration); without them the library stays a no-op.
 */
@AutoConfiguration
@ConditionalOnClass({Tracer.class, EventTracing.class})
public class ObservabilityAutoConfiguration {

  @Bean
  EventTracingInstaller dexterEventTracingInstaller(ObjectProvider<Tracer> tracer,
      ObjectProvider<Propagator> propagator) {
    return new EventTracingInstaller(tracer, propagator);
  }

  /** Installs after every singleton exists; resets on shutdown (tests start several contexts in one JVM). */
  static final class EventTracingInstaller implements SmartInitializingSingleton, DisposableBean {
    private final ObjectProvider<Tracer> tracer;
    private final ObjectProvider<Propagator> propagator;

    EventTracingInstaller(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
      this.tracer = tracer;
      this.propagator = propagator;
    }

    @Override
    public void afterSingletonsInstantiated() {
      Tracer t = tracer.getIfAvailable();
      Propagator p = propagator.getIfAvailable();
      if (t != null && p != null && t != Tracer.NOOP) {
        EventTracing.install(new MicrometerEventTracing(t, p));
      }
    }

    @Override
    public void destroy() {
      EventTracing.reset();
    }
  }
}
