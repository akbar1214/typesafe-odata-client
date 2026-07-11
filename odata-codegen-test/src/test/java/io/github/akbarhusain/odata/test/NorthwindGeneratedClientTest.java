package io.github.akbarhusain.odata.test;

import com.example.northwind.container.NorthwindEntities;
import com.example.northwind.entity.*;
import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.http.JdkHttpTransport;
import io.github.akbarhusain.odata.runtime.paging.CollectionPage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.example.northwind.entity.Customer.*;
import static org.junit.jupiter.api.Assertions.*;

class NorthwindGeneratedClientTest {

    static NorthwindEntities client;
    static Context context;

    @BeforeAll
    static void setup() {
        context = Context.builder()
                .baseUrl("https://services.odata.org/V4/Northwind/Northwind.svc")
                .transport(new JdkHttpTransport())
                .build();
        client = new NorthwindEntities(context);
    }

    @Test
    void getCategoriesCollection() {
        CollectionPage<Category> page = client.categories().top(3).get();
        assertNotNull(page);
        assertFalse(page.currentPage().isEmpty());
        for (Category c : page.currentPage()) {
            assertNotNull(c.getCategoryName());
        }
    }

    @Test
    void getCategoryByKey() {
        Category category = client.categories().categoryByCategoryID(1).get();
        assertNotNull(category);
        assertEquals(1, category.getCategoryID());
        assertEquals("Beverages", category.getCategoryName().orElse(null));
    }

    @Test
    void getProductsCollection() {
        CollectionPage<Product> page = client.products().top(3).get();
        assertNotNull(page);
        assertFalse(page.currentPage().isEmpty());
        for (Product p : page.currentPage()) {
            assertNotNull(p.getProductName());
        }
    }

    @Test
    void getProductByKey() {
        Product product = client.products().productByProductID(1).get();
        assertNotNull(product);
        assertEquals(1, product.getProductID());
        assertEquals("Chai", product.getProductName().orElse(null));
    }

    @Test
    void getCustomersCollection() {
        CollectionPage<Customer> page = client.customers().top(3).get();
        assertNotNull(page);
        assertFalse(page.currentPage().isEmpty());
        for (Customer c : page.currentPage()) {
            assertNotNull(c.getCustomerID());
            assertNotNull(c.getCompanyName());
        }
    }

    @Test
    void getCustomerByKey() {
        Customer customer = client.customers().customerByCustomerID("ALFKI").get();
        assertNotNull(customer);
        assertEquals("ALFKI", customer.getCustomerID());
        assertEquals("Alfreds Futterkiste", customer.getCompanyName().orElse(null));
        assertEquals("Berlin", customer.getCity().orElse(null));
    }

    @Test
    void filterProductsByCategory() {
        CollectionPage<Product> page = client.products()
                .filter(Product.CATEGORY_ID.equalTo(1))
                .top(3)
                .get();

        assertFalse(page.currentPage().isEmpty());
        for (Product p : page.currentPage()) {
            assertTrue(p.getCategoryID().isPresent());
            assertEquals(1, p.getCategoryID().get());
        }
    }

    @Test
    void orderByProducts() {
        CollectionPage<Product> page = client.products()
                .orderBy(Product.UNIT_PRICE.desc())
                .top(3)
                .get();

        assertFalse(page.currentPage().isEmpty());
    }

    @Test
    void selectProductFields() {
        CollectionPage<Product> page = client.products()
                .select(Product.PRODUCT_NAME)
                .top(1)
                .get();

        assertFalse(page.currentPage().isEmpty());
        Product product = page.currentPage().get(0);
        assertNotNull(product.getProductName());
    }

    @Test
    void selectNonStringProperties() {
        CollectionPage<Order> page = client.orders()
                .select(Order.ORDER_DATE, Order.SHIP_NAME)
                .top(1)
                .get();

        assertFalse(page.currentPage().isEmpty());
        Order order = page.currentPage().get(0);
        assertNotNull(order.getOrderDate());
        assertNotNull(order.getShipName());
    }

    @Test
    void countProducts() {
        CollectionPage<Product> page = client.products()
                .count()
                .top(1)
                .get();

        assertTrue(page.count().isPresent());
        assertTrue(page.count().get() > 0);
    }

    @Test
    void getOrdersCollection() {
        CollectionPage<Order> page = client.orders().top(3).get();
        assertNotNull(page);
        assertFalse(page.currentPage().isEmpty());
        for (Order o : page.currentPage()) {
            assertNotNull(o.getOrderID());
        }
    }

    @Test
    void getSuppliersCollection() {
        CollectionPage<Supplier> page = client.suppliers().top(2).get();
        assertNotNull(page);
        assertFalse(page.currentPage().isEmpty());
        for (Supplier s : page.currentPage()) {
            assertNotNull(s.getCompanyName());
        }
    }

    @Test
    void filterCustomersByCountry() {
        CollectionPage<Customer> page = client.customers()
                .filter(COUNTRY.equalTo("Germany"))
                .top(3)
                .get();

        assertFalse(page.currentPage().isEmpty());
        for (Customer c : page.currentPage()) {
            assertEquals("Germany", c.getCountry().orElse(null));
        }
    }

    @Test
    void getEmployeesCollection() {
        CollectionPage<Employee> page = client.employees().top(2).get();
        assertNotNull(page);
        assertFalse(page.currentPage().isEmpty());
        for (Employee e : page.currentPage()) {
            assertNotNull(e.getFirstName());
            assertNotNull(e.getLastName());
        }
    }

    @Test
    void filterProductsByPrice() {
        CollectionPage<Product> page = client.products()
                .filter(Product.UNIT_PRICE.greaterThan(BigDecimal.valueOf(50)))
                .orderBy(Product.UNIT_PRICE.desc())
                .top(3)
                .get();

        assertFalse(page.currentPage().isEmpty());
        for (Product p : page.currentPage()) {
            assertTrue(p.getUnitPrice().isPresent());
            assertTrue(p.getUnitPrice().get().compareTo(BigDecimal.valueOf(50)) > 0);
        }
    }

    @Test
    void filterOrdersByShipCountry() {
        CollectionPage<Order> page = client.orders()
                .filter(Order.SHIP_COUNTRY.equalTo("France"))
                .top(3)
                .get();

        assertFalse(page.currentPage().isEmpty());
        for (Order o : page.currentPage()) {
            assertEquals("France", o.getShipCountry().orElse(null));
        }
    }

    @Test
    void filterOrdersByDate() {
        CollectionPage<Order> page = client.orders()
                .filter(Order.ORDER_DATE.greaterThanOrEqualTo("1998-01-01T00:00:00Z"))
                .orderBy(Order.ORDER_DATE.asc())
                .top(3)
                .get();

        assertFalse(page.currentPage().isEmpty());
        for (Order o : page.currentPage()) {
            assertTrue(o.getOrderDate().isPresent());
        }
    }

    @Test
    void expandSingleNavProperty() {
        CollectionPage<Order> page = client.orders()
                .expand(Order.CUSTOMER)
                .top(1)
                .get();
        assertFalse(page.currentPage().isEmpty());
        Order order = page.currentPage().get(0);
        assertNotNull(order.getCustomerID());
    }

    @Test
    void expandMultipleNavProperties() {
        CollectionPage<Order> page = client.orders()
                .expand(Order.CUSTOMER, Order.EMPLOYEE)
                .top(1)
                .get();
        assertFalse(page.currentPage().isEmpty());
        Order order = page.currentPage().get(0);
        assertNotNull(order.getCustomerID());
    }

    @Test
    void expandCollectionNavProperty() {
        CollectionPage<Product> page = client.products()
                .expand(Product.ORDER_DETAILS)
                .top(1)
                .get();
        assertFalse(page.currentPage().isEmpty());
        Product product = page.currentPage().get(0);
        assertNotNull(product.getProductName());
    }

    @Test
    void expandWithNestedSelect() {
        CollectionPage<Order> page = client.orders()
                .expand(Order.CUSTOMER.select(
                        COMPANY_NAME,
                        CITY))
                .top(1)
                .get();
        assertFalse(page.currentPage().isEmpty());
        Order order = page.currentPage().get(0);
        assertNotNull(order.getCustomerID());
    }

    @Test
    void expandOnProduct() {
        CollectionPage<Product> page = client.products()
                .expand(Product.CATEGORY, Product.SUPPLIER)
                .top(1)
                .get();
        assertFalse(page.currentPage().isEmpty());
        Product product = page.currentPage().get(0);
        assertNotNull(product.getProductName());
    }

    @Test
    void expandWithNestedFilterOnOrderDetails() {
        CollectionPage<Order> page = client.orders()
                .expand(Order.ORDER_DETAILS.filter(Order_Detail.QUANTITY.greaterThan((short) 10)))
                .top(1)
                .get();
        assertFalse(page.currentPage().isEmpty());
        Order order = page.currentPage().get(0);
        assertNotNull(order.getCustomerID());
    }
}
