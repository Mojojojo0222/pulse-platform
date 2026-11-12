package com.pulse.prometheus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PrometheusClientTest {

    private MockRestServiceServer server;
    private PrometheusClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient rest = builder.baseUrl("http://prom.test").build();
        client = new PrometheusClient(rest, "http://prom.test");
    }

    private org.springframework.test.web.client.ResponseActions expectQuery() {
        return server.expect(request -> assertEquals("/api/v1/query", request.getURI().getPath()));
    }

    @Test
    void parsesVectorSamples() {
        String json = """
                {"status":"success","data":{"resultType":"vector","result":[
                  {"metric":{"job":"todoapp"},"value":[1794500000,"1"]}
                ]}}""";
        expectQuery().andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        PrometheusClient.VectorResult result = client.instantQuery("up{job=\"todoapp\"}");

        assertFalse(result.isEmpty());
        assertEquals(1, result.samples().size());
        assertEquals(1.0, result.samples().get(0).value());
        assertEquals("todoapp", result.samples().get(0).labels().get("job"));
        server.verify();
    }

    @Test
    void emptyResultIsNotAnError() {
        String json = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}";
        expectQuery().andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        PrometheusClient.VectorResult result = client.instantQuery("up{job=\"ghost\"}");

        assertTrue(result.isEmpty());
        assertEquals("vector", result.resultType());
    }

    @Test
    void httpErrorBecomesQueryFailed() {
        expectQuery().andRespond(withServerError());

        PrometheusException ex = assertThrows(PrometheusException.class,
                () -> client.instantQuery("bad("));

        assertEquals(PrometheusException.Kind.QUERY_FAILED, ex.getKind());
    }

    @Test
    void prometheusErrorBodyBecomesQueryFailed() {
        String json = "{\"status\":\"error\",\"errorType\":\"bad_data\",\"error\":\"parse error\"}";
        expectQuery().andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        PrometheusException ex = assertThrows(PrometheusException.class,
                () -> client.instantQuery("bad("));

        assertEquals(PrometheusException.Kind.QUERY_FAILED, ex.getKind());
        assertTrue(ex.getMessage().contains("parse error"));
    }

    @Test
    void connectionRefusedBecomesUnavailable() {
        RestClient refused = RestClient.builder().baseUrl("http://127.0.0.1:9").build();
        PrometheusClient offline = new PrometheusClient(refused, "http://127.0.0.1:9");

        PrometheusException ex = assertThrows(PrometheusException.class,
                () -> offline.instantQuery("up"));

        assertEquals(PrometheusException.Kind.UNAVAILABLE, ex.getKind());
    }

    @Test
    void parsesSpecialValues() {
        assertEquals(Double.NaN, PrometheusClient.parseValue("NaN"));
        assertEquals(Double.POSITIVE_INFINITY, PrometheusClient.parseValue("+Inf"));
        assertEquals(Double.NEGATIVE_INFINITY, PrometheusClient.parseValue("-Inf"));
        assertEquals(0.5, PrometheusClient.parseValue("0.5"));
    }
}
