package io.nao.iot

import akka.actor.ActorSystem
import io.nao.iot.api.IoTService

/**
 * Created by nicolasjorand on 27/02/15.
 */
class IoTServiceImpl(system: ActorSystem) extends IoTService {
  override def start(): Unit = {
    println(s"start called")
  }

  override def stop(): Unit = {
    println(s"stop called")

  }

  override def state(): Unit = {
    println(s"state called")
  }

  override def reset(): Unit = {
    println(s"reset called")
  }
}
