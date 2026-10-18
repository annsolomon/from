package in.dexter.catalog.api;

import in.dexter.catalog.app.IngestionAdminService;
import in.dexter.catalog.app.IngestionAdminService.RunView;
import in.dexter.catalog.app.IngestionAdminService.SourceView;
import in.dexter.db.page.PageRequest;
import in.dexter.security.AuthContext;
import in.dexter.security.web.RequiresPermission;
import in.dexter.web.etag.ETags;
import in.dexter.web.etag.IfMatchVersion;
import in.dexter.web.page.PageResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * /v1/admin/ingestion (MASTER_PLAN P10, admin token with catalog permissions): sources (CRUD; PATCH is a JSON merge
 * patch with If-Match), crawl runs (queued here, run by the worker; 202 + Location).
 */
@RestController
@RequestMapping("/v1/admin/ingestion")
public class IngestionController {
  static final String MERGE_PATCH = "application/merge-patch+json";

  /** {@code POST /v1/admin/ingestion/runs} body; no ids = every live, enabled source. */
  public record NewRun(List<UUID> sourceIds) {}

  private final IngestionAdminService ingestion;

  public IngestionController(IngestionAdminService ingestion) {
    this.ingestion = ingestion;
  }

  @GetMapping("/sources")
  @RequiresPermission("catalog:read")
  public PageResponse<SourceView> sources(AuthContext caller, PageRequest page) {
    return PageResponse.from(ingestion.sources(Callers.editor(caller), page));
  }

  @PostMapping("/sources")
  @RequiresPermission("catalog:write")
  public ResponseEntity<SourceView> create(AuthContext caller, @RequestBody IngestionAdminService.NewSource body) {
    SourceView v = ingestion.create(Callers.editor(caller), body);
    return ResponseEntity.status(HttpStatus.CREATED).eTag(ETags.of(v.version()))
        .header("Location", "/v1/admin/ingestion/sources/" + v.id()).body(v);
  }

  @GetMapping("/sources/{id}")
  @RequiresPermission("catalog:read")
  public ResponseEntity<SourceView> source(AuthContext caller, @PathVariable("id") UUID id) {
    SourceView v = ingestion.source(Callers.editor(caller), id);
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }

  @PatchMapping(value = "/sources/{id}", consumes = {MERGE_PATCH, "application/json"})
  @RequiresPermission("catalog:write")
  public ResponseEntity<SourceView> patch(AuthContext caller, @PathVariable("id") UUID id,
      @IfMatchVersion long version, @RequestBody JsonNode body) {
    SourceView v = ingestion.patch(Callers.editor(caller), id, (int) version, body);
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }

  @DeleteMapping("/sources/{id}")
  @RequiresPermission("catalog:write")
  public ResponseEntity<Void> delete(AuthContext caller, @PathVariable("id") UUID id) {
    ingestion.delete(Callers.editor(caller), id);
    return ResponseEntity.noContent().build();
  }

  /** Runs one source now. */
  @PostMapping("/sources/{id}/runs")
  @RequiresPermission("catalog:write")
  public ResponseEntity<RunView> runSource(AuthContext caller, @PathVariable("id") UUID id) {
    return accepted(ingestion.startRun(Callers.editor(caller), List.of(id)));
  }

  @PostMapping("/runs")
  @RequiresPermission("catalog:write")
  public ResponseEntity<RunView> run(AuthContext caller, @RequestBody(required = false) NewRun body) {
    return accepted(ingestion.startRun(Callers.editor(caller), body == null ? null : body.sourceIds()));
  }

  @GetMapping("/runs")
  @RequiresPermission("catalog:read")
  public PageResponse<RunView> runs(AuthContext caller, PageRequest page) {
    return PageResponse.from(ingestion.runs(Callers.editor(caller), page));
  }

  @GetMapping("/runs/{id}")
  @RequiresPermission("catalog:read")
  public RunView runStatus(AuthContext caller, @PathVariable("id") UUID id) {
    return ingestion.run(Callers.editor(caller), id);
  }

  private static ResponseEntity<RunView> accepted(RunView v) {
    return ResponseEntity.status(HttpStatus.ACCEPTED).header("Location", "/v1/admin/ingestion/runs/" + v.id()).body(v);
  }
}
