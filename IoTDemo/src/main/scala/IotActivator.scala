package io.nao.iot.osgi


import java.io.File

import _root_.io.nao.iot.IoTServiceImpl
import io.nao.iot.api.IoTService
import akka.actor.ActorSystem
import akka.osgi.ActorSystemActivator
import com.typesafe.config.ConfigFactory
import org.osgi.framework.BundleContext
import scala.collection.JavaConverters._

import scala.collection.mutable

class IoTActivator extends ActorSystemActivator {
  override def configure(context: BundleContext, system: ActorSystem): Unit = {
    // Register the system Actor as a service
    registerService(context, system)
    val props: mutable.Map[String, AnyRef] = scala.collection.mutable.Map[String, AnyRef]("name" -> "iot")
    context.registerService(classOf[IoTService], new IoTServiceImpl(system), props.asJavaDictionary)
  }

  override def getActorSystemName(bundle: BundleContext) = "iot"

  override def getActorSystemConfiguration(context: BundleContext) = ConfigFactory.parseFile(new File("etc/iot.conf"))
}