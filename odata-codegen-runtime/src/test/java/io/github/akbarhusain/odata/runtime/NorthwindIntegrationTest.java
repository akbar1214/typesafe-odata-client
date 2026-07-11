package io.github.akbarhusain.odata.runtime;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.entity.ContextPath;
import io.github.akbarhusain.odata.runtime.http.HttpResponse;
import io.github.akbarhusain.odata.runtime.http.HttpTransport;
import io.github.akbarhusain.odata.runtime.http.JdkHttpTransport;
import io.github.akbarhusain.odata.runtime.client.EntityOperations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NorthwindIntegrationTest {

    static Context northwindContext;
    static ObjectMapper mapper;

    @BeforeAll
    static void setup() {
        HttpTransport transport = new JdkHttpTransport();
        northwindContext = Context.builder()
                .baseUrl("https://services.odata.org/V4/Northwind/Northwind.svc")
                .transport(transport)
                .build();

        mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerModule(new Jdk8Module());
        mapper.registerModule(new JavaTimeModule());
    }

    record Category(int CategoryID, String CategoryName, String Description) {}
    record Product(int ProductID, String ProductName, double UnitPrice, Integer UnitsInStock) {}
    record Customer(String CustomerID, String CompanyName, String ContactName, String Country) {}
    record Order(int OrderID, String CustomerID, String ShipCountry) {}

    @Test
    void getCategoriesCollection() throws Exception {
        ContextPath path = northwindContext.basePath()
                .addSegment("Categories")
                .addQuery("$top", "3");

        HttpResponse response = EntityOperations.executeSync(
                northwindContext,
                io.github.akbarhusain.odata.runtime.http.HttpMethod.GET,
                path, null, null);

        assertEquals(200, response.statusCode());

        JsonNode root = mapper.readTree(response.body());
        assertTrue(root.has("value"), "Response should have 'value' array");
        assertTrue(root.get("value").isArray(), "'value' should be an array");
        assertTrue(root.get("value").size() > 0, "Should have at least one category");

        JsonNode firstCategory = root.get("value").get(0);
        assertTrue(firstCategory.has("CategoryID"), "Category should have CategoryID");
        assertTrue(firstCategory.has("CategoryName"), "Category should have CategoryName");
    }

    @Test
    void getCategoryByKey() throws Exception {
        ContextPath path = northwindContext.basePath()
                .addSegment("Categories")
                .addKey("CategoryID", 1);

        HttpResponse response = EntityOperations.executeSync(
                northwindContext,
                io.github.akbarhusain.odata.runtime.http.HttpMethod.GET,
                path, null, null);

        assertEquals(200, response.statusCode());

        JsonNode category = mapper.readTree(response.body());
        assertEquals(1, category.get("CategoryID").asInt());
        assertEquals("Beverages", category.get("CategoryName").asText());
    }

    @Test
    void getProductsCollection() throws Exception {
        ContextPath path = northwindContext.basePath()
                .addSegment("Products")
                .addQuery("$top", "3");

        HttpResponse response = EntityOperations.executeSync(
                northwindContext,
                io.github.akbarhusain.odata.runtime.http.HttpMethod.GET,
                path, null, null);

        assertEquals(200, response.statusCode());

        JsonNode root = mapper.readTree(response.body());
        assertTrue(root.has("value"));
        assertTrue(root.get("value").size() > 0);

        JsonNode product = root.get("value").get(0);
        assertTrue(product.has("ProductID"));
        assertTrue(product.has("ProductName"));
        assertTrue(product.has("UnitPrice"));
    }

    @Test
    void getCustomersCollection() throws Exception {
        ContextPath path = northwindContext.basePath()
                .addSegment("Customers")
                .addQuery("$top", "3");

        HttpResponse response = EntityOperations.executeSync(
                northwindContext,
                io.github.akbarhusain.odata.runtime.http.HttpMethod.GET,
                path, null, null);

        assertEquals(200, response.statusCode());

        JsonNode root = mapper.readTree(response.body());
        assertTrue(root.has("value"));
        assertTrue(root.get("value").size() > 0);

        JsonNode customer = root.get("value").get(0);
        assertTrue(customer.has("CustomerID"));
        assertTrue(customer.has("CompanyName"));
        assertTrue(customer.has("ContactName"));
    }

    @Test
    void getCustomerByKey() throws Exception {
        ContextPath path = northwindContext.basePath()
                .addSegment("Customers")
                .addKey("CustomerID", "ALFKI");

        HttpResponse response = EntityOperations.executeSync(
                northwindContext,
                io.github.akbarhusain.odata.runtime.http.HttpMethod.GET,
                path, null, null);

        assertEquals(200, response.statusCode());

        JsonNode customer = mapper.readTree(response.body());
        assertEquals("ALFKI", customer.get("CustomerID").asText());
        assertEquals("Alfreds Futterkiste", customer.get("CompanyName").asText());
        assertEquals("Berlin", customer.get("City").asText());
    }

    @Test
    void filterProductsByCategory() throws Exception {
        ContextPath path = northwindContext.basePath()
                .addSegment("Products")
                .addQuery("$filter", "CategoryID eq 1")
                .addQuery("$top", "3");

        HttpResponse response = EntityOperations.executeSync(
                northwindContext,
                io.github.akbarhusain.odata.runtime.http.HttpMethod.GET,
                path, null, null);

        assertEquals(200, response.statusCode());

        JsonNode root = mapper.readTree(response.body());
        assertTrue(root.get("value").size() > 0, "Should find products in category 1");

        for (JsonNode product : root.get("value")) {
            assertEquals(1, product.get("CategoryID").asInt(),
                    "All products should be in category 1");
        }
    }

    @Test
    void orderByProducts() throws Exception {
        ContextPath path = northwindContext.basePath()
                .addSegment("Products")
                .addQuery("$orderby", "UnitPrice desc")
                .addQuery("$top", "3");

        HttpResponse response = EntityOperations.executeSync(
                northwindContext,
                io.github.akbarhusain.odata.runtime.http.HttpMethod.GET,
                path, null, null);

        assertEquals(200, response.statusCode());

        JsonNode root = mapper.readTree(response.body());
        assertTrue(root.get("value").size() > 0);

        double prevPrice = Double.MAX_VALUE;
        for (JsonNode product : root.get("value")) {
            double price = product.get("UnitPrice").asDouble();
            assertTrue(price <= prevPrice,
                    "Should be descending: " + price + " <= " + prevPrice);
            prevPrice = price;
        }
    }

    @Test
    void selectProductFields() throws Exception {
        ContextPath path = northwindContext.basePath()
                .addSegment("Products")
                .addQuery("$select", "ProductID,ProductName")
                .addQuery("$top", "1");

        HttpResponse response = EntityOperations.executeSync(
                northwindContext,
                io.github.akbarhusain.odata.runtime.http.HttpMethod.GET,
                path, null, null);

        assertEquals(200, response.statusCode());

        JsonNode root = mapper.readTree(response.body());
        JsonNode product = root.get("value").get(0);
        assertTrue(product.has("ProductID"), "Should have ProductID");
        assertTrue(product.has("ProductName"), "Should have ProductName");
        assertFalse(product.has("UnitPrice"), "Should NOT have UnitPrice (not selected)");
    }

    @Test
    void countProducts() throws Exception {
        ContextPath path = northwindContext.basePath()
                .addSegment("Products")
                .addQuery("$count", "true")
                .addQuery("$top", "1");

        HttpResponse response = EntityOperations.executeSync(
                northwindContext,
                io.github.akbarhusain.odata.runtime.http.HttpMethod.GET,
                path, null, null);

        assertEquals(200, response.statusCode());

        JsonNode root = mapper.readTree(response.body());
        assertTrue(root.has("@odata.count"), "Response should have @odata.count");
        int count = root.get("@odata.count").asInt();
        assertTrue(count > 0, "Count should be positive");
    }

    @Test
    void getOrdersCollection() throws Exception {
        ContextPath path = northwindContext.basePath()
                .addSegment("Orders")
                .addQuery("$top", "3");

        HttpResponse response = EntityOperations.executeSync(
                northwindContext,
                io.github.akbarhusain.odata.runtime.http.HttpMethod.GET,
                path, null, null);

        assertEquals(200, response.statusCode());

        JsonNode root = mapper.readTree(response.body());
        assertTrue(root.has("value"));
        assertTrue(root.get("value").size() > 0);

        JsonNode order = root.get("value").get(0);
        assertTrue(order.has("OrderID"));
        assertTrue(order.has("CustomerID"));
        assertTrue(order.has("OrderDate"));
    }

    @Test
    void expandOrdersWithCustomer() throws Exception {
        ContextPath path = northwindContext.basePath()
                .addSegment("Orders")
                .addQuery("$expand", "Customer")
                .addQuery("$top", "1");

        HttpResponse response = EntityOperations.executeSync(
                northwindContext,
                io.github.akbarhusain.odata.runtime.http.HttpMethod.GET,
                path, null, null);

        assertEquals(200, response.statusCode());

        JsonNode root = mapper.readTree(response.body());
        JsonNode order = root.get("value").get(0);
        assertTrue(order.has("Customer"), "Order should have expanded Customer");
        assertNotNull(order.get("Customer"), "Customer should not be null");
        assertTrue(order.get("Customer").has("CustomerID"), "Customer should have CustomerID");
    }

    @Test
    void expandWithNestedSelect() throws Exception {
        ContextPath path = northwindContext.basePath()
                .addSegment("Orders")
                .addQuery("$expand", "Customer($select=CompanyName,City)")
                .addQuery("$top", "1");

        HttpResponse response = EntityOperations.executeSync(
                northwindContext,
                io.github.akbarhusain.odata.runtime.http.HttpMethod.GET,
                path, null, null);

        assertEquals(200, response.statusCode());

        JsonNode root = mapper.readTree(response.body());
        JsonNode order = root.get("value").get(0);
        assertTrue(order.has("Customer"), "Order should have expanded Customer");
        JsonNode customer = order.get("Customer");
        assertTrue(customer.has("CompanyName"), "Customer should have CompanyName");
        assertTrue(customer.has("City"), "Customer should have City");
    }

    @Test
    void expandMultipleNavProperties() throws Exception {
        ContextPath path = northwindContext.basePath()
                .addSegment("Orders")
                .addQuery("$expand", "Customer,Employee")
                .addQuery("$top", "1");

        HttpResponse response = EntityOperations.executeSync(
                northwindContext,
                io.github.akbarhusain.odata.runtime.http.HttpMethod.GET,
                path, null, null);

        assertEquals(200, response.statusCode());

        JsonNode root = mapper.readTree(response.body());
        JsonNode order = root.get("value").get(0);
        assertTrue(order.has("Customer"), "Order should have expanded Customer");
        assertTrue(order.has("Employee"), "Order should have expanded Employee");
    }

    @Test
    void expandCollectionNavProperty() throws Exception {
        ContextPath path = northwindContext.basePath()
                .addSegment("Orders")
                .addQuery("$expand", "Order_Details")
                .addQuery("$top", "1");

        HttpResponse response = EntityOperations.executeSync(
                northwindContext,
                io.github.akbarhusain.odata.runtime.http.HttpMethod.GET,
                path, null, null);

        assertEquals(200, response.statusCode());

        JsonNode root = mapper.readTree(response.body());
        JsonNode order = root.get("value").get(0);
        assertTrue(order.has("Order_Details"), "Order should have expanded Order_Details");
        assertTrue(order.get("Order_Details").isArray(), "Order_Details should be an array");
    }

    @Test
    void expandOnProductWithCategoryAndSupplier() throws Exception {
        ContextPath path = northwindContext.basePath()
                .addSegment("Products")
                .addQuery("$expand", "Category,Supplier")
                .addQuery("$top", "1");

        HttpResponse response = EntityOperations.executeSync(
                northwindContext,
                io.github.akbarhusain.odata.runtime.http.HttpMethod.GET,
                path, null, null);

        assertEquals(200, response.statusCode());

        JsonNode root = mapper.readTree(response.body());
        JsonNode product = root.get("value").get(0);
        assertTrue(product.has("Category"), "Product should have expanded Category");
        assertTrue(product.has("Supplier"), "Product should have expanded Supplier");
    }

    @Test
    void filterOrdersByDate() throws Exception {
        ContextPath path = northwindContext.basePath()
                .addSegment("Orders")
                .addQuery("$filter", "OrderDate ge 1998-01-01")
                .addQuery("$orderby", "OrderDate asc")
                .addQuery("$top", "3");

        HttpResponse response = EntityOperations.executeSync(
                northwindContext,
                io.github.akbarhusain.odata.runtime.http.HttpMethod.GET,
                path, null, null);

        assertEquals(200, response.statusCode());

        JsonNode root = mapper.readTree(response.body());
        assertTrue(root.get("value").size() > 0, "Should find orders after 1998");
    }

    @Test
    void getSuppliersCollection() throws Exception {
        ContextPath path = northwindContext.basePath()
                .addSegment("Suppliers")
                .addQuery("$top", "2");

        HttpResponse response = EntityOperations.executeSync(
                northwindContext,
                io.github.akbarhusain.odata.runtime.http.HttpMethod.GET,
                path, null, null);

        assertEquals(200, response.statusCode());

        JsonNode root = mapper.readTree(response.body());
        assertTrue(root.has("value"));
        assertTrue(root.get("value").size() > 0);

        JsonNode supplier = root.get("value").get(0);
        assertTrue(supplier.has("SupplierID"));
        assertTrue(supplier.has("CompanyName"));
        assertTrue(supplier.has("Country"));
    }

    @Test
    void filterCustomersByCountry() throws Exception {
        ContextPath path = northwindContext.basePath()
                .addSegment("Customers")
                .addQuery("$filter", "Country eq 'Germany'")
                .addQuery("$top", "3");

        HttpResponse response = EntityOperations.executeSync(
                northwindContext,
                io.github.akbarhusain.odata.runtime.http.HttpMethod.GET,
                path, null, null);

        assertEquals(200, response.statusCode());

        JsonNode root = mapper.readTree(response.body());
        assertTrue(root.get("value").size() > 0, "Should find German customers");

        for (JsonNode customer : root.get("value")) {
            assertEquals("Germany", customer.get("Country").asText(),
                    "All customers should be from Germany");
        }
    }

    @Test
    void getEmployeesCollection() throws Exception {
        ContextPath path = northwindContext.basePath()
                .addSegment("Employees")
                .addQuery("$top", "2");

        HttpResponse response = EntityOperations.executeSync(
                northwindContext,
                io.github.akbarhusain.odata.runtime.http.HttpMethod.GET,
                path, null, null);

        assertEquals(200, response.statusCode());

        JsonNode root = mapper.readTree(response.body());
        assertTrue(root.has("value"));
        assertTrue(root.get("value").size() > 0);

        JsonNode employee = root.get("value").get(0);
        assertTrue(employee.has("EmployeeID"));
        assertTrue(employee.has("FirstName"));
        assertTrue(employee.has("LastName"));
    }

    @Test
    void filterProductsByPrice() throws Exception {
        ContextPath path = northwindContext.basePath()
                .addSegment("Products")
                .addQuery("$filter", "UnitPrice gt 50")
                .addQuery("$orderby", "UnitPrice desc")
                .addQuery("$top", "3");

        HttpResponse response = EntityOperations.executeSync(
                northwindContext,
                io.github.akbarhusain.odata.runtime.http.HttpMethod.GET,
                path, null, null);

        assertEquals(200, response.statusCode());

        JsonNode root = mapper.readTree(response.body());
        assertTrue(root.get("value").size() > 0, "Should find expensive products");

        for (JsonNode product : root.get("value")) {
            assertTrue(product.get("UnitPrice").asDouble() > 50,
                    "All products should cost more than 50");
        }
    }
}
