package io.github.akbarhusain.odata.core.generator;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.parser.StaxCsdlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class RequestGeneratorCreateTest {

    private static CsdlModel.SchemaModel schema;
    private static CsdlModel.EntityTypeModel person;

    static {
        try (InputStream is = RequestGeneratorCreateTest.class.getResourceAsStream("/trippin-metadata.xml")) {
            CsdlModel model = new StaxCsdlParser().parse(is);
            schema = model.schemas().get(0);
            person = schema.entityTypes().stream()
                    .filter(e -> e.name().equals("Person"))
                    .findFirst()
                    .orElseThrow();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private String generateCollectionRequest() {
        return new RequestGenerator("com.example.trippin").generateCollectionRequest(person, schema);
    }

    private String generateEntityRequest() {
        return new RequestGenerator("com.example.trippin").generateEntityRequest(person, schema);
    }

    @Test
    void collectionRequestHasCreateMethod() {
        String code = generateCollectionRequest();
        assertTrue(code.contains("public Person create(Person entity)"),
                "Collection request should expose create() for POST to the entity set");
        assertTrue(code.contains("EntityOperations.executePostEntity(context, contextPath, entity, Person.class)"),
                "create() should delegate to EntityOperations.executePostEntity");
    }

    @Test
    void collectionRequestHasPostToBatchOperation() {
        String code = generateCollectionRequest();
        assertTrue(code.contains("public BatchOperation postToBatchOperation(Person entity)"),
                "Collection request should expose postToBatchOperation()");
        assertTrue(code.contains("context.serializer().serialize(entity, Person.class)"),
                "postToBatchOperation should serialize the entity via the context serializer");
        assertTrue(code.contains("BatchOperation.post(contextPath.toRelativeUrl(), body)"),
                "postToBatchOperation should build a POST batch operation");
    }

    @Test
    void entityRequestHasPutMethod() {
        String code = generateEntityRequest();
        assertTrue(code.contains("public Person put(Person entity)"),
                "Entity request should expose put() for full replace");
        assertTrue(code.contains("EntityOperations.executePutEntity(context, contextPath, entity, Person.class)"),
                "put() should delegate to EntityOperations.executePutEntity");
    }

    @Test
    void entityRequestHasPutWithETagMethod() {
        String code = generateEntityRequest();
        assertTrue(code.contains("public Person putWithETag(Person entity, String etag)"),
                "Entity request should expose putWithETag() for optimistic concurrency");
        assertTrue(code.contains("EntityOperations.executePutEntityWithETag(context, contextPath, entity, Person.class, etag)"),
                "putWithETag() should delegate to EntityOperations.executePutEntityWithETag");
    }

    @Test
    void entityRequestHasPutToBatchOperation() {
        String code = generateEntityRequest();
        assertTrue(code.contains("public BatchOperation putToBatchOperation(Person entity)"),
                "Entity request should expose putToBatchOperation()");
        assertTrue(code.contains("BatchOperation.put(contextPath.toRelativeUrl(), body)"),
                "putToBatchOperation should build a PUT batch operation");
    }
}
