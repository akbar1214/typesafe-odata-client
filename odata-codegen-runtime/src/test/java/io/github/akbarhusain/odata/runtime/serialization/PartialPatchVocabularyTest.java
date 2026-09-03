package io.github.akbarhusain.odata.runtime.serialization;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RED (TDD, Batch B8): partial PATCH filters the serialized tree by
 * {@code changedFields} — the two vocabularies (tracked names vs. serialized
 * JSON names) must align on the CSDL wire names, or PATCH silently sends
 * {@code {}}. Pins the contract with a @JsonProperty bean shaped like generated
 * entities (CSDL wire names, Java field names).
 */
class PartialPatchVocabularyTest {

    public static class PersonBean {
        @JsonProperty("UserName")
        public String userName;
        @JsonProperty("FirstName")
        public String firstName;
    }

    private static String patchBody(PersonBean bean, Set<String> changed) {
        return new String(new JacksonSerializer().serialize(bean, PersonBean.class, changed),
                StandardCharsets.UTF_8);
    }

    @Test
    void changedFieldsKeepExactlyTheTrackedWireName() {
        PersonBean bean = new PersonBean();
        bean.userName = "scott";
        bean.firstName = "Scott";

        String body = patchBody(bean, Set.of("FirstName"));

        assertTrue(body.contains("\"FirstName\""), "tracked field must survive filtering: " + body);
        assertFalse(body.contains("\"UserName\""), "untracked field must be stripped: " + body);
    }

    @Test
    void javaFieldNameDoesNotMatchWireName() {
        PersonBean bean = new PersonBean();
        bean.firstName = "Scott";

        // changedFields carry CSDL wire names (what with*/Builder record), not Java
        // field names — filtering is exact-match on the serialized tree.
        String body = patchBody(bean, Set.of("firstName"));

        assertFalse(body.contains("Scott"),
                "a Java field name is not a wire name and must match nothing: " + body);
    }
}
