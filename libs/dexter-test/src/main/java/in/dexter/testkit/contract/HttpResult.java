package in.dexter.testkit.contract;

import java.util.Map;
import java.util.TreeMap;

/** A transport-neutral HTTP result, so contracts run over MockMvc, REST Assured or a real client. */
public record HttpResult(int status, Map<String, String> headers, String body) {
  public HttpResult {
    TreeMap<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    if (headers != null) {
      h.putAll(headers);
    }
    headers = h;
    body = body == null ? "" : body;
  }

  public String header(String name) {
    return headers.get(name);
  }

  public boolean is2xx() {
    return status >= 200 && status < 300;
  }
}
