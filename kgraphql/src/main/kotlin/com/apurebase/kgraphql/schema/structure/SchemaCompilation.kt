@file:Suppress("LocalVariableName")

package com.apurebase.kgraphql.schema.structure

import com.apurebase.kgraphql.Context
import com.apurebase.kgraphql.configuration.SchemaConfiguration
import com.apurebase.kgraphql.defaultKQLTypeName
import com.apurebase.kgraphql.getIterableElementType
import com.apurebase.kgraphql.isIterable
import com.apurebase.kgraphql.schema.DefaultSchema
import com.apurebase.kgraphql.schema.SchemaException
import com.apurebase.kgraphql.schema.directive.Directive
import com.apurebase.kgraphql.schema.execution.Execution
import com.apurebase.kgraphql.schema.introspection.NotIntrospected
import com.apurebase.kgraphql.schema.introspection.SchemaProxy
import com.apurebase.kgraphql.schema.introspection.TypeKind
import com.apurebase.kgraphql.schema.introspection.__Schema
import com.apurebase.kgraphql.schema.model.BaseOperationDef
import com.apurebase.kgraphql.schema.model.FunctionWrapper
import com.apurebase.kgraphql.schema.model.InputValueDef
import com.apurebase.kgraphql.schema.model.PropertyDef
import com.apurebase.kgraphql.schema.model.QueryDef
import com.apurebase.kgraphql.schema.model.SchemaDefinition
import com.apurebase.kgraphql.schema.model.Transformation
import com.apurebase.kgraphql.schema.model.TypeDef
import java.lang.reflect.ParameterizedType
import java.lang.reflect.TypeVariable
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.reflect.jvm.javaType
import kotlin.reflect.jvm.jvmErasure

@Suppress("UNCHECKED_CAST")
class SchemaCompilation(
        val configuration : SchemaConfiguration,
        val definition : SchemaDefinition
){

    private val queryTypeProxies = mutableMapOf<KClass<*>, TypeProxy>()

    private val inputTypeProxies = mutableMapOf<KClass<*>, TypeProxy>()

    private val unions = mutableListOf<Type.Union>()

    private val enums = definition.enums.associate { enum -> enum.kClass to enum.toEnumType() }

    private val scalars = definition.scalars.associate { scalar -> scalar.kClass to scalar.toScalarType() }

    private val schemaProxy = SchemaProxy(configuration)

    private val contextType = Type._Context()

    private val executionType = Type._ExecutionNode()

    private enum class TypeCategory {
        INPUT, QUERY
    }

    suspend fun perform(): DefaultSchema {
        definition.unions.forEach { handleUnionType(it) }
        definition.objects.forEach { handleObjectType(it.kClass) }
        definition.inputObjects.forEach { handleInputType(it.kClass) }
        val queryType = handleQueries()
        val mutationType = handleMutations()
        val subscriptionType = handleSubscriptions()

        queryTypeProxies.forEach { (kClass, typeProxy) ->
            introspectPossibleTypes(kClass, typeProxy)
            introspectInterfaces(kClass, typeProxy)
        }

        val model =  SchemaModel (
            query = queryType,
            mutation = if (mutationType.fields!!.isEmpty()) null else mutationType,
            subscription = if (subscriptionType.fields!!.isEmpty()) null else subscriptionType,

            enums = enums,
            scalars = scalars,
            unions = unions,

            queryTypes = queryTypeProxies + enums + scalars,
            inputTypes = inputTypeProxies + enums + scalars,
            allTypes = queryTypeProxies.values
                    + inputTypeProxies.values
                    + enums.values
                    + scalars.values
                    + unions.distinctBy(Type.Union::name),
            directives = definition.directives.map { handlePartialDirective(it) }
        )
        val schema = DefaultSchema(configuration, model)
        schemaProxy.proxiedSchema = schema
        return schema
    }

    private fun introspectPossibleTypes(kClass: KClass<*>, typeProxy: TypeProxy) {
        val proxied = typeProxy.proxied
        if (proxied is Type.Interface<*>) {
            val possibleTypes = queryTypeProxies.filter { (otherKClass, otherTypeProxy) ->
                otherTypeProxy.kind == TypeKind.OBJECT && otherKClass != kClass && otherKClass.isSubclassOf(kClass)
            }.values.toList()

            typeProxy.proxied = proxied.withPossibleTypes(possibleTypes)
        }
    }

    private fun introspectInterfaces(kClass: KClass<*>, typeProxy: TypeProxy) {
        val proxied = typeProxy.proxied
        if(proxied is Type.Object<*>){
            val interfaces = queryTypeProxies.filter { (otherKClass, otherTypeProxy) ->
                otherTypeProxy.kind == TypeKind.INTERFACE && otherKClass != kClass && kClass.isSubclassOf(otherKClass)
            }.values.toList()

            typeProxy.proxied = proxied.withInterfaces(interfaces)
        }
    }

    private suspend fun handlePartialDirective(directive: Directive.Partial) : Directive {
        val inputValues = handleInputValues(directive.name, directive.execution, emptyList())
        return directive.toDirective(inputValues)
    }

    private suspend fun handleQueries() : Type {
        return Type.OperationObject(
            name = "Query",
            description = "Query object",
            fields = definition.queries.map { handleOperation(it) } + introspectionSchemaQuery() + introspectionTypeQuery()
        )
    }

    private suspend fun handleMutations() : Type {
        return Type.OperationObject("Mutation", "Mutation object", definition.mutations.map { handleOperation(it) })
    }

    private suspend fun handleSubscriptions() : Type {
        return Type.OperationObject("Subscription", "Subscription object", definition.subscriptions.map { handleOperation(it) })
    }

    @Suppress("USELESS_CAST") // We are casting as __Schema so we don't get proxied types. https://github.com/aPureBase/KGraphQL/issues/45
    private suspend fun introspectionSchemaQuery() = handleOperation(
        QueryDef("__schema", FunctionWrapper.on<__Schema> { schemaProxy as __Schema })
    )

    private suspend fun introspectionTypeQuery() = handleOperation(
        QueryDef("__type", FunctionWrapper.on {
            name : String -> schemaProxy.findTypeByName(name)
        })
    )

    private suspend fun handleOperation(operation : BaseOperationDef<*, *>) : Field {
        val returnType = handlePossiblyWrappedType(operation.returnType, TypeCategory.QUERY)
        val inputValues = handleInputValues(operation.name, operation, operation.inputValues)
        return Field.Function(operation, returnType, inputValues)
    }

    private suspend fun handleUnionProperty(unionProperty: PropertyDef.Union<*>) : Field {
        val inputValues = handleInputValues(unionProperty.name, unionProperty, unionProperty.inputValues)
        val type = handleUnionType(unionProperty.union)

        return Field.Union(unionProperty, unionProperty.nullable, type, inputValues)
    }

    private suspend fun handlePossiblyWrappedType(kType : KType, typeCategory: TypeCategory) : Type = try {
        when {
            kType.isIterable() -> handleCollectionType(kType, typeCategory)
            kType.jvmErasure == Context::class && typeCategory == TypeCategory.INPUT -> contextType
            kType.jvmErasure == Execution.Node::class && typeCategory == TypeCategory.INPUT -> executionType
            kType.jvmErasure == Context::class && typeCategory == TypeCategory.QUERY -> throw SchemaException("Context type cannot be part of schema")
            kType.arguments.isNotEmpty() ->
                handleGenericsType(kType.javaType)
//            kType.arguments.isNotEmpty() -> configuration.genericTypeResolver.resolveMonad(kType)
//                .let { handlePossiblyWrappedType(it, typeCategory) }
            kType.jvmErasure.isSealed -> TypeDef.Union(
                name = kType.jvmErasure.simpleName!!,
                members = kType.jvmErasure.sealedSubclasses.toSet(),
                description = null
            ).let { handleUnionType(it) }
            else -> handleSimpleType(kType, typeCategory)
        }
    } catch (e: Throwable) {
        if ("KotlinReflectionInternalError" in e.toString()) {
            throw SchemaException("If you construct a query/mutation generically, you must specify the return type T explicitly with resolver{ ... }.returns<T>()")
        } else {
            throw e
        }
    }

    private suspend fun handlePossiblyWrappedType(jType: java.lang.reflect.Type, typeCategory: TypeCategory) : Type = try {
        when {
            Iterable::class.java.isAssignableFrom(jType.rawType) -> handleCollectionType(jType, typeCategory)
//            kType.jvmErasure == Context::class && typeCategory == TypeCategory.INPUT -> contextType
//            kType.jvmErasure == Execution.Node::class && typeCategory == TypeCategory.INPUT -> executionType
//            kType.jvmErasure == Context::class && typeCategory == TypeCategory.QUERY -> throw SchemaException("Context type cannot be part of schema")
            jType is ParameterizedType -> handleGenericsType(jType)
//            kType.arguments.isNotEmpty() -> configuration.genericTypeResolver.resolveMonad(kType)
//                .let { handlePossiblyWrappedType(it, typeCategory) }
//            kType.jvmErasure.isSealed -> TypeDef.Union(
//                name = kType.jvmErasure.simpleName!!,
//                members = kType.jvmErasure.sealedSubclasses.toSet(),
//                description = null
//            ).let { handleUnionType(it) }
            else -> handleSimpleType(jType, typeCategory)
        }
    } catch (e: Throwable) {
        if ("KotlinReflectionInternalError" in e.toString()) {
            throw SchemaException("If you construct a query/mutation generically, you must specify the return type T explicitly with resolver{ ... }.returns<T>()")
        } else {
            throw e
        }
    }

    private suspend fun handleGenericsType(jType: java.lang.reflect.Type): Type {

        // Copied from Moshi KotlinJsonAdapter

        val rawType = jType.rawType
//        assertValidGenericType(rawType, jType)
        val rawTypeKotlin = rawType.kotlin
//        assertValidGenericType(rawTypeKotlin, jType)

        println("handleGenericsType   type: $jType")
        println("handleGenericsType   rawType: $rawType")

        // Copied from handleObjectType

        // TODO: Let's not worry about superclasses for now
//        val objectDefs = definition.objects.filter { it.kClass.isSuperclassOf(rawTypeKotlin) }
//        val objectDef = objectDefs.find { it.jType == type } ?: TypeDef.Object(rawTypeKotlin.defaultKQLTypeName(), rawTypeKotlin, type)
        val objectDefs = emptyList<TypeDef.Object<*>>()
        val objectDef = TypeDef.Object(jType.typeName.replace("""(\w|\.)+""".toRegex()) { it.value.substringAfterLast(".") }.replace("""(\w)+\$""".toRegex(), ""), rawTypeKotlin)

        //treat introspection types as objects -> adhere to reference implementation behaviour
        val kind = if(rawTypeKotlin.isFinal || objectDef.name.startsWith("__")) TypeKind.OBJECT else TypeKind.INTERFACE

        val objectType = if(kind == TypeKind.OBJECT) Type.Object(objectDef) else Type.Interface(objectDef)

        // TODO: Let's not worry about differentiating between KClass<*> with different generics
        val typeProxy = TypeProxy(objectType)
        queryTypeProxies[rawTypeKotlin] = typeProxy

        val allKotlinProperties = objectDefs.fold(emptyMap<String, PropertyDef.Kotlin<*, *>>()) { acc, def ->
            acc + def.kotlinProperties.mapKeys { (property) -> property.name }
        }
        val allTransformations= objectDefs.fold(emptyMap<String, Transformation<*, *>>()) { acc, def ->
            acc + def.transformations.mapKeys { (property) -> property.name }
        }

        val kotlinFields = rawTypeKotlin.memberProperties
            .filter { field -> field.visibility == KVisibility.PUBLIC }
            .filterNot { field -> objectDefs.any { it.isIgnored(field.name) } }
            .map { property ->
                val resolvedPropertyType =
                    if (property.returnType.javaType is ParameterizedType || property.returnType.javaType is TypeVariable<*>) {
                        resolve(
                            jType,
                            rawType,
                            property.returnType.javaType
                        ).also {
                            println("handleGenericsType   resolvedPropertyType: $it")
                        }
                    } else {
                        null
                    }
                handleKotlinProperty(
                    kProperty = property,
                    kqlProperty = allKotlinProperties[property.name],
                    transformation = allTransformations[property.name],
                    resolvedPropertyType = resolvedPropertyType
                )
            }

        val extensionFields = objectDefs
            .flatMap(TypeDef.Object<*>::extensionProperties)
            .map { property -> handleOperation(property) }

        val dataloadExtensionFields = objectDefs
            .flatMap(TypeDef.Object<*>::dataloadExtensionProperties)
            .map { property -> handleDataloadOperation(property) }

        val unionFields = objectDefs
            .flatMap(TypeDef.Object<*>::unionProperties)
            .map { property -> handleUnionProperty(property) }

        val typenameResolver: suspend (Any) -> String? = { value: Any ->
            schemaProxy.typeByKClass(value.javaClass.kotlin)?.name ?: typeProxy.name
        }

        val __typenameField = handleOperation (
            PropertyDef.Function<Nothing, String?> ("__typename", FunctionWrapper.on(typenameResolver, true))
        )

        val declaredFields = kotlinFields + extensionFields + unionFields + dataloadExtensionFields

        if(declaredFields.isEmpty()){
            throw SchemaException("An Object type must define one or more fields. Found none on type ${objectDef.name}")
        }

        declaredFields.find { it.name.startsWith("__") }?.let { field ->
            throw SchemaException("Illegal name '${field.name}'. Names starting with '__' are reserved for introspection system")
        }

        val allFields = declaredFields + __typenameField
        typeProxy.proxied = if(kind == TypeKind.OBJECT) Type.Object(objectDef, allFields) else Type.Interface(objectDef, allFields)
        return typeProxy
    }

    private suspend fun handleCollectionType(kType: KType, typeCategory: TypeCategory): Type {
        val type = kType.getIterableElementType()
        val nullableListType = Type.AList(handleSimpleType(type, typeCategory))
        return applyNullability(kType, nullableListType)
    }

    private suspend fun handleCollectionType(jType: java.lang.reflect.Type, typeCategory: TypeCategory): Type {
        if(jType !is ParameterizedType) throw SchemaException("Cannot handle collection without element type")
        val type = jType.actualTypeArguments.first()
        val nullableListType = Type.AList(handleSimpleType(type, typeCategory))
        // TODO: Apply nullability to simple type
        return Type.NonNull(nullableListType)
    }

    private suspend fun handleSimpleType(kType: KType, typeCategory: TypeCategory): Type {
        val simpleType = handleRawType(kType.jvmErasure, typeCategory)
        return applyNullability(kType, simpleType)
    }

    private suspend fun handleSimpleType(jType: java.lang.reflect.Type, typeCategory: TypeCategory): Type {
        val simpleType = handleRawType(jType, typeCategory)
        // TODO: Apply nullability to simple type
        return Type.NonNull(simpleType)
    }

    private fun applyNullability(kType: KType, simpleType: Type): Type {
        if (!kType.isMarkedNullable) {
            return Type.NonNull(simpleType)
        } else {
            return simpleType
        }
    }

    private suspend fun handleRawType(kClass: KClass<*>, typeCategory: TypeCategory) : Type {
        when (val type = unions.find { it.name == kClass.simpleName }) {
            null -> Unit
            else -> return type
        }

        if(kClass == Context::class) throw SchemaException("Context type cannot be part of schema")

        val cachedInstances = when(typeCategory) {
            TypeCategory.QUERY -> queryTypeProxies
            TypeCategory.INPUT -> inputTypeProxies
        }


        return cachedInstances[kClass]
            ?: enums[kClass]
            ?: scalars[kClass]
            ?: when(typeCategory) {
                TypeCategory.QUERY -> handleObjectType(kClass)
                TypeCategory.INPUT -> handleInputType(kClass)
            }
    }

    private suspend fun handleRawType(jType: java.lang.reflect.Type, typeCategory: TypeCategory) : Type {
        // TODO: Let's not worry about Unions for now
//        when (val type = unions.find { it.name == kClass.simpleName }) {
//            null -> Unit
//            else -> return type
//        }

        val kClass = jType.rawType.kotlin

        if(kClass == Context::class) throw SchemaException("Context type cannot be part of schema")

        val cachedInstances = when(typeCategory) {
            TypeCategory.QUERY -> queryTypeProxies
            TypeCategory.INPUT -> inputTypeProxies
        }


        return cachedInstances[kClass]
            ?: enums[kClass]
            ?: scalars[kClass]
            ?: when(typeCategory) {
                TypeCategory.QUERY -> {
                    if(jType is ParameterizedType) {
                        handleGenericsType(jType)
                    } else {
                        handleObjectType(kClass)
                    }
                }
                TypeCategory.INPUT -> handleInputType(kClass)
            }
    }

    private suspend fun <T, K, R> handleDataloadOperation(
        operation: PropertyDef.DataLoadedFunction<T, K, R>
    ): Field {
        val returnType = handlePossiblyWrappedType(operation.returnType, TypeCategory.QUERY)
        val inputValues = handleInputValues(operation.name, operation.prepare, operation.inputValues)

        return Field.DataLoader(
            kql = operation,
            loader = operation.loader,
            returnType = returnType,
            arguments = inputValues
        )
    }

    private suspend fun handleObjectType(kClass: KClass<*>) : Type {
        assertValidObjectType(kClass)
        val objectDefs = definition.objects.filter { it.kClass.isSuperclassOf(kClass) }
        val objectDef = objectDefs.find { it.kClass == kClass } ?: TypeDef.Object(kClass.defaultKQLTypeName(), kClass)

        //treat introspection types as objects -> adhere to reference implementation behaviour
        val kind = if(kClass.isFinal || objectDef.name.startsWith("__")) TypeKind.OBJECT else TypeKind.INTERFACE

        val objectType = if(kind == TypeKind.OBJECT) Type.Object(objectDef) else Type.Interface(objectDef)
        val typeProxy = TypeProxy(objectType)
        queryTypeProxies[kClass] = typeProxy

        val allKotlinProperties = objectDefs.fold(emptyMap<String, PropertyDef.Kotlin<*, *>>()) { acc, def ->
            acc + def.kotlinProperties.mapKeys { (property) -> property.name }
        }
        val allTransformations= objectDefs.fold(emptyMap<String, Transformation<*, *>>()) { acc, def ->
            acc + def.transformations.mapKeys { (property) -> property.name }
        }

        val kotlinFields = kClass.memberProperties
                .filter { field -> field.visibility == KVisibility.PUBLIC }
                .filterNot { field ->  objectDefs.any { it.isIgnored(field.name) } }
                .map { property -> handleKotlinProperty (
                        kProperty = property,
                        kqlProperty = allKotlinProperties[property.name],
                        transformation = allTransformations[property.name]
                ) }

        val extensionFields = objectDefs
            .flatMap(TypeDef.Object<*>::extensionProperties)
            .map { property -> handleOperation(property) }

        val dataloadExtensionFields = objectDefs
            .flatMap(TypeDef.Object<*>::dataloadExtensionProperties)
            .map { property -> handleDataloadOperation(property) }

        val unionFields = objectDefs
            .flatMap(TypeDef.Object<*>::unionProperties)
            .map { property -> handleUnionProperty(property) }


        val typenameResolver: suspend (Any) -> String? = { value: Any ->
            schemaProxy.typeByKClass(value.javaClass.kotlin)?.name ?: typeProxy.name
        }

        val __typenameField = handleOperation (
                PropertyDef.Function<Nothing, String?> ("__typename", FunctionWrapper.on(typenameResolver, true))
        )

        val declaredFields = kotlinFields + extensionFields + unionFields + dataloadExtensionFields

        if(declaredFields.isEmpty()){
            throw SchemaException("An Object type must define one or more fields. Found none on type ${objectDef.name}")
        }

        declaredFields.find { it.name.startsWith("__") }?.let { field ->
            throw SchemaException("Illegal name '${field.name}'. Names starting with '__' are reserved for introspection system")
        }

        val allFields = declaredFields + __typenameField
        typeProxy.proxied = if(kind == TypeKind.OBJECT) Type.Object(objectDef, allFields) else Type.Interface(objectDef, allFields)
        return typeProxy
    }

    private suspend fun handleInputType(kClass: KClass<*>) : Type {
        assertValidObjectType(kClass)

        val inputObjectDef = definition.inputObjects.find { it.kClass == kClass } ?: TypeDef.Input(kClass.defaultKQLTypeName(), kClass)
        val objectType = Type.Input(inputObjectDef)
        val typeProxy = TypeProxy(objectType)
        inputTypeProxies[kClass] = typeProxy

        val fields = if (kClass.findAnnotation<NotIntrospected>() == null) {
            kClass.memberProperties.map { property -> handleKotlinInputProperty(property) }
        } else listOf()

        typeProxy.proxied = Type.Input(inputObjectDef, fields)
        return typeProxy
    }

    private suspend fun handleInputValues(operationName : String, operation: FunctionWrapper<*>, inputValues: List<InputValueDef<*>>) : List<InputValue<*>> {
        val invalidInputValues = inputValues
                .map { it.name }
                .filterNot { it in operation.argumentsDescriptor.keys }

        if(invalidInputValues.isNotEmpty()){
            throw SchemaException("Invalid input values on $operationName: $invalidInputValues")
        }

        return operation.argumentsDescriptor.map { (name, kType) ->
            val inputValue = inputValues.find { it.name == name }
            val kqlInput = inputValue ?: InputValueDef(kType.jvmErasure, name)
            val inputType = handlePossiblyWrappedType(inputValue?.kType ?: kType, TypeCategory.INPUT)
            InputValue(kqlInput, inputType)
        }
    }

    private suspend fun handleUnionType(union : TypeDef.Union) : Type.Union {
        val possibleTypes = union.members.map {
            handleRawType(it, TypeCategory.QUERY)
        }

        val invalidPossibleTypes = possibleTypes.filterNot { it.kind == TypeKind.OBJECT }
        if(invalidPossibleTypes.isNotEmpty()){
            throw SchemaException("Invalid union type members")
        }

        val __typenameField = handleOperation (
            PropertyDef.Function<Nothing, String?> ("__typename", FunctionWrapper.on( { value: Any ->
                schemaProxy.typeByKClass(value.javaClass.kotlin)?.name
            }, true))
        )

        val unionType = Type.Union(union, __typenameField, possibleTypes)
        unions.add(unionType)
        return unionType
    }

    private suspend fun handleKotlinInputProperty(kProperty: KProperty1<*, *>) : InputValue<*> {
        val type = handlePossiblyWrappedType(kProperty.returnType, TypeCategory.INPUT)
        return InputValue(InputValueDef(kProperty.returnType.jvmErasure, kProperty.name), type)
    }

    private suspend fun <T : Any, R> handleKotlinProperty (
            kProperty: KProperty1<T, R>,
            kqlProperty: PropertyDef.Kotlin<*, *>?,
            transformation: Transformation<*, *>?,
            resolvedPropertyType: java.lang.reflect.Type? = null
    ) : Field.Kotlin<*, *> {
        val returnType = if (resolvedPropertyType == null) {
            handlePossiblyWrappedType(kProperty.returnType, TypeCategory.QUERY)
        } else {
            handlePossiblyWrappedType(resolvedPropertyType, TypeCategory.QUERY)
        }

        val inputValues = if(transformation != null){
            handleInputValues("$kProperty transformation", transformation.transformation, emptyList())
        } else {
            emptyList()
        }

        val actualKqlProperty = kqlProperty ?: PropertyDef.Kotlin(kProperty)

        return Field.Kotlin (
                kql = actualKqlProperty as PropertyDef.Kotlin<T, R>,
                returnType = returnType,
                arguments = inputValues,
                transformation = transformation as Transformation<T, R>?
        )
    }
}
