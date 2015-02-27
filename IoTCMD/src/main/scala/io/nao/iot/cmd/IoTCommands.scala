package io.nao.iot.cmd



import org.apache.felix.gogo.commands.{Argument, Command}
import org.apache.karaf.shell.console.OsgiCommandSupport
import io.nao.iot.api.IoTService

/**
 * Created by nicolasjorand on 04/02/15.
 */
@Command(scope = "nao", name = "iot", description = "Execute the demonstration of the Internet of Things demo")
class IoTCommands extends OsgiCommandSupport {

  @Argument(index = 0, name = "action", description = "The action to perform on the service. Could be start | reset | state | stop", required = true, multiValued = false)
  var key: String = null

  protected def doExecute: String = {
    val srvName = classOf[IoTService].getName()
    val ref = Option(getBundleContext().getServiceReference(srvName))

    val demo = ref match {
      case None =>
        println(s"Cannot get the reference to the service '$srvName'")
        None
      case Some(srvRef) =>
        Option(getService(classOf[IoTService], srvRef))
    }
    (demo, key) match {
      case (Some(s), "start") => s.start()
      case (Some(s), "reset") => s.reset()
      case (Some(s), "state") => s.state()
      case (Some(s), "stop") => s.stop()
      case (None, _) => println(s"Command '$key' not executed !")
      case (_, _) => println(s"'$key' is an unknown command")
    }
    null
  }

}
