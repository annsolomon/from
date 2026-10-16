package in.dexter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.dexter.web.dev.P01LabController;
import in.dexter.web.error.ProblemDetailsAdvice;
import in.dexter.web.etag.IfMatchArgumentResolver;
import in.dexter.web.json.StrictJsonCustomizer;
import in.dexter.web.page.PageRequestArgumentResolver;
import in.dexter.web.request.RequestIdFilter;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** dexter-web end to end through Spring MVC (standalone MockMvc, no server). */
class WebLabMvcTest {
  static final JsonMapper MAPPER = JsonMapper.builder().build();
  MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.standaloneSetup(new P01LabController())
        .setControllerAdvice(new ProblemDetailsAdvice())
        .setCustomArgumentResolvers(new PageRequestArgumentResolver(), new IfMatchArgumentResolver())
        .setMessageConverters(new JacksonJsonHttpMessageConverter(StrictJsonCustomizer.apply(JsonMapper.builder())))
        .addFilters(new RequestIdFilter())
        .build();
  }

  @Test
  void requestIdIsEchoedOrGenerated() throws Exception {
    mvc.perform(get("/_dev/p01/doc").header("X-Request-Id", "trail-1"))
        .andExpect(header().string("X-Request-Id", "trail-1"));
    MvcResult r = mvc.perform(get("/_dev/p01/doc").header("X-Request-Id", "bad id\ninjected")).andReturn();
    assertThat(r.getResponse().getHeader("X-Request-Id")).hasSize(36);
  }

  @Test
  void moneyUsesHalfEven() throws Exception {
    mvc.perform(get("/_dev/p01/money").param("paise", "500").param("bps", "50"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.resultPaise").value(2));
    mvc.perform(get("/_dev/p01/money").param("paise", "500").param("bps", "50").param("rounding", "HALF_UP"))
        .andExpect(jsonPath("$.resultPaise").value(3));
  }

  @Test
  void dexterExceptionBecomesProblemDetail() throws Exception {
    mvc.perform(get("/_dev/p01/errors/RATE_LIMITED").header("X-Request-Id", "rid-7"))
        .andExpect(status().is(429))
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
        .andExpect(jsonPath("$.requestId").value("rid-7"))
        .andExpect(jsonPath("$.status").value(429))
        .andExpect(jsonPath("$.type").value("https://docs.dexter.in/problems/rate-limited"))
        .andExpect(jsonPath("$.instance").value("/_dev/p01/errors/RATE_LIMITED"));
    mvc.perform(get("/_dev/p01/errors/VALIDATION_FAILED"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("name"))
        .andExpect(jsonPath("$.errors[1].field").value("amountPaise"));
  }

  @Test
  void strictJson() throws Exception {
    mvc.perform(post("/_dev/p01/strict-json").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"a\",\"amountPaise\":5}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.amountPaise").value(5));
    mvc.perform(post("/_dev/p01/strict-json").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"a\",\"amountPaise\":5,\"admin\":true}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"))
        .andExpect(jsonPath("$.errors[0].field").value("admin"));
    mvc.perform(post("/_dev/p01/strict-json").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"a\",\"name\":\"b\",\"amountPaise\":5}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DUPLICATE_FIELD"));
    mvc.perform(post("/_dev/p01/strict-json").contentType(MediaType.APPLICATION_JSON).content("{\"name\":"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
    mvc.perform(post("/_dev/p01/strict-json").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"a\",\"amountPaise\":null}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
    mvc.perform(post("/_dev/p01/strict-json").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\",\"amountPaise\":0}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    mvc.perform(post("/_dev/p01/strict-json").contentType(MediaType.TEXT_PLAIN).content("x"))
        .andExpect(status().is(415)).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
  }

  @Test
  void ifMatchFlow() throws Exception {
    mvc.perform(put("/_dev/p01/doc")).andExpect(status().is(428)).andExpect(jsonPath("$.code").value("IF_MATCH_REQUIRED"));
    mvc.perform(put("/_dev/p01/doc").header("If-Match", "W/\"0\"")).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("IF_MATCH_INVALID"));
    mvc.perform(get("/_dev/p01/doc")).andExpect(header().string("ETag", "\"0\""));
    mvc.perform(put("/_dev/p01/doc").header("If-Match", "\"0\"")).andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));
    mvc.perform(put("/_dev/p01/doc").header("If-Match", "\"0\"")).andExpect(status().is(412))
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT")).andExpect(jsonPath("$.expectedVersion").value(0));
  }

  @Test
  void pagingWalkAndLimitValidation() throws Exception {
    Set<String> seen = new HashSet<>();
    String cursor = null;
    int limit = 50;
    int pages = 0;
    while (true) {
      var req = get("/_dev/p01/items").param("limit", String.valueOf(limit));
      if (cursor != null) {
        req.param("cursor", cursor);
      }
      JsonNode body = MAPPER.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
      body.get("data").forEach(n -> assertThat(seen.add(n.get("id").asString())).isTrue());
      pages++;
      if (!body.get("page").get("hasMore").asBoolean()) {
        assertThat(body.get("page").get("nextCursor").isNull()).isTrue();
        break;
      }
      cursor = body.get("page").get("nextCursor").asString();
      limit = 25;
    }
    assertThat(seen).hasSize(120);
    assertThat(pages).isEqualTo(4);
    for (String bad : new String[] {"0", "101", "abc"}) {
      mvc.perform(get("/_dev/p01/items").param("limit", bad)).andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("LIMIT_INVALID"));
    }
    mvc.perform(get("/_dev/p01/items").param("cursor", "garbage.cursor")).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CURSOR_INVALID"));
  }

  @Test
  void cursorFilterBinding() throws Exception {
    JsonNode food = MAPPER.readTree(mvc.perform(get("/_dev/p01/items").param("category", "food").param("limit", "5"))
        .andReturn().getResponse().getContentAsString());
    String c = food.get("page").get("nextCursor").asString();
    mvc.perform(get("/_dev/p01/items").param("category", "travel").param("cursor", c))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CURSOR_FILTER_MISMATCH"));
  }

  @Test
  void unknownRouteAndMethod() throws Exception {
    mvc.perform(post("/_dev/p01/doc")).andExpect(status().is(405)).andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    mvc.perform(get("/_dev/p01/errors/NOPE")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }
}
