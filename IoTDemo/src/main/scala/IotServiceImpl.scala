package io.nao.iot

import akka.actor.{Props, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import io.nao.iot.api.IoTService
import io.nao.iot.InfoState
import scala.concurrent.duration._

import scala.concurrent.Await

/**
 * Created by nicolasjorand on 27/02/15.
 */
class IoTServiceImpl(system: ActorSystem) extends IoTService {

  // create the actors for the demo
  val mediator = system.actorOf(Props[FSMIoTMediator], "mediator")

  implicit val timeout = Timeout(5 seconds)
  override def start(): Unit = {
    println(s"start called")
  }

  override def stop(): Unit = {
    println(s"stop called")

  }

  override def state(): Unit = {
    val state = Await.result(mediator.ask(InfoState), timeout.duration).asInstanceOf[String]
    println(state)
  }

  override def reset(): Unit = {
    println(s"reset called")
  }
}
