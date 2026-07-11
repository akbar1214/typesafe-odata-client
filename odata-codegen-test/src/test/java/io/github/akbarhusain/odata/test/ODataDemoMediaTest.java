package io.github.akbarhusain.odata.test;

import com.example.odata.container.DemoService;
import com.example.odata.entity.Advertisement;
import com.example.odata.entity.PersonDetail;

import io.github.akbarhusain.odata.runtime.entity.Context;
import io.github.akbarhusain.odata.runtime.http.JdkHttpTransport;
import io.github.akbarhusain.odata.runtime.paging.CollectionPage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises generated media-stream support (improvement #3) against the live OData Demo service:
 * - Media entity (Advertisement, HasStream="true") read via streamMedia() at .../Advertisements(id)/$value
 * - Named stream property (PersonDetail.Photo, Edm.Stream) read via streamPhoto() at .../PersonDetails(id)/Photo/$value
 */
class ODataDemoMediaTest {

    static DemoService client;

    @BeforeAll
    static void setup() {
        Context context = Context.builder()
                .baseUrl("https://services.odata.org/V4/OData/OData.svc")
                .transport(new JdkHttpTransport())
                .build();
        client = new DemoService(context);
    }

    @Test
    void advertisement_streamMediaReadable() throws Exception {
        CollectionPage<Advertisement> page = client.advertisements().top(1).get();
        if (page.currentPage().isEmpty()) return; // service has no advertisements
        Advertisement ad = page.currentPage().get(0);

        try (InputStream is = client.advertisements().advertisementByID(ad.getID()).streamMedia()) {
            assertNotNull(is, "streamMedia() should return a stream");
            byte[] bytes = is.readAllBytes();
            assertTrue(bytes.length > 0, "media stream should contain bytes");
        }
    }

    @Test
    void personDetail_photoStreamReadable() throws Exception {
        CollectionPage<PersonDetail> page = client.personDetails().top(1).get();
        if (page.currentPage().isEmpty()) return; // service has no person details
        PersonDetail pd = page.currentPage().get(0);

        try (InputStream is = client.personDetails().personDetailByPersonID(pd.getPersonID()).streamPhoto()) {
            assertNotNull(is, "streamPhoto() should return a stream");
            byte[] bytes = is.readAllBytes();
            assertTrue(bytes.length > 0, "photo stream should contain bytes");
        }
    }
}
