package in.dexter.events.autoconfigure;

import in.dexter.events.relay.OutboxRelay;
import org.apache.kafka.clients.producer.Producer;
import org.springframework.context.SmartLifecycle;

/** Starts the relay after the context is up and stops it (releasing leadership) first on shutdown. */
final class RelayLifecycle implements SmartLifecycle {
  private final OutboxRelay relay;
  private final Producer<String, byte[]> producer;

  RelayLifecycle(OutboxRelay relay, Producer<String, byte[]> producer) {
    this.relay = relay;
    this.producer = producer;
  }

  @Override
  public void start() {
    relay.start();
  }

  @Override
  public void stop() {
    relay.close();
    producer.close();
  }

  @Override
  public boolean isRunning() {
    return relay.isRunning();
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 100;
  }
}
