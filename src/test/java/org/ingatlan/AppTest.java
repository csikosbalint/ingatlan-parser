package org.ingatlan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

class AppTest {
    private App app;
    @BeforeEach
    void setUp() {
        app = new App();
    }

    @AfterEach
    void tearDown() {
    }

    @Test
    void handleRequest() {
        Map<String, String> input = new HashMap<>();
        input.put("filter", System.getenv("FILTER"));
        input.put("bucket", System.getenv("BUCKET"));
        app.handleRequest(
                input,
                null
        );
    }
}