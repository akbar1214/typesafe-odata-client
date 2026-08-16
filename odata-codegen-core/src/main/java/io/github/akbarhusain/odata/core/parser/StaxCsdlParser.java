package io.github.akbarhusain.odata.core.parser;

import io.github.akbarhusain.odata.core.generator.Names;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StaxCsdlParser {

    private static final Logger log = LoggerFactory.getLogger(StaxCsdlParser.class);

    private static final String EDMX_NS = "http://docs.oasis-open.org/odata/ns/edmx";
    private static final String EDM_NS = "http://docs.oasis-open.org/odata/ns/edm";

    // Set while parsing a schema; the parser instance is one-shot per parse() call
    private String currentNamespace;
    private String currentAlias;
    private static final String EDMX_NS_V3 = "http://schemas.microsoft.com/ado/2007/06/edmx";

    private final List<String> warnings = new ArrayList<>();

    public CsdlModel parse(InputStream xml) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        XMLEventReader reader = factory.createXMLEventReader(xml);
        validateRootElement(reader);
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

        return new CsdlModel(mergeContainerInheritance(schemas), List.copyOf(warnings));
    }

    /**
     * Validates that the document is an OData v4 CSDL document. The parser only
     * understands the v4 namespaces; v3 or unknown documents must fail loudly
     * instead of silently producing an empty model.
     */
    private void validateRootElement(XMLEventReader reader) throws XMLStreamException {
        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                StartElement root = event.asStartElement();
                String local = root.getName().getLocalPart();
                String ns = root.getName().getNamespaceURI();
                if ("Edmx".equals(local)) {
                    if (EDMX_NS_V3.equals(ns)) {
                        throw new IllegalArgumentException(
                                "OData v3 metadata (" + ns + ") is not supported; only OData v4 (" + EDMX_NS + ") is supported");
                    }
                    if (!EDMX_NS.equals(ns)) {
                        throw new IllegalArgumentException("Unsupported EDMX namespace: " + ns);
                    }
                    return;
                }
                throw new IllegalArgumentException("Not an OData CSDL document (root element: " + local + ")");
            }
        }
        throw new IllegalArgumentException("Empty document: no root element found");
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
        String namespace = requireAttr(schemaEl, "Namespace", "Schema");
        String alias = getAttr(schemaEl, "Alias");
        // Aliases are usable within the schema that declares them; type references read
        // while parsing this schema are normalized to the real namespace immediately
        this.currentNamespace = namespace;
        this.currentAlias = alias;

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
                    default -> {
                        warnings.add("Schema '" + namespace + "': ignored unknown element <"
                                + localName + ">");
                        skipElement(reader);
                    }
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
        String name = requireAttr(el, "Name", "EntityType");
        String baseType = resolveTypeRef(getAttr(el, "BaseType"));
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
                    case "Key" -> {
                        // CSDL allows at most one <Key> per entity type; accepting several
                        // produced per-key single-key accessors for a composite-key entity
                        if (!keys.isEmpty()) {
                            throw new IllegalArgumentException("EntityType '" + name
                                    + "' declares multiple <Key> elements; CSDL allows at most one");
                        }
                        keys.add(parseKey(reader, name));
                    }
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
        String name = requireAttr(el, "Name", "ComplexType");
        String baseType = resolveTypeRef(getAttr(el, "BaseType"));
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

    private KeyModel parseKey(XMLEventReader reader, String entityName) throws XMLStreamException {
        List<String> propertyRefs = new ArrayList<>();
        List<String> aliases = new ArrayList<>();
        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement() && "PropertyRef".equals(event.asStartElement().getName().getLocalPart())) {
                propertyRefs.add(requireAttr(event.asStartElement(), "Name",
                        "PropertyRef in <Key> of EntityType '" + entityName + "'"));
                String alias = getAttr(event.asStartElement(), "Alias");
                aliases.add(alias != null ? alias : "");
            } else if (event.isEndElement() && isEdmElement(event.asEndElement(), "Key")) {
                return new KeyModel(propertyRefs, aliases);
            }
        }
        return new KeyModel(propertyRefs, aliases);
    }

    private PropertyModel parseProperty(XMLEventReader reader, StartElement el)
            throws XMLStreamException {
        String name = requireAttr(el, "Name", "Property");
        String edmType = resolveTypeRef(requireAttr(el, "Type", "Property '" + name + "'"));
        boolean nullable = !"false".equals(getAttr(el, "Nullable"));
        String defaultValue = getAttr(el, "DefaultValue");
        // Consume everything until the closing </Property> tag (annotations, etc.)
        skipElement(reader);
        return new PropertyModel(name, edmType, nullable, defaultValue, List.of());
    }

    private NavigationPropertyModel parseNavigationProperty(XMLEventReader reader, StartElement el)
            throws XMLStreamException {
        String name = requireAttr(el, "Name", "NavigationProperty");
        String type = resolveTypeRef(requireAttr(el, "Type", "NavigationProperty '" + name + "'"));
        String partner = getAttr(el, "Partner");
        boolean containsTarget = "true".equals(getAttr(el, "ContainsTarget"));
        boolean nullable = !"false".equals(getAttr(el, "Nullable"));

        List<ReferentialConstraintModel> constraints = new ArrayList<>();

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement() && "ReferentialConstraint".equals(
                    event.asStartElement().getName().getLocalPart())) {
                constraints.addAll(parseReferentialConstraint(reader, event.asStartElement()));
            } else if (event.isEndElement() && isEdmElement(event.asEndElement(), "NavigationProperty")) {
                return new NavigationPropertyModel(name, type, partner, containsTarget,
                        nullable, constraints, List.of());
            }
        }

        return new NavigationPropertyModel(name, type, partner, containsTarget,
                nullable, constraints, List.of());
    }

    /**
     * Parses a ReferentialConstraint in either shape: v4 nested
     * {@code <Principal><PropertyRef Name=.../></Principal><Dependent>...} (paired by
     * position) or the legacy attribute form ({@code Property}/{@code ReferencedProperty}).
     */
    private List<ReferentialConstraintModel> parseReferentialConstraint(XMLEventReader reader,
                                                                        StartElement constraintEl)
            throws XMLStreamException {
        List<String> principal = new ArrayList<>();
        List<String> dependent = new ArrayList<>();
        String section = null;
        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                StartElement el = event.asStartElement();
                String localName = el.getName().getLocalPart();
                switch (localName) {
                    case "Principal" -> section = "P";
                    case "Dependent" -> section = "D";
                    case "PropertyRef" -> {
                        String name = getAttr(el, "Name");
                        if ("P".equals(section)) principal.add(name);
                        else if ("D".equals(section)) dependent.add(name);
                    }
                    default -> skipElement(reader);
                }
            } else if (event.isEndElement()) {
                String localName = event.asEndElement().getName().getLocalPart();
                if ("Principal".equals(localName) || "Dependent".equals(localName)) {
                    section = null;
                } else if ("ReferentialConstraint".equals(localName)) {
                    List<ReferentialConstraintModel> result = new ArrayList<>();
                    if (!principal.isEmpty() || !dependent.isEmpty()) {
                        if (principal.size() != dependent.size()) {
                            throw new IllegalArgumentException(
                                    "ReferentialConstraint has " + principal.size()
                                            + " principal PropertyRefs but " + dependent.size()
                                            + " dependent PropertyRefs");
                        }
                        for (int i = 0; i < principal.size(); i++) {
                            result.add(new ReferentialConstraintModel(dependent.get(i), principal.get(i)));
                        }
                    } else {
                        // legacy attribute form
                        String prop = getAttr(constraintEl, "Property");
                        String ref = getAttr(constraintEl, "ReferencedProperty");
                        if (prop != null && ref != null) {
                            result.add(new ReferentialConstraintModel(prop, ref));
                        }
                    }
                    return result;
                }
            }
        }
        return List.of();
    }

    private EnumTypeModel parseEnumType(XMLEventReader reader, StartElement el)
            throws XMLStreamException {
        String name = requireAttr(el, "Name", "EnumType");
        String underlyingType = getOrDefault(getAttr(el, "UnderlyingType"), "Edm.Int32");
        boolean isFlags = "true".equals(getAttr(el, "IsFlags"));

        List<EnumMemberModel> members = new ArrayList<>();
        // CSDL: a Member without Value defaults to the previous member's value + 1 (0 if first)
        long lastValue = -1;
        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement() && "Member".equals(
                    event.asStartElement().getName().getLocalPart())) {
                StartElement memberEl = event.asStartElement();
                String memberName = requireAttr(memberEl, "Name",
                        "Member of EnumType '" + name + "'");
                String valueStr = getAttr(memberEl, "Value");
                long value;
                if (valueStr != null) {
                    try {
                        value = Long.parseLong(valueStr);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("EnumType '" + name + "' member '"
                                + memberName + "' has invalid Value '" + valueStr + "'", e);
                    }
                } else {
                    value = lastValue < 0 ? 0 : lastValue + 1;
                }
                lastValue = value;
                members.add(new EnumMemberModel(memberName, value));
            } else if (event.isEndElement() && isEdmElement(event.asEndElement(), "EnumType")) {
                return new EnumTypeModel(name, underlyingType, isFlags, members);
            }
        }

        return new EnumTypeModel(name, underlyingType, isFlags, members);
    }

    private TypeDefinitionModel parseTypeDefinition(XMLEventReader reader, StartElement el)
            throws XMLStreamException {
        String name = requireAttr(el, "Name", "TypeDefinition");
        String underlyingType = resolveTypeRef(
                requireAttr(el, "UnderlyingType", "TypeDefinition '" + name + "'"));
        skipElement(reader);
        return new TypeDefinitionModel(name, underlyingType);
    }

    private FunctionModel parseFunction(XMLEventReader reader, StartElement el)
            throws XMLStreamException {
        String name = requireAttr(el, "Name", "Function");
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
                            resolveTypeRef(getAttr(child, "Type")),
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
        String name = requireAttr(el, "Name", "Action");
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
                requireAttr(el, "Name", "Parameter"),
                resolveTypeRef(requireAttr(el, "Type", "Parameter")),
                !"false".equals(getAttr(el, "Nullable")));
    }

    private ContainerModel parseEntityContainer(XMLEventReader reader, StartElement el)
            throws XMLStreamException {
        String name = requireAttr(el, "Name", "EntityContainer");
        String extendsContainer = resolveTypeRef(getAttr(el, "Extends"));

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
                return new ContainerModel(name, extendsContainer, entitySets, singletons, functionImports, actionImports);
            }
        }

        return new ContainerModel(name, extendsContainer, entitySets, singletons, functionImports, actionImports);
    }

    private EntitySetModel parseEntitySet(XMLEventReader reader, StartElement el)
            throws XMLStreamException {
        String name = requireAttr(el, "Name", "EntitySet");
        String entityType = resolveTypeRef(requireAttr(el, "EntityType", "EntitySet '" + name + "'"));

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
        String name = requireAttr(el, "Name", "Singleton");
        String type = resolveTypeRef(requireAttr(el, "Type", "Singleton '" + name + "'"));

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
                requireAttr(el, "Name", "FunctionImport"),
                resolveTypeRef(getAttr(el, "Function")),
                getAttr(el, "EntitySet"),
                "true".equals(getAttr(el, "IncludeInServiceDocument")));
    }

    private ActionImportModel parseActionImport(StartElement el) {
        return new ActionImportModel(
                requireAttr(el, "Name", "ActionImport"),
                resolveTypeRef(getAttr(el, "Action")),
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

    /** Returns the attribute value or fails with the offending element in the message. */
    private String requireAttr(StartElement el, String attr, String elementDescription) {
        String value = getAttr(el, attr);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    elementDescription + " is missing required attribute '" + attr + "'");
        }
        return value;
    }

    /**
     * Resolves alias-qualified type references ({@code self.Address}) to the schema's real
     * namespace, preserving {@code Collection(...)} wrappers. Without this, alias refs
     * resolve as unknown types and the generators emit wrong packages/imports.
     */
    private String resolveTypeRef(String raw) {
        if (raw == null) {
            return null;
        }
        raw = raw.trim();
        boolean isCollection = raw.startsWith("Collection(") && raw.endsWith(")");
        String inner = isCollection ? raw.substring("Collection(".length(), raw.length() - 1).trim() : raw;
        if (currentAlias != null && currentNamespace != null && inner.startsWith(currentAlias + ".")) {
            inner = currentNamespace + inner.substring(currentAlias.length());
        }
        return isCollection ? "Collection(" + inner + ")" : inner;
    }

    private String getOrDefault(String value, String defaultValue) {
        return value != null ? value : defaultValue;
    }

    /**
     * Merges inherited entity sets/singletons/imports into containers that declare
     * {@code Extends} — without this, the parent's sets are silently absent from the
     * generated container. Own members win over inherited ones with the same name.
     */
    private static List<SchemaModel> mergeContainerInheritance(List<SchemaModel> schemas) {
        boolean anyExtends = schemas.stream()
                .flatMap(sch -> sch.containers().stream())
                .anyMatch(c -> c.extendsContainer() != null && !c.extendsContainer().isBlank());
        if (!anyExtends) {
            return schemas;
        }

        Map<String, ContainerModel> byQualifiedName = new HashMap<>();
        Map<ContainerModel, String> namespaceOf = new HashMap<>();
        for (SchemaModel schema : schemas) {
            for (ContainerModel container : schema.containers()) {
                byQualifiedName.putIfAbsent(schema.namespace() + "." + container.name(), container);
                namespaceOf.putIfAbsent(container, schema.namespace());
            }
        }

        List<SchemaModel> result = new ArrayList<>();
        for (SchemaModel schema : schemas) {
            List<ContainerModel> merged = new ArrayList<>();
            for (ContainerModel container : schema.containers()) {
                merged.add(mergeContainer(container, byQualifiedName, namespaceOf, new HashSet<>()));
            }
            result.add(new SchemaModel(schema.namespace(), schema.alias(), schema.entityTypes(),
                    schema.complexTypes(), schema.enumTypes(), schema.typeDefinitions(),
                    schema.functions(), schema.actions(), merged));
        }
        return result;
    }

    private static ContainerModel mergeContainer(ContainerModel container,
                                                 Map<String, ContainerModel> byQualifiedName,
                                                 Map<ContainerModel, String> namespaceOf,
                                                 Set<String> visiting) {
        String extendsName = container.extendsContainer();
        if (extendsName == null || extendsName.isBlank()) {
            return container;
        }
        String fqn = namespaceOf.get(container) + "." + container.name();
        if (!visiting.add(fqn)) {
            throw new IllegalArgumentException("Circular EntityContainer inheritance involving: " + fqn);
        }
        ContainerModel base = byQualifiedName.get(extendsName);
        if (base == null) {
            base = byQualifiedName.get(namespaceOf.get(container) + "." + extendsName);
        }
        if (base == null) {
            // unqualified ref to a container in another schema — accept a unique simple-name match
            for (ContainerModel candidate : byQualifiedName.values()) {
                if (candidate != container
                        && candidate.name().equals(Names.simpleNameFromFullName(extendsName))) {
                    base = candidate;
                    break;
                }
            }
        }
        if (base == null) {
            throw new IllegalArgumentException("EntityContainer '" + container.name()
                    + "' extends unknown container: " + extendsName);
        }
        ContainerModel resolvedBase = mergeContainer(base, byQualifiedName, namespaceOf, visiting);
        List<EntitySetModel> entitySets = new ArrayList<>(resolvedBase.entitySets());
        entitySets.removeIf(i -> container.entitySets().stream().anyMatch(o -> o.name().equals(i.name())));
        entitySets.addAll(container.entitySets());
        List<SingletonModel> singletons = new ArrayList<>(resolvedBase.singletons());
        singletons.removeIf(i -> container.singletons().stream().anyMatch(o -> o.name().equals(i.name())));
        singletons.addAll(container.singletons());
        List<FunctionImportModel> functionImports = new ArrayList<>(resolvedBase.functionImports());
        functionImports.removeIf(i -> container.functionImports().stream().anyMatch(o -> o.name().equals(i.name())));
        functionImports.addAll(container.functionImports());
        List<ActionImportModel> actionImports = new ArrayList<>(resolvedBase.actionImports());
        actionImports.removeIf(i -> container.actionImports().stream().anyMatch(o -> o.name().equals(i.name())));
        actionImports.addAll(container.actionImports());
        return new ContainerModel(container.name(), container.extendsContainer(),
                entitySets, singletons, functionImports, actionImports);
    }
}
