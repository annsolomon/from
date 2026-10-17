package in.dexter.identity.api;

import in.dexter.identity.app.SigningKeyService;
import in.dexter.identity.app.SigningKeyStore.KeyRow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEV ONLY ({@code dev} profile; removed with the trail in P19): shows the signing-key lifecycle and forces a
 * rotation so p04-identity.html can show that tokens signed by the old key stay valid.
 */
@Profile("dev")
@RestController
@RequestMapping("/_dev/p04/keys")
public class DevKeysController {
  private final SigningKeyService keys;

  public DevKeysController(SigningKeyService keys) {
    this.keys = keys;
  }

  @GetMapping
  public Map<String, Object> status() {
    return view(keys.reload());
  }

  @PostMapping("/rotate")
  public Map<String, Object> rotate() {
    return view(keys.rotateNow());
  }

  private static Map<String, Object> view(SigningKeyService.Snapshot s) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("activeKid", s.active().kid());
    List<Map<String, Object>> list = s.published().stream().map(DevKeysController::row).toList();
    m.put("keys", list);
    return m;
  }

  private static Map<String, Object> row(KeyRow r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("kid", r.kid());
    m.put("status", r.status());
    m.put("createdAt", r.createdAt());
    m.put("activatedAt", r.activatedAt());
    m.put("retiredAt", r.retiredAt());
    return m;
  }
}
