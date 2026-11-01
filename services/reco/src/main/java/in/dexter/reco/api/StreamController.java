package in.dexter.reco.api;

import in.dexter.reco.app.RecommendationService;
import in.dexter.reco.app.StreamService;
import in.dexter.security.AuthContext;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The SSE endpoints (MASTER_PLAN P15):
 *
 * <ul>
 *   <li>{@code POST /v1/recommendations/stream-ticket} — token in, a one-time 60-second ticket out;</li>
 *   <li>{@code GET /v1/recommendations/stream?ticket=} — the stream itself, openable by a browser's
 *       {@code EventSource}, which cannot send an {@code Authorization} header.</li>
 * </ul>
 *
 * <p>This controller is only the transport: the ticket, the fan-out and the heartbeat are {@link StreamService}'s,
 * which is what lets the cross-instance behaviour be tested with two application contexts and no HTTP at all.
 */
@RestController
@RequestMapping(RecommendationService.PATH)
public class StreamController {
  /** No timeout from our side: the client closes, or the heartbeat notices it already has. */
  private static final long NO_TIMEOUT = 0L;

  private final StreamService streams;

  public StreamController(StreamService streams) {
    this.streams = streams;
  }

  @PostMapping("/stream-ticket")
  public StreamService.Ticket ticket(AuthContext caller) {
    return streams.issue(Callers.user(caller));
  }

  /**
   * The ticket is the credential here, so the account comes from the ticket and never from a header or a query
   * parameter — the gateway makes this route public precisely because there is no token to check at the edge.
   */
  @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(@RequestParam(name = "ticket") String ticket,
      @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
    UUID account = streams.consume(ticket);
    SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
    StreamService.Sink sink = new EmitterSink(emitter);
    emitter.onCompletion(() -> streams.close(account, sink));
    emitter.onTimeout(() -> streams.close(account, sink));
    emitter.onError(e -> streams.close(account, sink));
    streams.open(account, sink, lastEventId);
    return emitter;
  }

  /** Adapts one {@link SseEmitter} to the service's transport-free {@link StreamService.Sink}. */
  private static final class EmitterSink implements StreamService.Sink {
    private final SseEmitter emitter;

    EmitterSink(SseEmitter emitter) {
      this.emitter = emitter;
    }

    @Override
    public boolean send(String eventId, String eventName, String json) {
      try {
        SseEmitter.SseEventBuilder event = SseEmitter.event().name(eventName).data(json,
            MediaType.APPLICATION_JSON);
        if (eventId != null) {
          // the event id is the state hash, so a reconnect's Last-Event-ID says what the client already has
          event = event.id(eventId);
        }
        emitter.send(event);
        return true;
      } catch (IOException | RuntimeException e) {
        // the client is gone, or the emitter is already complete; either way the service forgets this sink
        return false;
      }
    }

    @Override
    public void close() {
      emitter.complete();
    }
  }
}
