package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H6: generated-code member names must not collide with the generator's own
 * emitted members (lifecycle fields, builder(), inner classes) and must remain
 * valid Java identifiers for hostile CSDL names (leading digits, dashes).
 */
class EntityGeneratorMemberNameTest {

    private static CsdlModel model;
    private static CsdlModel.SchemaModel schema;

    static {
        try (InputStream is = EntityGeneratorMemberNameTest.class
                .getResourceAsStream("/reserved-member-names-metadata.xml")) {
            model = new StaxCsdlParser().parse(is);
            schema = model.schemas().stream()
                    .filter(s -> s.namespace().equals("ReservedTest"))
                    .findFirst()
                    .orElseThrow();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private CsdlModel.EntityTypeModel entity(String name) {
        return schema.entityTypes().stream()
                .filter(e -> e.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private String generateEntity(String name) {
        EntityGenerator gen = new EntityGenerator("com.example.reserved");
        gen.setGenerateWithMethods(true);
        return gen.generate(entity(name), schema);
    }

    @Test
    void lifecycleFieldNamesAreReserved() {
        String code = generateEntity("Filterable");
        assertTrue(code.contains("protected String etag_;"),
                "property 'etag' must become field etag_ (collides with lifecycle etag)");
        assertTrue(code.contains("protected String builder_;"),
                "property 'builder' must become field builder_ (collides with static builder())");
        assertTrue(code.contains("protected String contextPath_;"),
                "property 'contextPath' must become field contextPath_ (collides with lifecycle contextPath)");
        assertTrue(code.contains("protected String changedFields_;"),
                "property 'changedFields' must become field changedFields_ (collides with lifecycle changedFields)");
        assertTrue(code.contains("protected String unmappedFields_;"),
                "property 'unmappedFields' must become field unmappedFields_ (collides with lifecycle unmappedFields)");
    }

    @Test
    void fieldReferencesUseTheRenamedFieldConsistently() {
        String code = generateEntity("Filterable");
        // getter must reference the renamed field
        assertTrue(code.contains("Optional.ofNullable(etag_);"),
                "getter must return the renamed etag_ field");
        // setter must assign the renamed field
        assertTrue(code.contains("this.etag_ = value;"),
                "setter must assign the renamed etag_ field");
        // with* must copy the renamed field
        assertTrue(code.contains("e.etag_ = this.etag_;"),
                "with* must copy the renamed etag_ field");
    }

    @Test
    void leadingDigitPropertyProducesValidIdentifier() {
        String code = generateEntity("Filterable");
        assertTrue(code.contains("protected String _2FA;"),
                "property '2FA' must become field _2FA (cannot start with a digit)");
        assertTrue(code.contains("public static final StringProperty<Filterable_> _2FA"),
                "constant for '2FA' must be a valid Java identifier (_2FA)");
    }

    @Test
    void entityNamedFilterableIsRenamedToAvoidInnerClassShadowing() {
        String code = generateEntity("Filterable");
        // The entity class itself must be renamed so its inner Filterable class doesn't shadow it
        assertTrue(code.contains("public final class Filterable_"),
                "entity named 'Filterable' must generate as Filterable_ (inner class Filterable would shadow it)");
        assertTrue(code.contains("public static class Filterable {"),
                "the typed lambda Filterable inner class must still be generated");
    }

    @Test
    void refMethodsUseSanitizedNavNames() throws Exception {
        RequestGenerator gen = new RequestGenerator("com.example.reserved");
        String code = gen.generateEntityRequest(entity("Filterable"), schema);
        assertTrue(code.contains("addX_yRef("),
                "$ref method must use the sanitized nav name x_y: " + code);
        assertTrue(code.contains("removeX_yRef("),
                "remove $ref method must use the sanitized nav name x_y");
        assertFalse(code.contains("addX-yRef"),
                "raw nav name with a dash must not appear in $ref method names");
    }

    @Test
    void compositeGetKeyIsNullSafe() throws Exception {
        String code = new EntityGenerator("com.example.reserved").generate(entity("Order"), schema);
        int idx = code.indexOf("public Object getKey()");
        int end = code.indexOf("    }\n\n", idx);
        String getKeyMethod = code.substring(idx, end);

        assertTrue(getKeyMethod.contains("java.util.HashMap<>"),
                "getKey() must build a HashMap (Map.of throws on null key values): " + getKeyMethod);
        assertTrue(getKeyMethod.contains("key.put(\"OrderID\", this.orderID)"),
                "getKey() should include OrderID mapping");
        assertTrue(getKeyMethod.contains("key.put(\"ProductID\", this.productID)"),
                "getKey() should include ProductID mapping");
        assertFalse(getKeyMethod.contains("Map.of("),
                "getKey() must not use Map.of — it throws NPE on null values");
    }
}
