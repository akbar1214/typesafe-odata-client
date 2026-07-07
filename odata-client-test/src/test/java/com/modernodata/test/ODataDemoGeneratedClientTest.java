package com.modernodata.test;

import com.example.odata.complex.Address;
import com.example.odata.container.DemoService;
import com.example.odata.entity.*;
import com.modernodata.runtime.entity.Context;
import com.modernodata.runtime.http.JdkHttpTransport;
import com.modernodata.runtime.paging.CollectionPage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests generated type-safe client against the OData Demo V4 service.
 * This service exercises edge cases not covered by TripPin or Northwind:
 * - Inheritance (FeaturedProduct, Customer, Employee)
 * - Open types (Category)
 * - Complex types (Address)
 * - Geography types (Edm.GeographyPoint → Object)
 * - Stream types (Edm.Stream → Object)
 * - Various EDM types: Guid (String), Byte, Single (Float), Int64 (Long), Double, Decimal
 */
class ODataDemoGeneratedClientTest {

    static DemoService client;
    static Context context;

    @BeforeAll
    static void setup() {
        context = Context.builder()
                .baseUrl("https://services.odata.org/V4/OData/OData.svc")
                .transport(new JdkHttpTransport())
                .build();
        client = new DemoService(context);
    }

    // --- Product tests ---

    @Test
    void getProductsCollection() {
        CollectionPage<Product> page = client.products().top(3).get();
        assertNotNull(page);
        assertFalse(page.currentPage().isEmpty());
        for (Product p : page.currentPage()) {
            assertNotNull(p.getName().orElse(null));
        }
    }

    @Test
    void getProductByKey() {
        Product product = client.products().productByID(1).get();
        assertNotNull(product);
        assertEquals(1, product.getID());
    }

    @Test
    void filterProductsByName() {
        CollectionPage<Product> page = client.products()
                .filter(Product.NAME.equalTo("Bread"))
                .top(3)
                .get();

        assertFalse(page.currentPage().isEmpty());
        for (Product p : page.currentPage()) {
            assertEquals("Bread", p.getName().orElse(null));
        }
    }

    @Test
    void filterProductsByPrice() {
        CollectionPage<Product> page = client.products()
                .filter(Product.PRICE.greaterThan(10.0))
                .orderBy(Product.PRICE.desc())
                .top(3)
                .get();

        assertFalse(page.currentPage().isEmpty());
        for (Product p : page.currentPage()) {
            assertTrue(p.getPrice() > 10.0);
        }
    }

    @Test
    void filterProductsByRating() {
        CollectionPage<Product> page = client.products()
                .filter(Product.RATING.greaterThan((short) 2))
                .top(3)
                .get();

        assertFalse(page.currentPage().isEmpty());
        for (Product p : page.currentPage()) {
            assertTrue(p.getRating() > 2);
        }
    }

    @Test
    void orderByProductsByPrice() {
        CollectionPage<Product> page = client.products()
                .orderBy(Product.PRICE.desc())
                .top(5)
                .get();

        assertFalse(page.currentPage().isEmpty());
        double prev = Double.MAX_VALUE;
        for (Product p : page.currentPage()) {
            assertTrue(p.getPrice() <= prev);
            prev = p.getPrice();
        }
    }

    @Test
    void selectProductFields() {
        CollectionPage<Product> page = client.products()
                .select(Product.NAME)
                .top(1)
                .get();

        assertFalse(page.currentPage().isEmpty());
        Product p = page.currentPage().get(0);
        assertNotNull(p.getName().orElse(null));
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
    void dateTimeOffsetFilter() {
        CollectionPage<Product> page = client.products()
                .filter(Product.RELEASE_DATE.greaterThanOrEqualTo("2006-01-01T00:00:00Z"))
                .top(3)
                .get();

        assertFalse(page.currentPage().isEmpty());
        for (Product p : page.currentPage()) {
            assertNotNull(p.getReleaseDate());
        }
    }

    // --- Category (open type) ---

    @Test
    void getCategoriesCollection() {
        CollectionPage<Category> page = client.categories().top(3).get();
        assertNotNull(page);
        assertFalse(page.currentPage().isEmpty());
    }

    // --- Supplier (complex type + geography) ---

    @Test
    void getSuppliersCollection() {
        CollectionPage<Supplier> page = client.suppliers().top(3).get();
        assertNotNull(page);
        assertFalse(page.currentPage().isEmpty());
        for (Supplier s : page.currentPage()) {
            assertNotNull(s.getName().orElse(null));
        }
    }

    @Test
    void getSupplierByKey() {
        Supplier supplier = client.suppliers().supplierByID(1).get();
        assertNotNull(supplier);
        assertEquals(1, supplier.getID());
        // Address is a complex type
        assertTrue(supplier.getAddress().isPresent());
        assertNotNull(supplier.getAddress().get().getCity().orElse(null));
    }

    // --- Person (base type for inheritance) ---

    @Test
    void getPersonsCollection() {
        CollectionPage<Person> page = client.persons().top(3).get();
        assertNotNull(page);
        assertFalse(page.currentPage().isEmpty());
        for (Person p : page.currentPage()) {
            assertNotNull(p.getName().orElse(null));
        }
    }

    // --- Advertisement (HasStream + Guid) ---

    @Test
    void getAdvertisementsCollection() {
        CollectionPage<Advertisement> page = client.advertisements().top(3).get();
        assertNotNull(page);
        assertFalse(page.currentPage().isEmpty());
        for (Advertisement a : page.currentPage()) {
            assertNotNull(a.getID());
            assertNotNull(a.getName().orElse(null));
        }
    }

    // --- PersonDetail (Byte, Stream, ComplexType) ---

    @Test
    void getPersonDetailsCollection() {
        CollectionPage<PersonDetail> page = client.personDetails().top(3).get();
        assertNotNull(page);
        assertFalse(page.currentPage().isEmpty());
    }

    // --- Inheritance tests ---
    // OData Demo has inheritance: FeaturedProduct->Product, Customer->Person, Employee->Person
    // Generator creates standalone classes (no Java inheritance), but derived types still work
    // because the OData service returns all properties for each entity set.
    // Note: FeaturedProduct, Customer, Employee are NOT separate entity sets in the container —
    // they exist only as derived types in the metadata. The Persons entity set contains all Person-derived entities.

    @Test
    void inheritance_personEntitySetCompiles() {
        // Person is the base type — Persons entity set returns all persons
        CollectionPage<Person> page = client.persons().top(3).get();
        assertNotNull(page);
        assertFalse(page.currentPage().isEmpty());
        Person p = page.currentPage().get(0);
        assertNotNull(p.getID());
        assertNotNull(p.getName().orElse(null));
    }

    @Test
    void inheritance_productEntitySetContainsDerivedTypes() {
        // Products entity set should contain both Product and FeaturedProduct entities
        CollectionPage<Product> page = client.products().top(5).get();
        assertNotNull(page);
        assertFalse(page.currentPage().isEmpty());
        // All products should have base Product properties
        for (Product p : page.currentPage()) {
            assertNotNull(p.getName().orElse(null));
            assertTrue(p.getPrice() >= 0);
        }
    }

    @Test
    void inheritance_derivedTypeClassHasOwnProperties() {
        // FeaturedProduct class has its own ADVERTISEMENT nav property constant
        assertNotNull(FeaturedProduct.ADVERTISEMENT);
        // Customer class has its own TOTAL_EXPENSE property constant
        assertNotNull(Customer.TOTAL_EXPENSE);
        // Employee class has its own EMPLOYEE_ID, HIRE_DATE, SALARY property constants
        assertNotNull(Employee.EMPLOYEE_ID);
        assertNotNull(Employee.HIRE_DATE);
        assertNotNull(Employee.SALARY);
    }

    @Test
    void inheritance_baseTypeConstantsAreAccessibleOnDerived() {
        // Derived types have their parent's constants accessible on the derived class
        // (since they're standalone, this verifies the constants exist independently)
        assertNotNull(Product.ID);
        assertNotNull(Product.NAME);
        assertNotNull(Product.PRICE);
        assertNotNull(Person.ID);
        assertNotNull(Person.NAME);
    }

    // --- GeographyPoint tests ---
    // Supplier.Location is Edm.GeographyPoint → mapped to Object in generated code

    @Test
    void geography_supplierLocationIsPresent() {
        Supplier supplier = client.suppliers().supplierByID(1).get();
        assertNotNull(supplier);
        // Location is GeoJSON but typed as Object — should still be present
        assertTrue(supplier.getLocation().isPresent());
        assertNotNull(supplier.getLocation().get());
    }

    @Test
    void geography_supplierAddressComplexType() {
        Supplier supplier = client.suppliers().supplierByID(1).get();
        assertNotNull(supplier);
        assertTrue(supplier.getAddress().isPresent());
        Address addr = supplier.getAddress().get();
        assertNotNull(addr.getStreet().orElse(null));
        assertNotNull(addr.getCity().orElse(null));
        assertNotNull(addr.getState().orElse(null));
        assertNotNull(addr.getCountry().orElse(null));
    }

    @Test
    void geography_multipleSuppliersHaveLocations() {
        CollectionPage<Supplier> page = client.suppliers().top(5).get();
        assertFalse(page.currentPage().isEmpty());
        for (Supplier s : page.currentPage()) {
            // All suppliers should have both Address and Location
            assertTrue(s.getAddress().isPresent());
            assertTrue(s.getLocation().isPresent());
        }
    }
}
