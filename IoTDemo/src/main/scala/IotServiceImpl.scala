package io.nao.iot

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import io.nao.iot.api.IoTService
import io.nao.scanao.msg.{tech, txt}

import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * Created by nicolasjorand on 27/02/15.
 */
class IoTServiceImpl(system: ActorSystem) extends IoTService {

  implicit val timeout = Timeout(5 seconds)

  // create the actors for the demo
  val mediator = system.actorOf(Props[FSMIoTMediator], "mediator")

  override def start(): Unit = {
    /**
     * Thanks to the initialization, all the messages would be stached if the robot is not ready
     */

    /**
     * Inform that the demo is ready to start
     */
    mediator ! txt.Say(s"C'est partit pour la démo, pourvu que ca marche ! Je te dis des que je suis pret ...")

    /**
     * Subscribe to event and use a state Machine to handle it
     */
    mediator ! tech.SubscribeEvent("FaceDetected", "SNEvents", "event", mediator) // Subscribe to an event

    println("Starting...")
  }

  override def stop(): Unit = {
    println(s"stop called")


  }

  override def state(): Unit = {
    val state = Await.result(mediator.ask(InfoState), timeout.duration).asInstanceOf[String]
    println(state)
  }

  override def reset(): Unit = {
    mediator ! ResetState
    println("reset done")
  }

  // Test and debug methods
  override def sendToT24(msg: String): String = FSMIoTMediator.sendPost(msg)
}
