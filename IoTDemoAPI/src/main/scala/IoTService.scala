package io.nao.iot.api

trait IoTService {
  def start()

  def stop()

  def reset()

  def state()
}