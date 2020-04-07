/*
 * Copyright (C) Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.lagom.scaladsl.server

import akka.stream.Materializer
import com.lightbend.lagom.internal.scaladsl.server.ScaladslServerMacroImpl
import com.lightbend.lagom.internal.scaladsl.server.ScaladslServiceRouter
import com.lightbend.lagom.scaladsl.api.Descriptor
import com.lightbend.lagom.scaladsl.api.Service
import com.lightbend.lagom.scaladsl.api.ServiceInfo
import com.lightbend.lagom.scaladsl.client.ServiceResolver
import com.lightbend.lagom.scaladsl.server.status.MetricsServiceComponents
import play.api.http.HttpConfiguration
import play.api.mvc.PlayBodyParsers
import play.api.routing.Router
import play.api.routing.Router.Routes

import scala.concurrent.ExecutionContext
import scala.language.experimental.macros
import scala.reflect.ClassTag

/**
 * A Lagom server
 */
sealed trait LagomServer {
  val name: String
  val serviceBinding: LagomServiceBinding[_]
  def router: LagomServiceRouter

  /**
   * Allows the configuration of additional Play [[Router]]s.
   *
   * Typically, this will be a [[Router]] generated from a Play [[Router]]
   * or a akka-grpc generated Play [[Router]].
   *
   * Once you declare a [[Router]], you may need to define it's prefix to indicate on which path it should be available.
   *
   * {{{
   *   new LagomApplication(context) with AhcWSComponents with LocalServiceLocator {
   *     override def lagomServer: LagomServer =
   *       serverFor[MyService](new MyServiceImpl)
   *         .additionalRouter(new HelloWorldRouter().withPrefix("/hello"))
   *   }
   * }}}
   *
   * You don't need to configure a prefix if the [[Router]] has it pre-configured.
   * A akka-grpc generated Play [[Router]], for instance, has its prefix already defined by the gRPC descriptor
   * and doesn't need to have its prefix reconfigured.
   *
   * @param otherRouter the additional router
   * @return
   */
  final def additionalRouter(otherRouter: Router): LagomServer = {
    val self = this
    new LagomServer {
      override val name: String                           = self.name
      override val serviceBinding: LagomServiceBinding[_] = self.serviceBinding
      override def router: LagomServiceRouter             = self.router.additionalRouter(otherRouter)
    }
  }
}

/**
 * A Lagom service router.
 *
 * This trait doesn't add anything, except that it makes the router created by the LagomServer
 * strongly typed. This allows it to be dependency injected by type, making it simple to use it
 * in combination with the Play routes file.
 *
 * For example, if using a custom router, the Lagom router could be routed to from the routes file
 * like this:
 *
 * ```
 * ->   /     com.lightbend.lagom.scaladsl.server.LagomServiceRouter
 * ```
 */
trait LagomServiceRouter extends Router {
  final def additionalRouter(router: Router): LagomServiceRouter = {
    val self = this
    new LagomServiceRouter {
      override val documentation: Seq[(String, String, String)] =
        self.documentation ++ router.documentation

      override def withPrefix(prefix: String): Router =
        self.withPrefix(prefix).orElse(router.withPrefix(prefix))

      override val routes: Routes =
        self.routes.orElse(router.routes)
    }
  }
}

object LagomServer {

  /**
   * Internal API used by AST generated by the serverFor macro.
   */
  def forService(binding: LagomServiceBinding[_]): LagomServer = {
    new LagomServer {
      override val serviceBinding: LagomServiceBinding[_] = binding
      override val name: String                           = binding.descriptor.name
      override lazy val router: LagomServiceRouter        = binding.router
    }
  }
}

trait LagomServerComponents extends MetricsServiceComponents {
  def httpConfiguration: HttpConfiguration
  def playBodyParsers: PlayBodyParsers
  def materializer: Materializer
  def executionContext: ExecutionContext
  def serviceResolver: ServiceResolver

  lazy val lagomServerBuilder: LagomServerBuilder =
    new LagomServerBuilder(httpConfiguration, playBodyParsers, serviceResolver)(materializer, executionContext)

  @deprecated("Use LagomServerComponents#serverFor instead", "1.5.0")
  protected def bindService[T <: Service]: LagomServiceBinder[T] = macro ScaladslServerMacroImpl.createBinder[T]

  /**
   * Bind a server for the given service and factory for the service.
   *
   * Note, the type parameter for this method should always be passed explicitly, as the macro needs it to know what
   * the trait for the service descriptor should be.
   */
  protected def serverFor[T <: Service](serviceFactory: => T): LagomServer = macro ScaladslServerMacroImpl.simpleBind[T]

  lazy val serviceInfo: ServiceInfo = {
    val locatableService = lagomServer.serviceBinding.descriptor
    val resolved         = serviceResolver.resolve(locatableService)
    ServiceInfo(lagomServer.name, resolved.acls)
  }
  lazy val router: Router = lagomServer.router

  def lagomServer: LagomServer
}

final class LagomServerBuilder(
    httpConfiguration: HttpConfiguration,
    parsers: PlayBodyParsers,
    serviceResolver: ServiceResolver
)(implicit materializer: Materializer, executionContext: ExecutionContext) {
  def buildRouter(service: Service): LagomServiceRouter = {
    new ScaladslServiceRouter(serviceResolver.resolve(service.descriptor), service, httpConfiguration, parsers)(
      executionContext,
      materializer
    )
  }
}

/**
 * Internal API used by AST generated by the serverFor macro.
 */
sealed trait LagomServiceBinder[T <: Service] {
  def to(serviceFactory: => T): LagomServiceBinding[T]
}

/**
 * Internal API used by AST generated by the serverFor macro.
 */
object LagomServiceBinder {
  def apply[T <: Service: ClassTag](
      lagomServerBuilder: LagomServerBuilder,
      descriptor: Descriptor
  ): LagomServiceBinder[T] = {
    val _descriptor = descriptor
    new LagomServiceBinder[T] {
      override def to(serviceFactory: => T): LagomServiceBinding[T] = {
        new LagomServiceBinding[T] {
          override lazy val router: LagomServiceRouter = lagomServerBuilder.buildRouter(service)
          override lazy val service: T                 = serviceFactory
          override val descriptor: Descriptor          = _descriptor
        }
      }
    }
  }
}

/**
 * Internal API used by AST generated by the serverFor macro.
 */
sealed trait LagomServiceBinding[T <: Service] {
  val descriptor: Descriptor
  def service: T
  def router: LagomServiceRouter
}
