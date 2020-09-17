import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
annotation class InjectClass

/**
 * Create di container instance for given scope.
 * Scope is annotation used on classes with @InjectClass annotation.
 * Container manages instances of classes from bound scope.
 */
inline fun <reified Scope : Annotation> di(noinline bindings: Bindings.() -> Unit = {}) =
    DiContainer(Scope::class, bindings)

/**
 * Dsl for creating instance bibdings.
 */
class Bindings(
    private val depsProviders: DepsProviders,
    private val bootStrap: Bootstrap
) {

    /**
     * Set instance provider.
     */
    inline fun <reified T : Any> provide(noinline provider: Provider.() -> T) {
        provide(T::class, provider)
    }

    fun provide(clazz: KClass<*>, provider: Provider.() -> Any) {
        depsProviders.setProvider(clazz, provider)
    }

    /**
     * Bind implementation to interface.
     */
    inline fun <reified T : Any, reified Impl : T> bind() {
        provide<T> { get<Impl>() }
    }

    /**
     * Set classes to instantiate immediately after di creation.
     */
    fun boot(vararg classes: KClass<*>) {
        bootStrap.bootClasses = classes
    }
}

/**
 * Dsl for access container from provider function.
 */
class Provider(
    private val container: DiContainer
) {
    /**
     * Get instance from container.
     */
    inline fun <reified T : Any> get(): T {
        return get(T::class)
    }

    fun <T : Any> get(clazz: KClass<T>): T {
        return container.get(clazz)!!
    }

    /**
     * Get lazy instance from container.
     */
    inline fun <reified T : Any> getLazy(): Lazy<T> {
        return getLazy(T::class)
    }

    fun <T : Any> getLazy(clazz: KClass<T>): Lazy<T> {
        return container.getLazy(clazz)
    }

    /**
     * Get optional instance from container.
     */
    inline fun <reified T : Any> getOptional(): T? {
        return getOptional(T::class)
    }

    fun <T : Any> getOptional(clazz: KClass<T>): T? {
        return container.get(clazz, optional = true)
    }
}
