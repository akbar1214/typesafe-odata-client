package io.github.akbarhusain.odata.core.model;

import java.util.List;

public record CsdlModel(
    List<SchemaModel> schemas,
    List<String> warnings
) {
    public record SchemaModel(
        String namespace,
        String alias,
        List<EntityTypeModel> entityTypes,
        List<ComplexTypeModel> complexTypes,
        List<EnumTypeModel> enumTypes,
        List<TypeDefinitionModel> typeDefinitions,
        List<FunctionModel> functions,
        List<ActionModel> actions,
        List<ContainerModel> containers
    ) {}

    public record EntityTypeModel(
        String name,
        String baseType,
        boolean openType,
        boolean abstractType,
        boolean hasStream,
        List<KeyModel> keys,
        List<PropertyModel> properties,
        List<NavigationPropertyModel> navigationProperties
    ) {}

    public record ComplexTypeModel(
        String name,
        String baseType,
        boolean openType,
        boolean abstractType,
        List<PropertyModel> properties,
        List<NavigationPropertyModel> navigationProperties
    ) {}

    public record KeyModel(
        List<String> propertyRefs
    ) {}

    public record PropertyModel(
        String name,
        String edmType,
        boolean nullable,
        String defaultValue,
        List<AnnotationModel> annotations
    ) {}

    public record NavigationPropertyModel(
        String name,
        String type,
        String partner,
        boolean containsTarget,
        boolean nullable,
        List<ReferentialConstraintModel> referentialConstraints,
        List<AnnotationModel> annotations
    ) {}

    public record ReferentialConstraintModel(
        String property,
        String referencedProperty
    ) {}

    public record EnumTypeModel(
        String name,
        String underlyingType,
        boolean isFlags,
        List<EnumMemberModel> members
    ) {}

    public record EnumMemberModel(
        String name,
        long value
    ) {}

    public record TypeDefinitionModel(
        String name,
        String underlyingType
    ) {}

    public record FunctionModel(
        String name,
        boolean isBound,
        boolean isComposable,
        String entitySetPath,
        List<ParameterModel> parameters,
        ReturnTypeModel returnType
    ) {}

    public record ActionModel(
        String name,
        boolean isBound,
        String entitySetPath,
        List<ParameterModel> parameters,
        ReturnTypeModel returnType
    ) {}

    public record ParameterModel(
        String name,
        String type,
        boolean nullable
    ) {}

    public record ReturnTypeModel(
        String type,
        boolean nullable
    ) {}

    public record ContainerModel(
        String name,
        List<EntitySetModel> entitySets,
        List<SingletonModel> singletons,
        List<FunctionImportModel> functionImports,
        List<ActionImportModel> actionImports
    ) {}

    public record EntitySetModel(
        String name,
        String entityType,
        List<NavigationPropertyBindingModel> navigationPropertyBindings,
        List<AnnotationModel> annotations
    ) {}

    public record SingletonModel(
        String name,
        String type,
        List<NavigationPropertyBindingModel> navigationPropertyBindings
    ) {}

    public record FunctionImportModel(
        String name,
        String function,
        String entitySet,
        boolean includeInServiceDocument
    ) {}

    public record ActionImportModel(
        String name,
        String action,
        String entitySet
    ) {}

    public record NavigationPropertyBindingModel(
        String path,
        String target
    ) {}

    public record AnnotationModel(
        String term,
        String stringValue,
        Boolean boolValue
    ) {}
}
