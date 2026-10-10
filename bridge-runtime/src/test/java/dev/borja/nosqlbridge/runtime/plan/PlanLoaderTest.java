package dev.borja.nosqlbridge.runtime.plan;

import java.net.URL;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlanLoaderTest {

    @Test
    void loadsFullFeatureBridgePlan() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        PlanLoader planLoader = new PlanLoader(objectMapper);

        URL resource = getClass().getResource("/plans/full-feature-bridge.json");
        assertNotNull(resource);
        BridgeExecutionPlan plan = planLoader.load(Path.of(resource.toURI()));

        assertEquals(1, plan.planVersion());
        assertEquals("FullFeatureBridge", plan.bridgeName());
        assertEquals("mongodb", plan.source().technology());
        assertEquals("shop", plan.source().config().get("database"));
        assertEquals("products", plan.source().config().get("collection"));
        assertEquals("dynamodb", plan.target().technology());
        assertEquals("Products", plan.target().config().get("table"));
        assertEquals("id", plan.target().config().get("partitionKey"));
        assertEquals("createdAt", plan.target().config().get("sortKey"));
        assertEquals("preserve", plan.unmappedPolicy());
        assertEquals("preserve", plan.nullPolicy());
        assertEquals(9, plan.mappings().size());

        MappingPlan id = plan.mappings().get(0);
        assertEquals("_id", id.source());
        assertEquals("id", id.target());
        assertEquals("toString", id.transformation());
        assertNull(id.defaultValue());

        MappingPlan name = plan.mappings().get(1);
        assertEquals("name", name.source());
        assertEquals("name", name.target());
        assertNull(name.transformation());
        assertNull(name.defaultValue());

        MappingPlan price = plan.mappings().get(2);
        assertEquals("toDecimal", price.transformation());
        assertEquals("string", price.defaultValue().type());
        assertEquals("0", price.defaultValue().value());

        MappingPlan stock = plan.mappings().get(3);
        assertEquals("toInteger", stock.transformation());
        assertEquals("integer", stock.defaultValue().type());
        Number stockValue = assertInstanceOf(Number.class, stock.defaultValue().value());
        assertEquals(0.0, stockValue.doubleValue());

        MappingPlan active = plan.mappings().get(6);
        assertEquals("active", active.source());
        assertEquals("boolean", active.defaultValue().type());
        assertEquals(Boolean.TRUE, active.defaultValue().value());
    }
}
