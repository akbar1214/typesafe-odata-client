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
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StaxCsdlParser {

    private static final Logger log = LoggerFactory.getLogger(StaxCsdlParser.class);

    private static final String EDMX_NS = "http://docs.oasis-open.org/odata/ns/edmx";
    private static final String EDM_NS = "http://docs.oasis-open.org/odata/ns/edm";

    // Set while parsing a schema
    private String currentNamespace;
    private String currentAlias;
    private static final String EDMX_NS_V3 = "http://schemas.microsoft.com/ado/2007/06/edmx";

    private final Map<String, String> globalAliasMap = new HashMap<>();

    /**
     * Every schema namespace seen so far, so an alias rewrite can tell a real qualified
     * reference from a coincidental alias-prefix match. Populated in {@code parseSchema};
     * a document-order reference to a schema parsed LATER is still correct because the
     * alias reading is rejected on the {@code ns.startsWith(inner)} test.
     */
    private final Set<String> declaredNamespaces = new HashSet<>();

    private final List<String> warnings = new ArrayList<>();

    public CsdlModel parse(InputStream xml) throws XMLStreamException {
        currentNamespace = null;
        currentAlias = null;
        globalAliasMap.clear();
        declaredNamespaces.clear();
        warnings.clear();

        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        // The reader owns the parse buffers obtained from the factory and close() is the
        // contract for releasing them. parse() has ~25 exit points — every requireAttr
        // failure and every unsupported shape throws — so the reader must be closed on the
        // failure paths too. (XMLEventReader extends Iterator, not AutoCloseable, so this
        // cannot be try-with-resources; the finally is the equivalent.) The caller's
        // InputStream is closed separately by the caller, which is not sufficient on its own.
        XMLEventReader reader = factory.createXMLEventReader(xml);
        try {
            validateRootElement(reader);
            List<SchemaModel> schemas = new ArrayList<>();
            int dataServicesCount = 0;

            while (reader.hasNext()) {
                XMLEvent event = reader.nextEvent();
                if (event.isStartElement()) {
                    StartElement el = event.asStartElement();
                    if (isEdmxElement(el, "DataServices")) {
                        dataServicesCount++;
                        if (dataServicesCount > 1) {
                            throw new IllegalArgumentException(
                                    "Edmx must contain exactly one DataServices element; found multiple");
                        }
                        parseDataServices(reader, schemas);
                    } else {
                        warnIgnored("Edmx", el);
                        skipElement(reader);
                    }
                }
            }

            if (dataServicesCount != 1) {
                throw new IllegalArgumentException(
                        "Edmx must contain exactly one DataServices element; found " + dataServicesCount);
            }
            if (schemas.isEmpty()) {
                // A document with no parsable Schema previously produced an empty model
                // with no signal — wrong-namespace feeds and empty documents all looked
                // like "success with zero types"
                throw new IllegalArgumentException(
                        "No OData Schema found in document (expected <Schema> under <edmx:DataServices>)");
            }
            // Every schema has been seen, so declaredNamespaces is complete before the
            // post-pass: an alias rewrite can now distinguish a real qualified reference
            // from a coincidental alias-prefix match regardless of document order.
            schemas = fixupCrossSchemaAliases(schemas);
            validateTypeDefinitionCycles(schemas);

            CsdlModel result = new CsdlModel(mergeContainerInheritance(schemas), List.copyOf(warnings));
            try {
                reader.close();
            } catch (XMLStreamException e) {
                // Close BEFORE returning: CsdlModel snapshots the warning list in its
                // compact constructor, so a warning appended in a finally would never reach
                // the caller. Re-wrap the model with the warning included.
                log.warn("Failed to close the XML event reader: " + e.getMessage());
                java.util.List<String> withClose = new ArrayList<>(warnings);
                withClose.add("Failed to close the XML event reader: " + e.getMessage());
                result = new CsdlModel(result.schemas(), List.copyOf(withClose));
            }
            return result;
        } finally {
            try {
                reader.close();
            } catch (XMLStreamException e) {
                log.warn("Failed to close the XML event reader: " + e.getMessage());
            }
        }
    }

    /**
     * Records a skipped unknown element. Every silent drop is a potential typo'd
     * member name (e.g. {@code <Proprety>}) — surfacing it beats debugging why a
     * generated client is missing a property. Inline {@code <Annotation>} elements
     * are exempt: they are legal CSDL that this parser discards by design
     * (vocabulary annotations carry no codegen signal), and real feeds
     * (TripPin, OData Demo) nest them inside EntityType/EntityContainer bodies —
     * warning on them would be noise, not signal.
     */
    private void warnIgnored(String parent, StartElement element) {
        warnIgnored(parent, element, null);
    }

    /**
     * Single namespace-aware implementation. {@code detail} carries caller-specific context
     * (e.g. a mismatched {@code Namespace}); the EDM-namespace {@code Annotation} exemption
     * applies here for every site so parse-time and ordinary skips cannot drift.
     */
    private void warnIgnored(String parent, StartElement element, String detail) {
        String localName = element.getName().getLocalPart();
        String namespace = element.getName().getNamespaceURI();
        if (EDM_NS.equals(namespace) && "Annotation".equals(localName)) {
            return;
        }
        warnings.add(parent + ": ignored unknown element <" + localName + ">"
                + (detail != null ? detail : "")
                + (EDM_NS.equals(namespace) ? "" : " in namespace '" + namespace + "'"));
    }

    private List<SchemaModel> fixupCrossSchemaAliases(List<SchemaModel> schemas) {
        if (globalAliasMap.isEmpty()) return schemas;
        List<SchemaModel> fixed = new ArrayList<>(schemas.size());
        for (SchemaModel s : schemas) {
            List<EntityTypeModel> ets = new ArrayList<>();
            for (EntityTypeModel e : s.entityTypes()) {
                List<PropertyModel> props = new ArrayList<>();
                for (PropertyModel p : e.properties()) props.add(new PropertyModel(p.name(), fixAlias(p.edmType()), p.nullable(), p.defaultValue(), p.annotations()));
                List<NavigationPropertyModel> navs = new ArrayList<>();
                for (NavigationPropertyModel n : e.navigationProperties()) navs.add(new NavigationPropertyModel(n.name(), fixAlias(n.type()), n.partner(), n.containsTarget(), n.nullable(), n.referentialConstraints(), n.annotations()));
                ets.add(new EntityTypeModel(e.name(), fixAlias(e.baseType()), e.openType(), e.abstractType(), e.hasStream(), e.keys(), props, navs));
            }
            List<ComplexTypeModel> cts = new ArrayList<>();
            for (ComplexTypeModel c : s.complexTypes()) {
                List<PropertyModel> props = new ArrayList<>();
                for (PropertyModel p : c.properties()) props.add(new PropertyModel(p.name(), fixAlias(p.edmType()), p.nullable(), p.defaultValue(), p.annotations()));
                List<NavigationPropertyModel> navs = new ArrayList<>();
                for (NavigationPropertyModel n : c.navigationProperties()) navs.add(new NavigationPropertyModel(n.name(), fixAlias(n.type()), n.partner(), n.containsTarget(), n.nullable(), n.referentialConstraints(), n.annotations()));
                cts.add(new ComplexTypeModel(c.name(), fixAlias(c.baseType()), c.openType(), c.abstractType(), props, navs));
            }
            List<EnumTypeModel> enums = s.enumTypes();
            List<TypeDefinitionModel> tds = new ArrayList<>();
            for (TypeDefinitionModel td : s.typeDefinitions()) tds.add(new TypeDefinitionModel(td.name(), fixAlias(td.underlyingType())));
            List<FunctionModel> fns = new ArrayList<>();
            for (FunctionModel fn : s.functions()) {
                List<ParameterModel> params = new ArrayList<>();
                for (ParameterModel pm : fn.parameters()) params.add(new ParameterModel(pm.name(), fixAlias(pm.type()), pm.nullable()));
                ReturnTypeModel rt = fn.returnType() == null ? null : new ReturnTypeModel(fixAlias(fn.returnType().type()), fn.returnType().nullable());
                fns.add(new FunctionModel(fn.name(), fn.isBound(), fn.isComposable(), fn.entitySetPath(), params, rt));
            }
            List<ActionModel> acts = new ArrayList<>();
            for (ActionModel a : s.actions()) {
                List<ParameterModel> params = new ArrayList<>();
                for (ParameterModel pm : a.parameters()) params.add(new ParameterModel(pm.name(), fixAlias(pm.type()), pm.nullable()));
                ReturnTypeModel rt = a.returnType() == null ? null : new ReturnTypeModel(fixAlias(a.returnType().type()), a.returnType().nullable());
                acts.add(new ActionModel(a.name(), a.isBound(), a.entitySetPath(), params, rt));
            }
            List<ContainerModel> containers = new ArrayList<>();
            for (ContainerModel c : s.containers()) {
                List<EntitySetModel> ess = new ArrayList<>();
                for (EntitySetModel es : c.entitySets()) ess.add(new EntitySetModel(es.name(), fixAlias(es.entityType()), es.navigationPropertyBindings(), es.annotations()));
                List<SingletonModel> sing = new ArrayList<>();
                for (SingletonModel sm : c.singletons()) sing.add(new SingletonModel(sm.name(), fixAlias(sm.type()), sm.navigationPropertyBindings()));
                List<FunctionImportModel> fim = new ArrayList<>();
                for (FunctionImportModel fi : c.functionImports()) fim.add(new FunctionImportModel(fi.name(), fixAlias(fi.function()), fi.entitySet(), fi.includeInServiceDocument()));
                List<ActionImportModel> aim = new ArrayList<>();
                for (ActionImportModel ai : c.actionImports()) aim.add(new ActionImportModel(ai.name(), fixAlias(ai.action()), ai.entitySet()));
                containers.add(new ContainerModel(c.name(), fixAlias(c.extendsContainer()), ess, sing, fim, aim));
            }
            fixed.add(new SchemaModel(s.namespace(), s.alias(), ets, cts, enums, tds, fns, acts, containers));
        }
        return fixed;
    }

    private String fixAlias(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return trimmed;
        boolean isCollection = trimmed.startsWith("Collection(");
        // Shared with resolveTypeRef so the post-pass cannot silently accept a
        // nested/malformed Collection(...) that parse time rejects.
        String inner = unwrapCollectionType(trimmed, "type reference");
        int dot = inner.indexOf('.');
        if (dot > 0) {
            String rewritten = applyAliasMap(inner, dot);
            if (rewritten != null) {
                inner = rewritten;
            }
        }
        return isCollection ? "Collection(" + inner + ")" : inner;
    }

    /**
     * Rejects self/cyclic {@code TypeDefinition} {@code UnderlyingType} chains (A→A, A→B→A).
     * A cyclic chain is invalid CSDL and can never resolve to a primitive. The generator's
     * typedef resolver carries a cycle guard too (defence in depth for programmatically built
     * models), but the parser fails here with a readable chain at the metadata boundary.
     */
    private void validateTypeDefinitionCycles(List<SchemaModel> schemas) {
        Map<String, SchemaModel> owner = new HashMap<>();
        for (SchemaModel schema : schemas) {
            for (TypeDefinitionModel td : schema.typeDefinitions()) {
                owner.put(schema.namespace() + "." + td.name(), schema);
            }
        }
        if (owner.isEmpty()) {
            return;
        }
        Set<String> typedefNames = owner.keySet();
        for (String start : typedefNames) {
            walkTypeDefinition(start, owner, typedefNames, new LinkedHashSet<>());
        }
    }

    private void walkTypeDefinition(String current, Map<String, SchemaModel> owner,
                                    Set<String> typedefNames, LinkedHashSet<String> path) {
        if (!path.add(current)) {
            throw new IllegalArgumentException("Cyclic TypeDefinition UnderlyingType: "
                    + String.join(" -> ", path) + " -> " + current);
        }
        TypeDefinitionModel td = findTypeDefinition(current, owner);
        if (td != null) {
            String next = resolveUnderlyingTypeDefinition(td.underlyingType(), owner.get(current), typedefNames);
            if (next != null) {
                walkTypeDefinition(next, owner, typedefNames, path);
            }
        }
        path.remove(current);
    }

    private TypeDefinitionModel findTypeDefinition(String qualified, Map<String, SchemaModel> owner) {
        SchemaModel schema = owner.get(qualified);
        if (schema == null) {
            return null;
        }
        String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
        for (TypeDefinitionModel td : schema.typeDefinitions()) {
            if (td.name().equals(simple)) {
                return td;
            }
        }
        return null;
    }

    private String resolveUnderlyingTypeDefinition(String underlying, SchemaModel schema,
                                                   Set<String> typedefNames) {
        if (underlying == null) {
            return null;
        }
        String value = underlying.trim();
        if (value.isEmpty() || value.indexOf('(') >= 0 || value.indexOf(')') >= 0) {
            return null; // not a plain single type name
        }
        if (typedefNames.contains(value)) {
            return value; // already qualified
        }
        if (value.indexOf('.') > 0) {
            return null; // qualified but not a known TypeDefinition
        }
        String sameSchema = schema.namespace() + "." + value;
        if (typedefNames.contains(sameSchema)) {
            return sameSchema;
        }
        String match = null;
        for (String qualified : typedefNames) {
            if (qualified.endsWith("." + value)) {
                if (match != null) {
                    return null; // ambiguous unqualified ref — generator resolution policy handles it
                }
                match = qualified;
            }
        }
        return match;
    }

    /**
     * Shared rewrite for both {@link #resolveTypeRef} (parse time) and {@link #fixAlias}
     * (post-pass): if the prefix before the first dot is a known document alias, returns
     * the name rewritten to that alias's real namespace; otherwise {@code null}.
     * Keeping one implementation prevents the two paths from drifting apart.
     *
     * <p>An alias is a prefix substitution for the schema that DECLARES it, and it never
     * shadows a real namespace. Keying the rewrite on the first dot-separated segment
     * alone therefore corrupts a legitimate fully-qualified reference in two ways: with
     * {@code Namespace="Contoso.Model" Alias="Contoso"}, another schema's
     * {@code Contoso.Model.Address} became {@code Contoso.Model.Model.Address}; and when
     * the alias prefix is ALSO a declared namespace of its own
     * ({@code Namespace="Contoso"} alongside the above), a reference
     * {@code Contoso.Account} was redirected to {@code Contoso.Model.Account}. Both are
     * guarded by leaving a reference that resolves without the alias alone.
     */
    private String applyAliasMap(String inner, int dot) {
        String alias = inner.substring(0, dot);
        String ns = globalAliasMap.get(alias);
        if (ns == null) {
            return null;
        }
        String remainder = inner.substring(alias.length());
        String rewritten = ns + remainder;
        // Already fully qualified under the real namespace: the reference is not an alias
        // use, it is a qualified name whose first segment coincidentally matches one.
        // Re-applying the alias to it is what compounded the corruption across the
        // parse-time rewrite and the post-pass.
        if (inner.startsWith(ns)) {
            return null;
        }
        // The alias prefix is ITSELF a declared namespace of some other schema, so `alias`
        // is being used as a real namespace qualifier here and the reference is already
        // fully qualified. Rewriting it would redirect a legitimate cross-schema reference
        // into a namespace it was never written against. When both readings are possible
        // the qualified one wins: an alias is only ever a shorthand, and a reference that
        // resolves without it is not using it.
        if (isDeclaredNamespace(alias)) {
            return null;
        }
        return rewritten;
    }

    /**
     * True when {@code candidate} is the namespace of some schema in this document, i.e. a
     * real namespace rather than an alias.
     *
     * <p>An alias is a prefix substitution for the schema that DECLARES it and never
     * shadows a real namespace. The alias prefix can itself be a declared namespace of a
     * DIFFERENT schema ({@code Namespace="Contoso"} alongside
     * {@code Namespace="Contoso.Model" Alias="Contoso"}), and then a reference using it is
     * already fully qualified. Without this check the rewrite would redirect a legitimate
     * cross-schema reference into a namespace it was never written against.
     */
    private boolean isDeclaredNamespace(String candidate) {
        return declaredNamespaces.contains(candidate);
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
                    requireAttr(root, "Version", "Edmx");
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
            } else if (event.isStartElement()) {
                StartElement el = event.asStartElement();
                String skipped = el.getName().getLocalPart();
                if ("Schema".equals(skipped)) {
                    // Right element, wrong namespace: name it, or the skip is a mystery
                    // (a typo'd xmlns silently dropping a whole schema otherwise)
                    String declared = getAttr(el, "Namespace");
                    warnIgnored("DataServices", el,
                            (declared != null ? " Namespace='" + declared + "'" : "")
                                    + " (namespace mismatch)");
                } else {
                    warnIgnored("DataServices", el);
                }
                skipElement(reader);
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
        declaredNamespaces.add(namespace);
        if (alias != null && !alias.isBlank()) {
            String existingNs = globalAliasMap.putIfAbsent(alias, namespace);
            if (existingNs != null && !existingNs.equals(namespace)) {
                // CSDL requires an alias to be unambiguous across the document; a conflict
                // would silently resolve references to the FIRST namespace (order-dependent)
                throw new IllegalArgumentException(
                        "Duplicate Alias '" + alias + "' maps to multiple namespaces ('"
                                + existingNs + "' and '" + namespace
                                + "'); aliases must be unambiguous within a document");
            }
        }

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
                if (!EDM_NS.equals(el.getName().getNamespaceURI())) {
                    warnIgnored("Schema '" + namespace + "'", el);
                    skipElement(reader);
                    continue;
                }
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
                        warnIgnored("Schema '" + namespace + "'", el);
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
        String baseType = resolveTypeRef(getAttr(el, "BaseType"),
                "BaseType of EntityType '" + name + "'");
        boolean openType = booleanAttr(el, "OpenType", false, "EntityType '" + name + "'");
        boolean abstractType = booleanAttr(el, "Abstract", false, "EntityType '" + name + "'");
        boolean hasStream = booleanAttr(el, "HasStream", false, "EntityType '" + name + "'");

        List<KeyModel> keys = new ArrayList<>();
        List<PropertyModel> properties = new ArrayList<>();
        List<NavigationPropertyModel> navProps = new ArrayList<>();

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                StartElement child = event.asStartElement();
                if (!EDM_NS.equals(child.getName().getNamespaceURI())) {
                    warnIgnored("EntityType '" + name + "'", child);
                    skipElement(reader);
                    continue;
                }
                String localName = child.getName().getLocalPart();
                switch (localName) {
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
                    default -> {
                        warnIgnored("EntityType '" + name + "'", child);
                        skipElement(reader);
                    }
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
        String baseType = resolveTypeRef(getAttr(el, "BaseType"),
                "BaseType of ComplexType '" + name + "'");
        boolean openType = booleanAttr(el, "OpenType", false, "ComplexType '" + name + "'");
        boolean abstractType = booleanAttr(el, "Abstract", false, "ComplexType '" + name + "'");

        List<PropertyModel> properties = new ArrayList<>();
        List<NavigationPropertyModel> navProps = new ArrayList<>();

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                StartElement child = event.asStartElement();
                if (!EDM_NS.equals(child.getName().getNamespaceURI())) {
                    warnIgnored("ComplexType '" + name + "'", child);
                    skipElement(reader);
                    continue;
                }
                String localName = child.getName().getLocalPart();
                switch (localName) {
                    case "Property" -> properties.add(parseProperty(reader, child));
                    case "NavigationProperty" -> navProps.add(parseNavigationProperty(reader, child));
                    default -> {
                        warnIgnored("ComplexType '" + name + "'", child);
                        skipElement(reader);
                    }
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
            if (event.isStartElement()) {
                StartElement child = event.asStartElement();
                if (isEdmElement(child, "PropertyRef")) {
                    propertyRefs.add(requireAttr(child, "Name",
                            "PropertyRef in <Key> of EntityType '" + entityName + "'"));
                    String alias = getAttr(child, "Alias");
                    aliases.add(alias != null ? alias : "");
                } else {
                    warnIgnored("Key of EntityType '" + entityName + "'", child);
                    skipElement(reader);
                }
            } else if (event.isEndElement() && isEdmElement(event.asEndElement(), "Key")) {
                return keyModel(propertyRefs, aliases, entityName);
            }
        }
        return keyModel(propertyRefs, aliases, entityName);
    }

    private KeyModel keyModel(List<String> propertyRefs, List<String> aliases, String entityName) {
        if (propertyRefs.isEmpty()) {
            throw new IllegalArgumentException("Key of EntityType '" + entityName
                    + "' must contain at least one PropertyRef");
        }
        return new KeyModel(propertyRefs, aliases);
    }

    private PropertyModel parseProperty(XMLEventReader reader, StartElement el)
            throws XMLStreamException {
        String name = requireAttr(el, "Name", "Property");
        String edmType = resolveTypeRef(requireAttr(el, "Type", "Property '" + name + "'"),
                "Type of Property '" + name + "'");
        boolean nullable = booleanAttr(el, "Nullable", true, "Property '" + name + "'");
        String defaultValue = getAttr(el, "DefaultValue");
        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                StartElement child = event.asStartElement();
                if (!EDM_NS.equals(child.getName().getNamespaceURI())
                        || !"Annotation".equals(child.getName().getLocalPart())) {
                    warnIgnored("Property '" + name + "'", child);
                }
                skipElement(reader);
            } else if (event.isEndElement() && isEdmElement(event.asEndElement(), "Property")) {
                return new PropertyModel(name, edmType, nullable, defaultValue, List.of());
            }
        }
        throw new IllegalArgumentException("Property '" + name + "' is missing its closing element");
    }

    private NavigationPropertyModel parseNavigationProperty(XMLEventReader reader, StartElement el)
            throws XMLStreamException {
        String name = requireAttr(el, "Name", "NavigationProperty");
        String type = resolveTypeRef(requireAttr(el, "Type", "NavigationProperty '" + name + "'"),
                "Type of NavigationProperty '" + name + "'");
        String partner = getAttr(el, "Partner");
        boolean containsTarget = booleanAttr(el, "ContainsTarget", false,
                "NavigationProperty '" + name + "'");
        boolean nullable = booleanAttr(el, "Nullable", true, "NavigationProperty '" + name + "'");

        List<ReferentialConstraintModel> constraints = new ArrayList<>();

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                StartElement child = event.asStartElement();
                if (isEdmElement(child, "ReferentialConstraint")) {
                    constraints.addAll(parseReferentialConstraint(reader, child));
                } else {
                    warnIgnored("NavigationProperty '" + name + "'", child);
                    skipElement(reader);
                }
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
        boolean sawPrincipal = false;
        boolean sawDependent = false;
        String section = null;
        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                StartElement el = event.asStartElement();
                if (!EDM_NS.equals(el.getName().getNamespaceURI())) {
                    warnIgnored("ReferentialConstraint", el);
                    skipElement(reader);
                    continue;
                }
                switch (el.getName().getLocalPart()) {
                    case "Principal" -> {
                        if (sawPrincipal) {
                            throw new IllegalArgumentException(
                                    "ReferentialConstraint must contain at most one Principal element");
                        }
                        sawPrincipal = true;
                        section = "P";
                    }
                    case "Dependent" -> {
                        if (sawDependent) {
                            throw new IllegalArgumentException(
                                    "ReferentialConstraint must contain at most one Dependent element");
                        }
                        sawDependent = true;
                        section = "D";
                    }
                    case "PropertyRef" -> {
                        if (section == null) {
                            throw new IllegalArgumentException(
                                    "PropertyRef in ReferentialConstraint must be nested in Principal or Dependent");
                        }
                        String name = requireAttr(el, "Name", "PropertyRef in ReferentialConstraint");
                        if ("P".equals(section)) principal.add(name);
                        else dependent.add(name);
                    }
                    default -> {
                        warnIgnored("ReferentialConstraint", el);
                        skipElement(reader);
                    }
                }
            } else if (event.isEndElement()) {
                javax.xml.stream.events.EndElement end = event.asEndElement();
                if (isEdmElement(end, "Principal") || isEdmElement(end, "Dependent")) {
                    section = null;
                } else if (isEdmElement(end, "ReferentialConstraint")) {
                    String property = getAttr(constraintEl, "Property");
                    String referencedProperty = getAttr(constraintEl, "ReferencedProperty");
                    if (property != null || referencedProperty != null) {
                        property = requireAttr(constraintEl, "Property", "ReferentialConstraint");
                        referencedProperty = requireAttr(constraintEl, "ReferencedProperty",
                                "ReferentialConstraint");
                        if (sawPrincipal || sawDependent) {
                            throw new IllegalArgumentException(
                                    "ReferentialConstraint must use either Property/ReferencedProperty attributes "
                                            + "or nested Principal/Dependent elements, not both");
                        }
                        return List.of(new ReferentialConstraintModel(property, referencedProperty));
                    }
                    if (!sawPrincipal || !sawDependent) {
                        throw new IllegalArgumentException(
                                "ReferentialConstraint must contain both Principal and Dependent elements "
                                        + "or both Property and ReferencedProperty attributes");
                    }
                    if (principal.isEmpty() || dependent.isEmpty()) {
                        throw new IllegalArgumentException(
                                "ReferentialConstraint Principal and Dependent must each contain at least one PropertyRef");
                    }
                    if (principal.size() != dependent.size()) {
                        throw new IllegalArgumentException(
                                "ReferentialConstraint has " + principal.size()
                                        + " principal PropertyRefs but " + dependent.size()
                                        + " dependent PropertyRefs");
                    }
                    List<ReferentialConstraintModel> result = new ArrayList<>();
                    for (int i = 0; i < principal.size(); i++) {
                        result.add(new ReferentialConstraintModel(dependent.get(i), principal.get(i)));
                    }
                    return result;
                }
            }
        }
        throw new IllegalArgumentException("ReferentialConstraint is missing its closing element");
    }

    private EnumTypeModel parseEnumType(XMLEventReader reader, StartElement el)
            throws XMLStreamException {
        String name = requireAttr(el, "Name", "EnumType");
        String rawUnderlyingType = getAttr(el, "UnderlyingType");
        String underlyingType = rawUnderlyingType == null ? "Edm.Int32"
                : resolveTypeRef(requireAttr(el, "UnderlyingType", "EnumType '" + name + "'"),
                "UnderlyingType of EnumType '" + name + "'");
        boolean isFlags = booleanAttr(el, "IsFlags", false, "EnumType '" + name + "'");

        List<EnumMemberModel> members = new ArrayList<>();
        // CSDL: a Member without Value defaults to the previous member's value + 1 (0 if first)
        long lastValue = -1;
        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                StartElement child = event.asStartElement();
                if (isEdmElement(child, "Member")) {
                    StartElement memberEl = child;
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
                } else {
                    warnIgnored("EnumType '" + name + "'", child);
                    skipElement(reader);
                }
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
                requireAttr(el, "UnderlyingType", "TypeDefinition '" + name + "'"),
                "UnderlyingType of TypeDefinition '" + name + "'");
        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                StartElement child = event.asStartElement();
                if (!EDM_NS.equals(child.getName().getNamespaceURI())
                        || !"Annotation".equals(child.getName().getLocalPart())) {
                    warnIgnored("TypeDefinition '" + name + "'", child);
                }
                skipElement(reader);
            } else if (event.isEndElement() && isEdmElement(event.asEndElement(), "TypeDefinition")) {
                return new TypeDefinitionModel(name, underlyingType);
            }
        }
        throw new IllegalArgumentException("TypeDefinition '" + name + "' is missing its closing element");
    }

    private FunctionModel parseFunction(XMLEventReader reader, StartElement el)
            throws XMLStreamException {
        String name = requireAttr(el, "Name", "Function");
        boolean isBound = booleanAttr(el, "IsBound", false, "Function '" + name + "'");
        boolean isComposable = booleanAttr(el, "IsComposable", false, "Function '" + name + "'");
        String entitySetPath = getAttr(el, "EntitySetPath");

        List<ParameterModel> parameters = new ArrayList<>();
        ReturnTypeModel returnType = null;

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                StartElement child = event.asStartElement();
                if (!EDM_NS.equals(child.getName().getNamespaceURI())) {
                    warnIgnored("Function '" + name + "'", child);
                    skipElement(reader);
                    continue;
                }
                switch (child.getName().getLocalPart()) {
                    case "Parameter" -> parameters.add(parseParameter(child,
                            "Parameter of Function '" + name + "'"));
                    case "ReturnType" -> {
                        if (returnType != null) {
                            throw new IllegalArgumentException(
                                    "Function '" + name + "' must contain exactly one ReturnType element");
                        }
                        returnType = parseReturnType(child, "ReturnType of Function '" + name + "'");
                    }
                    default -> {
                        warnIgnored("Function '" + name + "'", child);
                        skipElement(reader);
                    }
                }
            } else if (event.isEndElement() && isEdmElement(event.asEndElement(), "Function")) {
                return functionModel(name, isBound, isComposable, entitySetPath, parameters, returnType);
            }
        }

        return functionModel(name, isBound, isComposable, entitySetPath, parameters, returnType);
    }

    private FunctionModel functionModel(String name, boolean isBound, boolean isComposable,
                                        String entitySetPath, List<ParameterModel> parameters,
                                        ReturnTypeModel returnType) {
        if (returnType == null) {
            throw new IllegalArgumentException(
                    "Function '" + name + "' must contain exactly one ReturnType element");
        }
        return new FunctionModel(name, isBound, isComposable, entitySetPath, parameters, returnType);
    }

    private ActionModel parseAction(XMLEventReader reader, StartElement el)
            throws XMLStreamException {
        String name = requireAttr(el, "Name", "Action");
        boolean isBound = booleanAttr(el, "IsBound", false, "Action '" + name + "'");
        String entitySetPath = getAttr(el, "EntitySetPath");

        List<ParameterModel> parameters = new ArrayList<>();
        ReturnTypeModel returnType = null;

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                StartElement child = event.asStartElement();
                if (!EDM_NS.equals(child.getName().getNamespaceURI())) {
                    warnIgnored("Action '" + name + "'", child);
                    skipElement(reader);
                    continue;
                }
                switch (child.getName().getLocalPart()) {
                    case "Parameter" -> parameters.add(parseParameter(child,
                            "Parameter of Action '" + name + "'"));
                    case "ReturnType" -> {
                        if (returnType != null) {
                            throw new IllegalArgumentException(
                                    "Action '" + name + "' must contain at most one ReturnType element");
                        }
                        returnType = parseReturnType(child, "ReturnType of Action '" + name + "'");
                    }
                    default -> {
                        warnIgnored("Action '" + name + "'", child);
                        skipElement(reader);
                    }
                }
            } else if (event.isEndElement() && isEdmElement(event.asEndElement(), "Action")) {
                return new ActionModel(name, isBound, entitySetPath, parameters, returnType);
            }
        }

        return new ActionModel(name, isBound, entitySetPath, parameters, returnType);
    }

    private ParameterModel parseParameter(StartElement el, String description) {
        return new ParameterModel(
                requireAttr(el, "Name", description),
                resolveTypeRef(requireAttr(el, "Type", description), "Type of " + description),
                booleanAttr(el, "Nullable", true, description));
    }

    private ReturnTypeModel parseReturnType(StartElement el, String description) {
        return new ReturnTypeModel(
                resolveTypeRef(requireAttr(el, "Type", description), "Type of " + description),
                booleanAttr(el, "Nullable", true, description));
    }

    private ContainerModel parseEntityContainer(XMLEventReader reader, StartElement el)
            throws XMLStreamException {
        String name = requireAttr(el, "Name", "EntityContainer");
        String extendsContainer = resolveTypeRef(getAttr(el, "Extends"),
                "Extends of EntityContainer '" + name + "'");

        List<EntitySetModel> entitySets = new ArrayList<>();
        List<SingletonModel> singletons = new ArrayList<>();
        List<FunctionImportModel> functionImports = new ArrayList<>();
        List<ActionImportModel> actionImports = new ArrayList<>();

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                StartElement child = event.asStartElement();
                if (!EDM_NS.equals(child.getName().getNamespaceURI())) {
                    warnIgnored("EntityContainer '" + name + "'", child);
                    skipElement(reader);
                    continue;
                }
                String localName = child.getName().getLocalPart();
                switch (localName) {
                    case "EntitySet" -> entitySets.add(parseEntitySet(reader, child));
                    case "Singleton" -> singletons.add(parseSingleton(reader, child));
                    case "FunctionImport" -> functionImports.add(parseFunctionImport(reader, child));
                    case "ActionImport" -> actionImports.add(parseActionImport(reader, child));
                    default -> {
                        warnIgnored("EntityContainer '" + name + "'", child);
                        skipElement(reader);
                    }
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
        String entityType = resolveTypeRef(requireAttr(el, "EntityType", "EntitySet '" + name + "'"),
                "EntityType of EntitySet '" + name + "'");

        List<NavigationPropertyBindingModel> bindings = new ArrayList<>();

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                StartElement child = event.asStartElement();
                if (isEdmElement(child, "NavigationPropertyBinding")) {
                    bindings.add(parseNavigationPropertyBinding(child, "EntitySet '" + name + "'"));
                } else {
                    warnIgnored("EntitySet '" + name + "'", child);
                    skipElement(reader);
                }
            } else if (event.isEndElement() && isEdmElement(event.asEndElement(), "EntitySet")) {
                return new EntitySetModel(name, entityType, bindings, List.of());
            }
        }

        return new EntitySetModel(name, entityType, bindings, List.of());
    }

    private SingletonModel parseSingleton(XMLEventReader reader, StartElement el)
            throws XMLStreamException {
        String name = requireAttr(el, "Name", "Singleton");
        String type = resolveTypeRef(requireAttr(el, "Type", "Singleton '" + name + "'"),
                "Type of Singleton '" + name + "'");

        List<NavigationPropertyBindingModel> bindings = new ArrayList<>();

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                StartElement child = event.asStartElement();
                if (isEdmElement(child, "NavigationPropertyBinding")) {
                    bindings.add(parseNavigationPropertyBinding(child, "Singleton '" + name + "'"));
                } else {
                    warnIgnored("Singleton '" + name + "'", child);
                    skipElement(reader);
                }
            } else if (event.isEndElement() && isEdmElement(event.asEndElement(), "Singleton")) {
                return new SingletonModel(name, type, bindings);
            }
        }

        return new SingletonModel(name, type, bindings);
    }

    private NavigationPropertyBindingModel parseNavigationPropertyBinding(StartElement el,
                                                                          String parent) {
        return new NavigationPropertyBindingModel(
                requireAttr(el, "Path", "NavigationPropertyBinding in " + parent),
                requireAttr(el, "Target", "NavigationPropertyBinding in " + parent));
    }

    private FunctionImportModel parseFunctionImport(XMLEventReader reader, StartElement el)
            throws XMLStreamException {
        String name = requireAttr(el, "Name", "FunctionImport");
        String function = resolveTypeRef(requireAttr(el, "Function", "FunctionImport '" + name + "'"),
                "Function of FunctionImport '" + name + "'");
        String entitySet = getAttr(el, "EntitySet");
        boolean includeInServiceDocument = booleanAttr(el, "IncludeInServiceDocument", false,
                "FunctionImport '" + name + "'");
        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                warnIgnored("FunctionImport '" + name + "'", event.asStartElement());
                skipElement(reader);
            } else if (event.isEndElement() && isEdmElement(event.asEndElement(), "FunctionImport")) {
                return new FunctionImportModel(name, function, entitySet, includeInServiceDocument);
            }
        }
        throw new IllegalArgumentException("FunctionImport '" + name + "' is missing its closing element");
    }

    private ActionImportModel parseActionImport(XMLEventReader reader, StartElement el)
            throws XMLStreamException {
        String name = requireAttr(el, "Name", "ActionImport");
        String action = resolveTypeRef(requireAttr(el, "Action", "ActionImport '" + name + "'"),
                "Action of ActionImport '" + name + "'");
        String entitySet = getAttr(el, "EntitySet");
        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                warnIgnored("ActionImport '" + name + "'", event.asStartElement());
                skipElement(reader);
            } else if (event.isEndElement() && isEdmElement(event.asEndElement(), "ActionImport")) {
                return new ActionImportModel(name, action, entitySet);
            }
        }
        throw new IllegalArgumentException("ActionImport '" + name + "' is missing its closing element");
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

    private boolean booleanAttr(StartElement el, String name, boolean defaultValue,
                                String elementDescription) {
        String value = getAttr(el, name);
        if (value == null) {
            return defaultValue;
        }
        return switch (value.trim()) {
            case "true", "1" -> true;
            case "false", "0" -> false;
            default -> throw new IllegalArgumentException(elementDescription
                    + " has invalid xs:boolean attribute '" + name + "' value '" + value
                    + "'; expected true, false, 1, or 0");
        };
    }

    /**
     * Resolves alias-qualified type references ({@code self.Address}) to the schema's real
     * namespace, preserving {@code Collection(...)} wrappers. Without this, alias refs
     * resolve as unknown types and the generators emit wrong packages/imports.
     * Checks global alias map (cross-schema) first, then current schema alias.
     */
    private String resolveTypeRef(String raw, String description) {
        if (raw == null) {
            return null;
        }
        boolean isCollection = raw.trim().startsWith("Collection(");
        String value = unwrapCollectionType(raw, description);
        int dot = value.indexOf('.');
        if (dot > 0) {
            String rewritten = applyAliasMap(value, dot);
            if (rewritten == null && currentAlias != null && currentNamespace != null
                    && value.startsWith(currentAlias + ".")) {
                rewritten = currentNamespace + value.substring(currentAlias.length());
            }
            if (rewritten != null) {
                value = rewritten;
            }
        }
        return isCollection ? "Collection(" + value + ")" : value;
    }

    /**
     * Shared {@code Collection(...)} parsing/validation for {@link #resolveTypeRef} (parse time)
     * and {@link #fixAlias} (post-pass): trims, rejects nested/malformed {@code Collection(...)}
     * and stray parentheses, and returns the inner (unwrapped) type name. One implementation
     * keeps parse-time and post-pass from drifting apart.
     */
    private String unwrapCollectionType(String raw, String description) {
        String value = raw == null ? "" : raw.trim();
        boolean isCollection = value.startsWith("Collection(");
        if (value.contains("Collection(") && !isCollection) {
            throw invalidTypeReference(value, description);
        }
        if (isCollection) {
            if (!value.endsWith(")")) {
                throw invalidTypeReference(value, description);
            }
            value = value.substring("Collection(".length(), value.length() - 1).trim();
            if (value.isEmpty() || value.indexOf('(') >= 0 || value.indexOf(')') >= 0) {
                throw invalidTypeReference(value, description);
            }
        } else if (value.indexOf('(') >= 0 || value.indexOf(')') >= 0) {
            throw invalidTypeReference(value, description);
        }
        return value;
    }

    private IllegalArgumentException invalidTypeReference(String value, String description) {
        return new IllegalArgumentException(description + " has invalid Collection type '" + value
                + "'; expected a non-nested Collection(type) or a single type name");
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
        // Identity-keyed: containers are records (value equality), so two
        // identical-valued containers from different schemas would collapse
        // last-wins under equals() — and an unqualified Extends would then
        // resolve in the WRONG schema's namespace. (The `candidate != container`
        // check below is already identity-based and stays as is.)
        Map<ContainerModel, String> namespaceOf = new IdentityHashMap<>();
        for (SchemaModel schema : schemas) {
            for (ContainerModel container : schema.containers()) {
                String qualified = schema.namespace() + "." + container.name();
                if (byQualifiedName.putIfAbsent(qualified, container) != null) {
                    // Two schemas in one namespace declaring the same container name — the FQN
                    // collides, so keeping the first silently would drop the other.
                    throw new IllegalArgumentException(
                            "Duplicate EntityContainer '" + qualified + "': more than one container "
                                    + "shares the same qualified name");
                }
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
        if (base == null && extendsName.indexOf('.') < 0) {
            ContainerModel found = null;
            int matches = 0;
            String simple = Names.simpleNameFromFullName(extendsName);
            for (ContainerModel candidate : byQualifiedName.values()) {
                if (candidate != container && candidate.name().equals(simple)) {
                    found = candidate;
                    matches++;
                }
            }
            if (matches == 1) {
                base = found;
            } else if (matches > 1) {
                throw new IllegalArgumentException(
                        "Ambiguous unqualified EntityContainer Extends '" + extendsName
                                + "' in '" + fqn + "': matches " + matches
                                + " containers with that simple name; use qualified name");
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
