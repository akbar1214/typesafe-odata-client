package io.github.akbarhusain.odata.maven;

import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GenerateMojoDescriptorTest {

    @Test
    void reactorConsumersHaveADescriptorBeforeCompile() throws Exception {
        Path descriptor = Path.of("target", "classes", "META-INF", "maven", "plugin.xml");
        assertTrue(Files.isRegularFile(descriptor), "plugin descriptor was not generated for an external consumer");
        String descriptorText = Files.readString(descriptor);
        assertTrue(descriptorText.contains("<goal>generate</goal>"));
        assertTrue(descriptorText.contains("<phase>generate-sources</phase>"));
        Path consumerPom = Path.of("..", "odata-codegen-test", "pom.xml");
        assertTrue(Files.isRegularFile(consumerPom));
        assertTrue(Files.readString(consumerPom).contains("<artifactId>odata-codegen-maven-plugin</artifactId>"));

        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var document = factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        var plugins = document.getElementsByTagName("plugin");
        boolean found = false;
        for (int i = 0; i < plugins.getLength(); i++) {
            var plugin = (Element) plugins.item(i);
            if (!"maven-plugin-plugin".equals(text(plugin, "artifactId"))) {
                continue;
            }
            var executions = plugin.getElementsByTagName("execution");
            for (int j = 0; j < executions.getLength(); j++) {
                if ("compile".equals(text(executions.item(j), "phase"))) {
                    found = true;
                }
            }
        }
        assertTrue(found, "descriptor generation must be bound to compile for the reactor test consumer");
    }

    private static String text(org.w3c.dom.Node parent, String tag) {
        var children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            var child = children.item(i);
            if (tag.equals(child.getNodeName())) {
                return child.getTextContent().trim();
            }
        }
        return null;
    }
}
