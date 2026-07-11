package io.github.akbarhusain.odata.core.parser;

import io.github.akbarhusain.odata.core.model.CsdlModel;
import io.github.akbarhusain.odata.core.model.CsdlModel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class StaxCsdlParser {

    private static final Logger log = LoggerFactory.getLogger(StaxCsdlParser.class);

    private static final String EDMX_NS = "http://docs.oasis-open.org/odata/ns/edmx";
    private static final String EDM_NS = "http://docs.oasis-open.org/odata/ns/edm";

    private final List<String> warnings = new ArrayList<>();

    public CsdlModel parse(InputStream xml) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        XMLEventReader reader = factory.createXMLEventReader(xml);
        List<SchemaModel> schemas = new ArrayList<>();

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                StartElement el = event.asStartElement();
                if (isEdmxElement(el, "DataServices")) {
                    parseDataServices(reader, schemas);
                }
            }
        }

        return new CsdlModel(schemas, List.copyOf(warnings));
    }

    private void parseDataServices(XMLEventReader reader, List<SchemaModel> schemas)
            throws XMLStreamException {
        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement() && isEdmElement(event.asStartElement(), "Schema")) {
                schemas.add(parseSchema(reader, event.asStartElement()));
            } else if (event.isEndElement() && isEdmxElement(event.asEndElement(), "DataServices")) {
                return;
            }
        }
    }

    private SchemaModel parseSchema(XMLEventReader reader, StartElement schemaEl)
            throws XMLStreamException {
        String namespace = getAttr(schemaEl, "Namespace");
        String alias = getAttr(schemaEl, "Alias");

        List<EntityTypeModel> entityTypes = new ArrayList<>();
        List<ComplexTypeModel> complexTypes = new ArrayList<>();
        List<EnumTypeModel> enumTypes = new ArrayList<>();
        List<TypeDefinitionModel> typeDefinitions = new ArrayList<>();
        List<FunctionModel> functions = new ArrayList<>();
        List<ActionModel> actions = new ArrayList<>();
        List<ContainerModel> containers = new ArrayList<>();

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                StartElement el = event.asStartElement();
                String localName = el.getName().getLocalPart();
                switch (localName) {
                    case "EntityType" -> entityTypes.add(parseEntityType(reader, el));
                    case "ComplexType" -> complexTypes.add(parseComplexType(reader, el));
                    case "EnumType" -> enumTypes.add(parseEnumType(reader, el));
                    case "TypeDefinition" -> typeDefinitions.add(parseTypeDefinition(reader, el));
                    case "Function" -> functions.add(parseFunction(reader, el));
                    case "Action" -> actions.add(parseAction(reader, el));
                    case "EntityContainer" -> containers.add(parseEntityContainer(reader, el));
                    default -> skipElement(reader);
                }
            } else if (event.isEndElement() && isEdmElement(event.asEndElement(), "Schema")) {
                return new SchemaModel(namespace, alias, entityTypes, complexTypes,
                        enumTypes, typeDefinitions, functions, actions, containers);
            }
        }

        return new SchemaModel(namespace, alias, entityTypes, complexTypes,
                enumTypes, typeDefinitions, functions, actions, containers);
    }

    private EntityTypeModel parseEntityType(XMLEventReader reader, StartElement el)
            throws XMLStreamException {
        String name = getAttr(el, "Name");
        String baseType = getAttr(el, "BaseType");
        boolean openType = "true".equals(getAttr(el, "OpenType"));
        boolean abstractType = "true".equals(getAttr(el, "Abstract"));
        boolean hasStream = "true".equals(getAttr(el, "HasStream"));

        List<KeyModel> keys = new ArrayList<>();
        List<PropertyModel> properties = new ArrayList<>();
        List<NavigationPropertyModel> navProps = new ArrayList<>();

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                StartElement child = event.asStartElement();
                switch (child.getName().getLocalPart()) {
                    case "Key" -> keys.add(parseKey(reader));
                    case "Property" -> properties.add(parseProperty(reader, child));
                    case "NavigationProperty" -> navProps.add(parseNavigationProperty(reader, child));
                    default -> skipElement(reader);
                }
            } else if (event.isEndElement() && isEdmElement(event.asEndElement(), "EntityType")) {
                return new EntityTypeModel(name, baseType, openType, abstractType, hasStream,
                        keys, properties, navProps);
            }
        }

        return new EntityTypeModel(name, baseType, openType, abstractType, hasStream,
                keys, properties, navProps);
    }

    private ComplexTypeModel parseComplexType(XMLEventReader reader, StartElement el)
            throws XMLStreamException {
        String name = getAttr(el, "Name");
        String baseType = getAttr(el, "BaseType");
        boolean openType = "true".equals(getAttr(el, "OpenType"));
        boolean abstractType = "true".equals(getAttr(el, "Abstract"));

        List<PropertyModel> properties = new ArrayList<>();
        List<NavigationPropertyModel> navProps = new ArrayList<>();

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                StartElement child = event.asStartElement();
                switch (child.getName().getLocalPart()) {
                    case "Property" -> properties.add(parseProperty(reader, child));
                    case "NavigationProperty" -> navProps.add(parseNavigationProperty(reader, child));
                    default -> skipElement(reader);
                }
            } else if (event.isEndElement() && isEdmElement(event.asEndElement(), "ComplexType")) {
                return new ComplexTypeModel(name, baseType, openType, abstractType, properties, navProps);
            }
        }

        return new ComplexTypeModel(name, baseType, openType, abstractType, properties, navProps);
    }

    private KeyModel parseKey(XMLEventReader reader) throws XMLStreamException {
        List<String> propertyRefs = new ArrayList<>();
        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement() && "PropertyRef".equals(event.asStartElement().getName().getLocalPart())) {
                propertyRefs.add(getAttr(event.asStartElement(), "Name"));
            } else if (event.isEndElement() && isEdmElement(event.asEndElement(), "Key")) {
                return new KeyModel(propertyRefs);
            }
        }
        return new KeyModel(propertyRefs);
    }

    private PropertyModel parseProperty(XMLEventReader reader, StartElement el)
            throws XMLStreamException {
        String name = getAttr(el, "Name");
        String edmType = getAttr(el, "Type");
        boolean nullable = !"false".equals(getAttr(el, "Nullable"));
        String defaultValue = getAttr(el, "DefaultValue");
        // Consume everything until the closing </Property> tag (annotations, etc.)
        skipElement(reader);
        return new PropertyModel(name, edmType, nullable, defaultValue, List.of());
    }

    private NavigationPropertyModel parseNavigationProperty(XMLEventReader reader, StartElement el)
            throws XMLStreamException {
        String name = getAttr(el, "Name");
        String type = getAttr(el, "Type");
        String partner = getAttr(el, "Partner");
        boolean containsTarget = "true".equals(getAttr(el, "ContainsTarget"));
        boolean nullable = !"false".equals(getAttr(el, "Nullable"));

        List<ReferentialConstraintModel> constraints = new ArrayList<>();

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement() && "ReferentialConstraint".equals(
                    event.asStartElement().getName().getLocalPart())) {
                StartElement constraintEl = event.asStartElement();
                constraints.add(new ReferentialConstraintModel(
                        getAttr(constraintEl, "Property"),
                        getAttr(constraintEl, "ReferencedProperty")));
            } else if (event.isEndElement() && isEdmElement(event.asEndElement(), "NavigationProperty")) {
                return new NavigationPropertyModel(name, type, partner, containsTarget,
                        nullable, constraints, List.of());
            }
        }

        return new NavigationPropertyModel(name, type, partner, containsTarget,
                nullable, constraints, List.of());
    }

    private EnumTypeModel parseEnumType(XMLEventReader reader, StartElement el)
            throws XMLStreamException {
        String name = getAttr(el, "Name");
        String underlyingType = getOrDefault(getAttr(el, "UnderlyingType"), "Edm.Int32");
        boolean isFlags = "true".equals(getAttr(el, "IsFlags"));

        List<EnumMemberModel> members = new ArrayList<>();
        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement() && "Member".equals(
                    event.asStartElement().getName().getLocalPart())) {
                StartElement memberEl = event.asStartElement();
                String memberName = getAttr(memberEl, "Name");
                String valueStr = getAttr(memberEl, "Value");
                long value = valueStr != null ? Long.parseLong(valueStr) : members.size();
                members.add(new EnumMemberModel(memberName, value));
            } else if (event.isEndElement() && isEdmElement(event.asEndElement(), "EnumType")) {
                return new EnumTypeModel(name, underlyingType, isFlags, members);
            }
        }

        return new EnumTypeModel(name, underlyingType, isFlags, members);
    }

    private TypeDefinitionModel parseTypeDefinition(XMLEventReader reader, StartElement el)
            throws XMLStreamException {
        String name = getAttr(el, "Name");
        String underlyingType = getAttr(el, "UnderlyingType");
        skipElement(reader);
        return new TypeDefinitionModel(name, underlyingType);
    }

    private FunctionModel parseFunction(XMLEventReader reader, StartElement el)
            throws XMLStreamException {
        String name = getAttr(el, "Name");
        boolean isBound = "true".equals(getAttr(el, "IsBound"));
        boolean isComposable = "true".equals(getAttr(el, "IsComposable"));
        String entitySetPath = getAttr(el, "EntitySetPath");

        List<ParameterModel> parameters = new ArrayList<>();
        ReturnTypeModel returnType = null;

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                StartElement child = event.asStartElement();
                switch (child.getName().getLocalPart()) {
                    case "Parameter" -> parameters.add(parseParameter(child));
                    case "ReturnType" -> returnType = new ReturnTypeModel(
                            getAttr(child, "Type"),
                            !"false".equals(getAttr(child, "Nullable")));
                    default -> skipElement(reader);
                }
            } else if (event.isEndElement() && isEdmElement(event.asEndElement(), "Function")) {
                return new FunctionModel(name, isBound, isComposable, entitySetPath,
                        parameters, returnType);
            }
        }

        return new FunctionModel(name, isBound, isComposable, entitySetPath,
                parameters, returnType);
    }

    private ActionModel parseAction(XMLEventReader reader, StartElement el)
            throws XMLStreamException {
        String name = getAttr(el, "Name");
        boolean isBound = "true".equals(getAttr(el, "IsBound"));
        String entitySetPath = getAttr(el, "EntitySetPath");

        List<ParameterModel> parameters = new ArrayList<>();
        ReturnTypeModel returnType = null;

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                StartElement child = event.asStartElement();
                switch (child.getName().getLocalPart()) {
                    case "Parameter" -> parameters.add(parseParameter(child));
                    case "ReturnType" -> returnType = new ReturnTypeModel(
                            getAttr(child, "Type"),
                            !"false".equals(getAttr(child, "Nullable")));
                    default -> skipElement(reader);
                }
            } else if (event.isEndElement() && isEdmElement(event.asEndElement(), "Action")) {
                return new ActionModel(name, isBound, entitySetPath, parameters, returnType);
            }
        }

        return new ActionModel(name, isBound, entitySetPath, parameters, returnType);
    }

    private ParameterModel parseParameter(StartElement el) {
        return new ParameterModel(
                getAttr(el, "Name"),
                getAttr(el, "Type"),
                !"false".equals(getAttr(el, "Nullable")));
    }

    private ContainerModel parseEntityContainer(XMLEventReader reader, StartElement el)
            throws XMLStreamException {
        String name = getAttr(el, "Name");

        List<EntitySetModel> entitySets = new ArrayList<>();
        List<SingletonModel> singletons = new ArrayList<>();
        List<FunctionImportModel> functionImports = new ArrayList<>();
        List<ActionImportModel> actionImports = new ArrayList<>();

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                StartElement child = event.asStartElement();
                switch (child.getName().getLocalPart()) {
                    case "EntitySet" -> entitySets.add(parseEntitySet(reader, child));
                    case "Singleton" -> singletons.add(parseSingleton(reader, child));
                    case "FunctionImport" -> functionImports.add(parseFunctionImport(child));
                    case "ActionImport" -> actionImports.add(parseActionImport(child));
                    default -> skipElement(reader);
                }
            } else if (event.isEndElement() && isEdmElement(event.asEndElement(), "EntityContainer")) {
                return new ContainerModel(name, entitySets, singletons, functionImports, actionImports);
            }
        }

        return new ContainerModel(name, entitySets, singletons, functionImports, actionImports);
    }

    private EntitySetModel parseEntitySet(XMLEventReader reader, StartElement el)
            throws XMLStreamException {
        String name = getAttr(el, "Name");
        String entityType = getAttr(el, "EntityType");

        List<NavigationPropertyBindingModel> bindings = new ArrayList<>();

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement() && "NavigationPropertyBinding".equals(
                    event.asStartElement().getName().getLocalPart())) {
                StartElement bindingEl = event.asStartElement();
                bindings.add(new NavigationPropertyBindingModel(
                        getAttr(bindingEl, "Path"),
                        getAttr(bindingEl, "Target")));
            } else if (event.isEndElement() && isEdmElement(event.asEndElement(), "EntitySet")) {
                return new EntitySetModel(name, entityType, bindings, List.of());
            }
        }

        return new EntitySetModel(name, entityType, bindings, List.of());
    }

    private SingletonModel parseSingleton(XMLEventReader reader, StartElement el)
            throws XMLStreamException {
        String name = getAttr(el, "Name");
        String type = getAttr(el, "Type");

        List<NavigationPropertyBindingModel> bindings = new ArrayList<>();

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement() && "NavigationPropertyBinding".equals(
                    event.asStartElement().getName().getLocalPart())) {
                StartElement bindingEl = event.asStartElement();
                bindings.add(new NavigationPropertyBindingModel(
                        getAttr(bindingEl, "Path"),
                        getAttr(bindingEl, "Target")));
            } else if (event.isEndElement() && isEdmElement(event.asEndElement(), "Singleton")) {
                return new SingletonModel(name, type, bindings);
            }
        }

        return new SingletonModel(name, type, bindings);
    }

    private FunctionImportModel parseFunctionImport(StartElement el) {
        return new FunctionImportModel(
                getAttr(el, "Name"),
                getAttr(el, "Function"),
                getAttr(el, "EntitySet"),
                "true".equals(getAttr(el, "IncludeInServiceDocument")));
    }

    private ActionImportModel parseActionImport(StartElement el) {
        return new ActionImportModel(
                getAttr(el, "Name"),
                getAttr(el, "Action"),
                getAttr(el, "EntitySet"));
    }

    private void skipElement(XMLEventReader reader) throws XMLStreamException {
        int depth = 1;
        while (reader.hasNext() && depth > 0) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) depth++;
            else if (event.isEndElement()) depth--;
        }
    }

    private boolean isEdmxElement(StartElement el, String localName) {
        return el.getName().getLocalPart().equals(localName)
                && EDMX_NS.equals(el.getName().getNamespaceURI());
    }

    private boolean isEdmxElement(javax.xml.stream.events.EndElement el, String localName) {
        return el.getName().getLocalPart().equals(localName)
                && EDMX_NS.equals(el.getName().getNamespaceURI());
    }

    private boolean isEdmElement(StartElement el, String localName) {
        return el.getName().getLocalPart().equals(localName)
                && EDM_NS.equals(el.getName().getNamespaceURI());
    }

    private boolean isEdmElement(javax.xml.stream.events.EndElement el, String localName) {
        return el.getName().getLocalPart().equals(localName)
                && EDM_NS.equals(el.getName().getNamespaceURI());
    }

    private String getAttr(StartElement el, String name) {
        Attribute attr = el.getAttributeByName(new javax.xml.namespace.QName("", name));
        return attr != null ? attr.getValue() : null;
    }

    private String getOrDefault(String value, String defaultValue) {
        return value != null ? value : defaultValue;
    }
}
