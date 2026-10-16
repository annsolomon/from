package in.dexter.testkit.support;

import in.dexter.testkit.contract.HttpResult;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

/** MockMvc → {@link HttpResult}. */
public final class Mvc {
  private Mvc() {}

  public static HttpResult call(MockMvc mvc, RequestBuilder req) throws Exception {
    MockHttpServletResponse r = mvc.perform(req).andReturn().getResponse();
    Map<String, String> headers = new LinkedHashMap<>();
    for (String name : r.getHeaderNames()) {
      headers.put(name, r.getHeader(name));
    }
    return new HttpResult(r.getStatus(), headers, r.getContentAsString(StandardCharsets.UTF_8));
  }
}
