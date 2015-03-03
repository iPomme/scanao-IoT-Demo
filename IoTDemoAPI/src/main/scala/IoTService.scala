package io.nao.iot.api

trait IoTService {
  def start()

  def stop()

  def reset()

  def state()

  // Test and debug methods
  def sendToT24(msg : String) : String
}