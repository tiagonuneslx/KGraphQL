package com.apurebase.kgraphql.schema.structure

import java.lang.reflect.*
import java.lang.reflect.Array
import java.lang.reflect.Type
import java.util.*
import kotlin.arrayOf

/**
 * Returns true if `rawType` is built in. We don't reflect on private fields of platform
 * types because they're unspecified and likely to be different on Java vs. Android.
 */
fun isPlatformType(rawType: Class<*>): Boolean {
    val name = rawType.name
    return (name.startsWith("android.")
            || name.startsWith("androidx.")
            || name.startsWith("java.")
            || name.startsWith("javax.")
            || name.startsWith("kotlin.")
            || name.startsWith("kotlinx.")
            || name.startsWith("scala."))
}

/**
 * This annotation is present on any class file produced by the Kotlin compiler and is read by the compiler and reflection.
 * Parameters have very short JVM names on purpose: these names appear in all generated class files, and we'd like to reduce their size.
 */
val KOTLIN_METADATA = Metadata::class.java

/** Returns the raw [Class] type of this type. */
val Type.rawType: Class<*> get() = getRawType(this)

@JvmName("getRawTypeStatic")
fun getRawType(type: Type?): Class<*> {
    return when (type) {
        is Class<*> -> {
            // type is a normal class.
            type
        }
        is ParameterizedType -> {
            // I'm not exactly sure why getRawType() returns Type instead of Class. Neal isn't either but
            // suspects some pathological case related to nested classes exists.
            val rawType = type.rawType
            rawType as Class<*>
        }
        is GenericArrayType -> {
            val componentType = type.genericComponentType
            Array.newInstance(getRawType(componentType), 0).javaClass
        }
        is TypeVariable<*> -> {
            // We could use the variable's bounds, but that won't work if there are multiple. having a raw
            // type that's more general than necessary is okay.
            Any::class.java
        }
        is WildcardType -> {
            getRawType(type.upperBounds[0])
        }
        else -> {
            val className = if (type == null) "null" else type.javaClass.name
            throw IllegalArgumentException(
                "Expected a Class, ParameterizedType, or "
                        + "GenericArrayType, but <"
                        + type
                        + "> is of type "
                        + className
            )
        }
    }
}

/**
 * Returns a type that represents an unknown type that extends `bound`. For example, if
 * `bound` is `CharSequence.class`, this returns `? extends CharSequence`. If
 * `bound` is `Object.class`, this returns `?`, which is shorthand for `?
 * extends Object`.
 */
fun subtypeOf(bound: Type?): WildcardType {
    val upperBounds: kotlin.Array<Type?> = if (bound is WildcardType) {
        bound.upperBounds
    } else {
        arrayOf(bound)
    }
    return WildcardTypeImpl(
        upperBounds,
        arrayOf()
    )
}

/**
 * Returns a type that represents an unknown supertype of `bound`. For example, if `bound` is `String.class`, this returns `? super String`.
 */
fun supertypeOf(bound: Type?): WildcardType {
    val lowerBounds: kotlin.Array<Type?> = if (bound is WildcardType) {
        bound.lowerBounds
    } else {
        arrayOf(bound)
    }
    return WildcardTypeImpl(arrayOf(Any::class.java), lowerBounds)
}

fun resolve(context: Type, contextRawType: Class<*>, toResolve: Type): Type? {
    return resolve(context, contextRawType, toResolve, LinkedHashSet())
}

private fun resolve(
    context: Type,
    contextRawType: Class<*>,
    toResolve: Type?,
    visitedTypeVariables: MutableCollection<TypeVariable<*>>
): Type? {
    // This implementation is made a little more complicated in an attempt to avoid object-creation.
    var toResolve = toResolve
    while (true) {
        if (toResolve is TypeVariable<*>) {
            val typeVariable = toResolve
            if (visitedTypeVariables.contains(typeVariable)) {
                // cannot reduce due to infinite recursion
                return toResolve
            } else {
                visitedTypeVariables.add(typeVariable)
            }
            toResolve = resolveTypeVariable(context, contextRawType, typeVariable)
            if (toResolve === typeVariable) return toResolve
        } else if (toResolve is Class<*> && toResolve.isArray) {
            val original = toResolve
            val componentType: Type = original.componentType
            val newComponentType = resolve(context, contextRawType, componentType, visitedTypeVariables)
            return if (componentType === newComponentType) original else com.apurebase.kgraphql.schema.structure.arrayOf(
                newComponentType
            )
        } else if (toResolve is GenericArrayType) {
            val original = toResolve
            val componentType = original.genericComponentType
            val newComponentType = resolve(context, contextRawType, componentType, visitedTypeVariables)
            return if (componentType === newComponentType) original else com.apurebase.kgraphql.schema.structure.arrayOf(
                newComponentType
            )
        } else if (toResolve is ParameterizedType) {
            val original = toResolve
            val ownerType: Type? = original.ownerType
            val newOwnerType = resolve(context, contextRawType, ownerType, visitedTypeVariables)
            var changed = newOwnerType !== ownerType
            var args = original.actualTypeArguments
            var t = 0
            val length = args.size
            while (t < length) {
                val resolvedTypeArgument = resolve(context, contextRawType, args[t], visitedTypeVariables)
                if (resolvedTypeArgument !== args[t]) {
                    if (!changed) {
                        args = args.clone()
                        changed = true
                    }
                    args[t] = resolvedTypeArgument
                }
                t++
            }
            return if (changed) ParameterizedTypeImpl(
                newOwnerType,
                original.rawType,
                *args
            ) else original
        } else if (toResolve is WildcardType) {
            val original = toResolve
            val originalLowerBound = original.lowerBounds
            val originalUpperBound = original.upperBounds
            if (originalLowerBound.size == 1) {
                val lowerBound = resolve(context, contextRawType, originalLowerBound[0], visitedTypeVariables)
                if (lowerBound !== originalLowerBound[0]) {
                    return supertypeOf(lowerBound)
                }
            } else if (originalUpperBound.size == 1) {
                val upperBound = resolve(context, contextRawType, originalUpperBound[0], visitedTypeVariables)
                if (upperBound !== originalUpperBound[0]) {
                    return subtypeOf(upperBound)
                }
            }
            return original
        } else {
            return toResolve
        }
    }
}

fun resolveTypeVariable(context: Type, contextRawType: Class<*>, unknown: TypeVariable<*>): Type {
    val declaredByRaw: Class<*> = declaringClassOf(unknown) ?: return unknown

    // We can't reduce this further.
    val declaredBy = getGenericSupertype(context, contextRawType, declaredByRaw)
    if (declaredBy is ParameterizedType) {
        val index: Int = indexOf(declaredByRaw.typeParameters as kotlin.Array<Any>, unknown)
        return declaredBy.actualTypeArguments[index]
    }
    return unknown
}

/**
 * Returns the generic supertype for `supertype`. For example, given a class `IntegerSet`, the result for when supertype is `Set.class` is `Set<Integer>` and the
 * result when the supertype is `Collection.class` is `Collection<Integer>`.
 */
fun getGenericSupertype(context: Type?, rawType: Class<*>, toResolve: Class<*>): Type? {
    var rawType = rawType
    if (toResolve == rawType) {
        return context
    }

    // we skip searching through interfaces if unknown is an interface
    if (toResolve.isInterface) {
        val interfaces = rawType.interfaces
        var i = 0
        val length = interfaces.size
        while (i < length) {
            if (interfaces[i] == toResolve) {
                return rawType.genericInterfaces[i]
            } else if (toResolve.isAssignableFrom(interfaces[i])) {
                return getGenericSupertype(rawType.genericInterfaces[i], interfaces[i], toResolve)
            }
            i++
        }
    }

    // check our supertypes
    if (!rawType.isInterface) {
        while (rawType != Any::class.java) {
            val rawSupertype = rawType.superclass
            if (rawSupertype == toResolve) {
                return rawType.genericSuperclass
            } else if (toResolve.isAssignableFrom(rawSupertype)) {
                return getGenericSupertype(rawType.genericSuperclass, rawSupertype, toResolve)
            }
            rawType = rawSupertype
        }
    }

    // we can't resolve this further
    return toResolve
}

/**
 * Returns the declaring class of `typeVariable`, or `null` if it was not declared by
 * a class.
 */
fun declaringClassOf(typeVariable: TypeVariable<*>): Class<*>? {
    val genericDeclaration = typeVariable.genericDeclaration
    return if (genericDeclaration is Class<*>) genericDeclaration else null
}

fun indexOf(array: kotlin.Array<Any>, toFind: Any): Int {
    for (i in array.indices) {
        if (toFind == array[i]) return i
    }
    throw NoSuchElementException()
}

fun arrayOf(componentType: Type?): GenericArrayType {
    return GenericArrayTypeImpl(componentType)
}

/**
 * Returns a type that is functionally equal but not necessarily equal according to [ ][Object.equals].
 */
fun canonicalize(type: Type?): Type? {
    return if (type is Class<*>) {
        if (type.isArray) GenericArrayTypeImpl(canonicalize(type.componentType)) else type
    } else if (type is ParameterizedType) {
        if (type is ParameterizedTypeImpl) return type
        ParameterizedTypeImpl(
            type.ownerType, type.rawType, *type.actualTypeArguments
        )
    } else if (type is GenericArrayType) {
        if (type is GenericArrayTypeImpl) return type
        GenericArrayTypeImpl(type.genericComponentType)
    } else if (type is WildcardType) {
        if (type is WildcardTypeImpl) return type
        WildcardTypeImpl(type.upperBounds, type.lowerBounds)
    } else {
        type // This type is unsupported!
    }
}


/** Returns true if `a` and `b` are equal.  */
fun equals(a: Type?, b: Type?): Boolean {
    return if (a === b) {
        true // Also handles (a == null && b == null).
    } else if (a is Class<*>) {
        if (b is GenericArrayType) {
            equals(
                a.componentType, b.genericComponentType
            )
        } else a == b
        // Class already specifies equals().
    } else if (a is ParameterizedType) {
        if (b !is ParameterizedType) return false
        val aTypeArguments =
            if (a is ParameterizedTypeImpl) a.typeArguments else a.actualTypeArguments
        val bTypeArguments =
            if (b is ParameterizedTypeImpl) b.typeArguments else b.actualTypeArguments
        equals(a.ownerType, b.ownerType) && a.rawType == b.rawType && Arrays.equals(aTypeArguments, bTypeArguments)
    } else if (a is GenericArrayType) {
        if (b is Class<*>) {
            return equals(
                b.componentType, a.genericComponentType
            )
        }
        if (b !is GenericArrayType) return false
        equals(a.genericComponentType, b.genericComponentType)
    } else if (a is WildcardType) {
        if (b !is WildcardType) return false
        val wa = a
        val wb = b
        (Arrays.equals(wa.upperBounds, wb.upperBounds)
                && Arrays.equals(wa.lowerBounds, wb.lowerBounds))
    } else if (a is TypeVariable<*>) {
        if (b !is TypeVariable<*>) return false
        val va = a
        val vb = b
        va.genericDeclaration === vb.genericDeclaration && va.name == vb.name
    } else {
        // This isn't a supported type.
        false
    }
}

fun typeToString(type: Type?): String? {
    return if (type is Class<*>) type.name else type.toString()
}

fun checkNotPrimitive(type: Type?) {
    require(!(type is Class<*> && type.isPrimitive)) { "Unexpected primitive $type. Use the boxed type." }
}

fun hashCodeOrZero(o: Any?): Int {
    return o?.hashCode() ?: 0
}

class GenericArrayTypeImpl(componentType: Type?) : GenericArrayType {
    private val componentType: Type?

    init {
        this.componentType = canonicalize(componentType)
    }

    override fun getGenericComponentType(): Type? {
        return componentType
    }

    override fun equals(o: Any?): Boolean {
        return o is GenericArrayType && equals(this, o as GenericArrayType?)
    }

    override fun hashCode(): Int {
        return componentType.hashCode()
    }

    override fun toString(): String {
        return typeToString(componentType) + "[]"
    }
}

class ParameterizedTypeImpl(ownerType: Type?, rawType: Type, vararg typeArguments: Type) :
    ParameterizedType {
    private val ownerType: Type?
    private val rawType: Type?
    val typeArguments: kotlin.Array<Type?>

    init {
        // Require an owner type if the raw type needs it.
        if (rawType is Class<*>) {
            val enclosingClass = rawType.enclosingClass
            if (ownerType != null) {
                require(!(enclosingClass == null || getRawType(ownerType) != enclosingClass)) { "unexpected owner type for $rawType: $ownerType" }
            } else require(enclosingClass == null) { "unexpected owner type for $rawType: null" }
        }
        this.ownerType = if (ownerType == null) null else canonicalize(ownerType)
        this.rawType = canonicalize(rawType)
        this.typeArguments = typeArguments.clone() as kotlin.Array<Type?>
        for (t in this.typeArguments.indices) {
            checkNotPrimitive(this.typeArguments[t])
            this.typeArguments[t] = canonicalize(this.typeArguments[t])
        }
    }

    override fun getActualTypeArguments(): kotlin.Array<Type> {
        return typeArguments.clone() as kotlin.Array<Type>
    }

    override fun getRawType(): Type? {
        return rawType
    }

    override fun getOwnerType(): Type? {
        return ownerType
    }

    override fun equals(other: Any?): Boolean {
        return other is ParameterizedType && equals(this, other as ParameterizedType?)
    }

    override fun hashCode(): Int {
        return Arrays.hashCode(typeArguments) xor rawType.hashCode() xor hashCodeOrZero(
            ownerType
        )
    }

    override fun toString(): String {
        val result = StringBuilder(30 * (typeArguments.size + 1))
        result.append(typeToString(rawType))
        if (typeArguments.size == 0) {
            return result.toString()
        }
        result.append("<").append(typeToString(typeArguments[0]))
        for (i in 1 until typeArguments.size) {
            result.append(", ").append(typeToString(typeArguments[i]))
        }
        return result.append(">").toString()
    }
}

/**
 * The WildcardType interface supports multiple upper bounds and multiple lower bounds. We only
 * support what the Java 6 language needs - at most one bound. If a lower bound is set, the upper
 * bound must be Object.class.
 */
class WildcardTypeImpl(upperBounds: kotlin.Array<Type?>, lowerBounds: kotlin.Array<Type?>) :
    WildcardType {
    private var upperBound: Type? = null
    private var lowerBound: Type? = null

    init {
        require(lowerBounds.size <= 1)
        require(upperBounds.size == 1)
        if (lowerBounds.size == 1) {
            if (lowerBounds[0] == null) throw NullPointerException()
            checkNotPrimitive(lowerBounds[0]!!)
            require(!(upperBounds[0] !== Any::class.java))
            lowerBound = canonicalize(lowerBounds[0]!!)
            upperBound = Any::class.java
        } else {
            if (upperBounds[0] == null) throw NullPointerException()
            checkNotPrimitive(upperBounds[0]!!)
            lowerBound = null
            upperBound = canonicalize(upperBounds[0]!!)
        }
    }

    override fun getUpperBounds(): kotlin.Array<Type> {
        return arrayOf(upperBound!!)
    }

    override fun getLowerBounds(): kotlin.Array<Type> {
        return lowerBound?.let { arrayOf(it) } ?: arrayOf()
    }

    override fun equals(other: Any?): Boolean {
        return other is WildcardType && equals(this, other as WildcardType?)
    }

    override fun hashCode(): Int {
        // This equals Arrays.hashCode(getLowerBounds()) ^ Arrays.hashCode(getUpperBounds()).
        return (if (lowerBound != null) 31 + lowerBound.hashCode() else 1) xor 31 + upperBound.hashCode()
    }

    override fun toString(): String {
        return if (lowerBound != null) {
            "? super " + typeToString(lowerBound!!)
        } else if (upperBound === Any::class.java) {
            "?"
        } else {
            "? extends " + typeToString(upperBound!!)
        }
    }
}