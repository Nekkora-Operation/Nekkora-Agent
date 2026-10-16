package com.nekkora.agent.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StrictJsonTest {

    @Test
    void readsObjectsStringsIntegersBooleansAndNull() throws Exception {
        Map<String, Object> m = StrictJson.parseObject(
                "{ \"a\": \"x\\u00e9\\n\", \"n\": -12, \"t\": true, \"f\": false, \"z\": null, \"o\": { \"k\": \"v\" } }");
        assertEquals("xé\n", m.get("a"));
        assertEquals(-12L, m.get("n"));
        assertEquals(Boolean.TRUE, m.get("t"));
        assertEquals(Boolean.FALSE, m.get("f"));
        assertSame(StrictJson.NULL, m.get("z"));
        assertEquals(Map.of("k", "v"), m.get("o"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "[]",
        "{\"a\": [1]}",
        "{\"a\": 1.5}",
        "{\"a\": 1e3}",
        "{\"a\": 1, \"a\": 2}",
        "{\"a\": 1} x",
        "{\"a\": 1,}",
        "{a: 1}",
        "{\"a\": \"linha\nquebrada\"}",
        "{\"a\": \"sem fim}",
        "{\"a\": \"\\x\"}",
        "{\"a\": {\"b\": {\"c\": {\"d\": {}}}}}",
        "{\"a\": 1234567890123456789}",
        "{\"a\": tru}",
        "{\"a\": NaN}"
    })
    void rejectsAnythingOutsideTheStrictSubset(String text) {
        assertThrows(StrictJson.SyntaxException.class, () -> StrictJson.parseObject(text));
    }

    @Test
    void quoteRoundTripsControlQuotesAndNonAscii() throws Exception {
        String v = "C:\\Programas\\\"Nekkora\"\tó\u0001";
        String json = "{\"v\": " + StrictJson.quote(v) + "}";
        assertEquals(v, StrictJson.parseObject(json).get("v"));
    }
}
