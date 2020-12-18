package com.avsystem.commons
package di

import com.avsystem.commons.macros.di.ComponentMacros
import com.avsystem.commons.misc.SourceInfo

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.compileTimeOnly

case class ComponentInitializationException(component: Component[_], cause: Throwable)
  extends Exception(s"failed to initialize component ${component.info}", cause)

case class DependencyCycleException(cyclePath: List[Component[_]])
  extends Exception(s"component dependency cycle detected:\n${cyclePath.iterator.map(_.info).map("  " + _).mkString(" ->\n")}")

/**
  * Represents a lazily initialized component in a dependency injection setting. The name "component" indicates
  * that the value is often an application building block like a database service, data access object, HTTP server etc.
  * which is associated with some side-effectful initialization code.
  * However, [[Component]] can hold values of any type.
  *
  * You can think of [[Component]] as a high-level `lazy val` with more features:
  * parallel initialization of dependencies, dependency cycle detection, source code awareness.
  */
abstract class Component[+T] {

  import Component._

  def sourceInfo: SourceInfo
  def info: String = s"${sourceInfo.enclosingSymbols.head}(${sourceInfo.fileName}:${sourceInfo.line})"

  private[this] val savedFuture = new AtomicReference[Future[T]]

  /**
    * Phantom method that indicates an asynchronous reference to this component inside definition of some other component.
    * This method is rewritten in compile time by [[Components.component]] or [[Components.singleton]] macro.
    * The component being referred is extracted as a dependency and initialized before the component that refers to
    * it. This way multiple dependencies can be initialized in parallel.
    *
    * @example
    * {{{
    *   class FooService
    *   class BarService
    *   class Application(foo: FooService, bar: BarService)
    *
    *   object MyComponents extends Components {
    *     def foo: Component[FooService] = singleton(new FooService)
    *     def bar: Component[BarService] = singleton(new BarService)
    *
    *     // before `app` is initialized, `foo` and `bar` can be initialized in parallel
    *     def app: Component[Application] = singleton(new Application(foo.ref, bar.ref))
    *   }
    * }}}
    */
  @compileTimeOnly(".ref can only be used inside code passed to component/singleton(...) macro")
  def ref: T = sys.error("stub")

  def getIfReady: Opt[T] =
    savedFuture.get.opt.flatMap(_.value.map(_.get).toOpt)

  /**
    * Forces initialization of this component and its dependencies (in parallel, using given `ExecutionContext`).
    * Returns a `Future` containing the initialized component value.
    * NOTE: the component is initialized only once and its value is cached.
    */
  final def init(implicit ec: ExecutionContext): Future[T] =
    doInit(Nil).catchFailures

  private def doInit(stack: List[Component[_]])(implicit ec: ExecutionContext): Future[T] = {
    if (stack.contains(this)) {
      val cyclePath = this :: (this :: stack.takeWhile(_ != this)).reverse
      throw DependencyCycleException(cyclePath)
    }
    val promise = Promise[T]()
    if (savedFuture.compareAndSet(null, promise.future)) {
      val newStack = this :: stack
      val resultFuture =
        Future.traverse(dependencies)(_.doInit(newStack))
          .flatMapNow(deps => create(deps) match {
            case CreateResult.Ready(value) =>
              Future.successful(value)
            case CreateResult.More(nextComponent) =>
              nextComponent.doInit(newStack)
          })
          .recoverNow {
            case NonFatal(cause) =>
              throw ComponentInitializationException(this, cause)
          }
      promise.completeWith(resultFuture)
    }
    savedFuture.get()
  }

  protected def dependencies: IndexedSeq[Component[_]]
  protected def create(resolvedDeps: IndexedSeq[Any]): CreateResult[T]
}
object Component {
  sealed abstract class CreateResult[+T]
  object CreateResult {
    final case class Ready[+T](value: T) extends CreateResult[T]
    final case class More[+T](nextStep: Component[T]) extends CreateResult[T]
  }
}

final class ComponentImpl[T](
  val sourceInfo: SourceInfo,
  deps: => IndexedSeq[Component[_]],
  createFun: IndexedSeq[Any] => Component.CreateResult[T]
) extends Component[T] {
  protected lazy val dependencies: IndexedSeq[Component[_]] = deps
  protected def create(resolvedDeps: IndexedSeq[Any]): Component.CreateResult[T] = createFun(resolvedDeps)
}

trait Components extends ComponentsLowPrio {
  /**
    * Creates a [[Component]] based on a definition (i.e. a constructor invocation). The definition may refer to
    * other components as dependencies using `.ref`. This macro will transform the definition by extracting dependencies
    * in a way that allows them to be initialized in parallel, before initializing the current component itself.
    */
  protected[this] def component[T](definition: => T, dependsOn: Component[_]*)(implicit sourceInfo: SourceInfo): Component[T] = macro ComponentMacros.prototype[T]

  /**
    * This is the same as [[component]] except that the created [[Component]] is cached inside an outer instance that
    * implements [[Components]]. This way you can implement your components using `def`s rather than `val`s
    * (`val`s can be problematic in traits) but caching will make sure that your `def` always returns the same,
    * cached [[Component]] instance. The cache key is based on source position so overriding a method that returns
    * `singleton` will create separate [[Component]] with different cache key.
    */
  protected[this] def singleton[T](definition: => T, dependsOn: Component[_]*)(implicit sourceInfo: SourceInfo): Component[T] = macro ComponentMacros.singleton[T]

  private[this] val singletons = new ConcurrentHashMap[SourceInfo, Component[_]]

  protected[this] def cached[T](component: => Component[T])(implicit sourceInfo: SourceInfo): Component[T] =
    singletons.computeIfAbsent(sourceInfo, _ => component).asInstanceOf[Component[T]]

  // avoids divergent implicit expansion involving `inject`
  // this is not strictly necessary but makes compiler error messages nicer
  // i.e. the compiler will emit "could not find implicit value" instead of "divergent implicit expansion"
  implicit def ambiguousArbitraryComponent1[T]: Component[T] = null
  implicit def ambiguousArbitraryComponent2[T]: Component[T] = null
}
trait ComponentsLowPrio {
  @compileTimeOnly("implicit Component[T] => implicit T inference only works inside code passed to component/singleton macro")
  implicit def inject[T](implicit component: Component[T]): T = sys.error("stub")
}
