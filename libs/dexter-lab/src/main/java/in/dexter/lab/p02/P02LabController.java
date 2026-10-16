package in.dexter.lab.p02;

import in.dexter.db.page.PageRequest;
import in.dexter.idempotency.web.IdempotencyKey;
import in.dexter.idempotency.web.IdempotentResponses;
import in.dexter.web.page.PageResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEV-ONLY P02 lab endpoints ({@code /_dev/p02/*}), driven by the P02 trail page and {@code api-smoke.sh}.
 * {@code /_dev/**} is a public path for the service's own security, so these endpoints authenticate themselves:
 * the lab's tokens are checked by the real {@code JwtVerifier} with audience {@code lab}.
 */
@RestController
@RequestMapping("/_dev/p02")
public class P02LabController {
  private static final String AUTH = "Authorization";
  private final P02Lab lab;

  public P02LabController(P02Lab lab) {
    this.lab = lab;
  }

  @GetMapping("/status")
  public Map<String, Object> status(@RequestParam(name = "init", defaultValue = "false") boolean init) {
    return lab.status(init);
  }

  // ---- JWT playground

  @PostMapping("/jwt/token")
  public Map<String, Object> token(@RequestBody LabModel.TokenRequest req) {
    String persona = req.persona() == null ? "asha" : req.persona();
    String defect = req.defect() == null ? "none" : req.defect();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("persona", persona);
    m.put("defect", defect);
    m.put("expected", LabIssuer.DEFECTS.get(defect));
    m.put("token", lab.issuer().token(persona, defect));
    m.put("currentKid", lab.issuer().currentKid());
    return m;
  }

  @GetMapping("/jwt/whoami")
  public Map<String, Object> whoami(@RequestHeader(name = AUTH, required = false) String authorization) {
    return lab.whoami(authorization);
  }

  @PostMapping("/jwt/rotate")
  public Map<String, Object> rotate() {
    String kid = lab.issuer().rotate();
    return Map.of("currentKid", kid, "publishedKids", lab.issuer().publishedKids());
  }

  @PostMapping("/jwt/retire")
  public Map<String, Object> retire() {
    List<String> retired = lab.issuer().retireOld();
    return Map.of("retired", retired, "publishedKids", lab.issuer().publishedKids(),
        "note", "verifiers drop retired keys at their next JWKS refresh (TTL 5 min)");
  }

  @GetMapping("/jwt/jwks")
  public Map<String, Object> jwks() {
    return Map.of("jwks", lab.issuer().publicJwks().toJSONObject(), "fetches", lab.issuer().fetches());
  }

  // ---- notes: idempotency + outbox + cache

  @PostMapping("/notes")
  public ResponseEntity<String> create(@RequestHeader(name = AUTH, required = false) String authorization,
      @IdempotencyKey(required = true) UUID key, @RequestBody LabModel.CreateNote body,
      @RequestParam(name = "holdMs", defaultValue = "0") long holdMs) {
    return IdempotentResponses.toEntity(lab.create(lab.user(authorization), key, body, holdMs));
  }

  @PutMapping("/notes/{id}")
  public Map<String, Object> rename(@RequestHeader(name = AUTH, required = false) String authorization,
      @PathVariable("id") UUID id, @RequestBody LabModel.RenameNote body) {
    return lab.rename(lab.user(authorization), id, body);
  }

  @GetMapping("/notes")
  public PageResponse<LabModel.View> list(@RequestHeader(name = AUTH, required = false) String authorization,
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestParam(name = "cursor", required = false) String cursor) {
    return PageResponse.from(lab.list(lab.user(authorization), PageRequest.of(limit, cursor)));
  }

  @GetMapping("/notes/{id}")
  public ResponseEntity<LabModel.View> get(@RequestHeader(name = AUTH, required = false) String authorization,
      @PathVariable("id") UUID id) {
    P02Lab.Read r = lab.get(lab.user(authorization), id);
    return ResponseEntity.ok().header("X-Dexter-Cache", r.cache()).body(r.note());
  }

  // ---- cache controls

  @GetMapping("/cache")
  public Map<String, Object> cache(@RequestHeader(name = AUTH, required = false) String authorization) {
    return lab.cacheInfo(lab.user(authorization));
  }

  @PostMapping("/cache/bump")
  public Map<String, Object> bump(@RequestHeader(name = AUTH, required = false) String authorization) {
    return lab.bump(lab.user(authorization));
  }

  @PostMapping("/cache/purge")
  public Map<String, Object> purge(@RequestHeader(name = AUTH, required = false) String authorization) {
    return lab.purge(lab.user(authorization));
  }

  @PostMapping("/cache/redis/cut")
  public Map<String, Object> cutRedis() {
    return lab.redis(true);
  }

  @PostMapping("/cache/redis/restore")
  public Map<String, Object> restoreRedis() {
    return lab.redis(false);
  }

  // ---- events viewer

  @GetMapping("/outbox")
  public List<Map<String, Object>> outbox(@RequestHeader(name = AUTH, required = false) String authorization) {
    return lab.outbox(lab.user(authorization));
  }

  @GetMapping("/projection")
  public Map<String, Object> projection(@RequestHeader(name = AUTH, required = false) String authorization) {
    return lab.projection(lab.user(authorization));
  }

  @GetMapping("/dlq")
  public List<Map<String, Object>> dlq(@RequestHeader(name = AUTH, required = false) String authorization) {
    return lab.dlq(lab.user(authorization));
  }
}
