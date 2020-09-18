import java.lang.IllegalStateException
import java.lang.RuntimeException
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor


/**
 * Di container.
 * Used to retrieve instances and create subDi containers.
 * Mark classes with @InjectClass annotation and your own scope annotation to
 * automatically inject it to container with given scope.
 * Constructor params of inject classes may be usual classes (not generics),
 * Kotlin Lazy for lazy instances and Kotlin nullable types for optional injection.
 */
class DiContainer(
    private val scope: KClass<*>,
    bindings: Bindings.() -> Unit = {},
    private val superDi: DiContainer? = null
) {
    private val instances = hashMapOf<KClass<*>, Any?>()
    private val depsProviders = DepsProviders(this)
    private val callStackClasses = HashSet<KClass<*>>()

    init {
        val bootStrap = Bootstrap(this)
        Bindings(depsProviders, bootStrap).bindings()
        bootStrap.boot()
    }

    /**
     * Get instance of given class.
     */
    inline fun <reified T : Any> get(): T {
        return get(T::class)!!
    }

    /**
     * Get lazy instance of given class.
     */
    inline fun <reified T : Any> getLazy(): Lazy<T> {
        return getLazy(T::class)!!
    }

    /**
     * Get optional instance of given class.
     */
    inline fun <reified T : Any> getOptional(): T? {
        return get(T::class, optional = true)
    }

    /**
     * Get optional lazy instance of given class.
     */
    inline fun <reified T : Any> getOptionalLazy(): Lazy<T>? {
        return getLazy(T::class, optional = true)
    }

    fun <T : Any> getLazy(clazz: KClass<T>, optional: Boolean = false): Lazy<T>? {
        if (!canProvide(clazz)) {
            instances[clazz] = null
            throwNotFoundExceptionIfNeeded(clazz, null, optional)
            return null
        }

        return lazy { get(clazz)!! }
    }

    fun <T : Any> get(clazz: KClass<T>, optional: Boolean = false): T? {
        if (clazz == Lazy::class) {
            throw RuntimeException("Using get() for lazy type. Please use getLazy() instead")
        }

        if (instances.containsKey(clazz)) {
            val instance = instances[clazz]
            throwNotFoundExceptionIfNeeded(clazz, instance, optional)
            return instance as T
        }

        verifyNoCircularDependencies(clazz) {
            val newInstance = createInstance(clazz) ?: superDi?.get(clazz)
            instances[clazz] = newInstance
            throwNotFoundExceptionIfNeeded(clazz, newInstance, optional)
            return newInstance
        }
        throw IllegalStateException("Cannot create instance due to internal error")
    }

    private fun throwNotFoundExceptionIfNeeded(clazz: KClass<*>, instance: Any?, optional: Boolean) {
        if (instance == null && !optional) {
            throw RuntimeException(
                "No @InjectClass classes in scope ${scope.qualifiedName} " +
                        "and providers for class ${clazz.qualifiedName}. " +
                        "If you want to use optional injection, specify constructor param as nullable, " +
                        "or use getOptional()"
            )
        }
    }

    private inline fun verifyNoCircularDependencies(clazz: KClass<*>, block: () -> Unit) {
        if (callStackClasses.contains(clazz)) {
            throw RuntimeException("Circular dependency in class ${clazz.qualifiedName}")
        }
        callStackClasses.add(clazz)
        try {
            block()
        } finally {
            callStackClasses.clear()
        }
    }

    private fun <T : Any> createInstance(clazz: KClass<T>): T? {
        if (clazz == DiContainer::class) {
            return this as T
        }

        if (depsProviders.hasProvider(clazz)) {
            return depsProviders.instantiate(clazz) as T
        }

        if (annotatedForInject(clazz)) {
            val primaryConstructor = clazz.primaryConstructor
                ?: throw RuntimeException("No primary constructor for class ${clazz.qualifiedName}")

            val constructorArgumentsInstances = primaryConstructor.parameters.map {
                val type = it.type
                val argClass = type.classifier as KClass<*>
                val isOptional = type.isMarkedNullable

                if (argClass == Lazy::class) {
                    val lazyArgClass = type.arguments[0].type!!.classifier as KClass<*>
                    getLazy(lazyArgClass, optional = isOptional)
                } else {
                    get(argClass, optional = isOptional)
                }
            }
            return primaryConstructor.call(*constructorArgumentsInstances.toTypedArray())
        }

        return null
    }

    private fun annotatedForInject(clazz: KClass<*>): Boolean {
        return clazz.hasAnnotation(InjectClass::class) && clazz.hasAnnotation(scope)
    }

    private fun canProvide(clazz: KClass<*>): Boolean {
        return instances[clazz] != null || depsProviders.hasProvider(clazz)
                || annotatedForInject(clazz) && clazz.primaryConstructor != null || superDi?.canProvide(clazz) == true
    }

    /**
     * Create subDi container that has access to all dependencies from parent di container.
     * Scope of subDi must be different.
     */
    inline fun <reified SubScope : Annotation> subDi(noinline bindings: Bindings.() -> Unit = {}): DiContainer {
        return subDi(SubScope::class, bindings)
    }

    fun subDi(subScope: KClass<*>, bindings: Bindings.() -> Unit = {}): DiContainer {
        if (this.scope == subScope) {
            throw RuntimeException(
                "Cannot create subDi with same scope, " +
                        "please, use different scope annotation"
            )
        }
        return DiContainer(subScope, bindings, this)
    }
}

private fun KClass<*>.hasAnnotation(annotationClass: KClass<*>): Boolean {
    return annotations.any { it.annotationClass == annotationClass }
}

/**
 * Holds registered providers for classes.
 */
class DepsProviders(
    container: DiContainer
) {
    private val provider = Provider(container)
    private val registeredProviders = hashMapOf<KClass<*>, Provider.() -> Any>()

    fun setProvider(clazz: KClass<*>, provider: Provider.() -> Any) {
        if (clazz == Lazy::class) {
            throw RuntimeException(
                "Cannot set provider of lazy type. " +
                        "Please, set usual provider instead and use getLazy() " +
                        "or Lazy<> parameter in constructor"
            )
        }

        registeredProviders[clazz] = provider
    }

    fun hasProvider(clazz: KClass<*>): Boolean {
        return registeredProviders.containsKey(clazz)
    }

    fun instantiate(clazz: KClass<*>): Any {
        if (!hasProvider(clazz)) {
            throw RuntimeException("No provider for class ${clazz.qualifiedName}")
        }

        return registeredProviders[clazz]!!.invoke(provider)
    }
}

/**
 * Used to instantiate some classes immediately after container creation.
 */
class Bootstrap(private val container: DiContainer) {
    var bootClasses: Array<out KClass<*>>? = null

    fun boot() {
        bootClasses?.forEach { container.get(it) }
    }
}
