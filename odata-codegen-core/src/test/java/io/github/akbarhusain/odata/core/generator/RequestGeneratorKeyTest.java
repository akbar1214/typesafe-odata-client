package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestGeneratorKeyTest {

    @Test
    void compositeKeyEntityGetsByKeyAccessor() throws Exception {
        CsdlModel model;
        try (InputStream is = getClass().getResourceAsStream("/northwind-metadata.xml")) {
            model = new StaxCsdlParser().parse(is);
        }
        CsdlModel.SchemaModel schema = model.schemas().get(0);
        CsdlModel.EntityTypeModel orderDetail = null;
        for (var et : schema.entityTypes()) {
            if (et.name().equals("Order_Detail")) orderDetail = et;
        }
        assertTrue(orderDetail != null, "Order_Detail entity should exist");

        RequestGenerator gen = new RequestGenerator("com.example.northwind");
        String code = gen.generateCollectionRequest(orderDetail, schema);

        assertTrue(code.contains("ByOrderID") && code.contains("orderID") && code.contains("productID"),
                "Composite-key entity should have a single composite accessor with all key params");
    }

    @Test
    void inheritedKeyEntityGetsByKeyAccessor() throws Exception {
        CsdlModel model;
        try (InputStream is = getClass().getResourceAsStream("/trippin-metadata.xml")) {
            model = new StaxCsdlParser().parse(is);
        }
        CsdlModel.SchemaModel schema = model.schemas().get(0);
        CsdlModel.EntityTypeModel flight = null;
        for (var et : schema.entityTypes()) {
            if (et.name().equals("Flight")) flight = et;
        }
        assertTrue(flight != null, "Flight entity should exist");

        RequestGenerator gen = new RequestGenerator("com.example.trippin");
        String code = gen.generateCollectionRequest(flight, schema);

        assertTrue(code.contains("ByPlanItemId"),
                "Subtype with inherited key should have byKey accessor");
    }
}
