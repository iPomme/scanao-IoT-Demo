/*
 * -----------------------------------------------------------------------------
 *  - ScaNao is an open-source enabling Nao's control from Scala code.            -
 *  - At the low level jNaoqi is used to bridge the C++ code with the JVM.        -
 *  -                                                                             -
 *  -  CreatedBy: Nicolas Jorand                                                  -
 *  -       Date: 27 Feb 2015                                                      -
 *  -                                                                            	-
 *  -       _______.  ______      ___      .__   __.      ___       ______       	-
 *  -      /       | /      |    /   \     |  \ |  |     /   \     /  __  \      	-
 *  -     |   (----`|  ,----'   /  ^  \    |   \|  |    /  ^  \   |  |  |  |     	-
 *  -      \   \    |  |       /  /_\  \   |  . `  |   /  /_\  \  |  |  |  |     	-
 *  -  .----)   |   |  `----. /  _____  \  |  |\   |  /  _____  \ |  `--'  |     	-
 *  -  |_______/     \______|/__/     \__\ |__| \__| /__/     \__\ \______/      	-
 *  -----------------------------------------------------------------------------
 */
package io.nao.iot

import java.io.BufferedReader

import akka.actor._
import io.nao.iot.FSMIoTMediator._
import io.nao.scanao.msg.tech.NaoEvent
import io.nao.scanao.msg.{tech, txt}
import org.apache.commons.httpclient.HttpClient
import org.apache.commons.httpclient.methods.PostMethod

import scala.collection.immutable.HashMap

// Message for this demonstration
sealed trait DemoMsg

// Technical messages
object InfoState extends DemoMsg

object ResetState extends DemoMsg

// Heart Beat messages
object SlowHeartBeat extends DemoMsg

object NormalHeartBeat extends DemoMsg

object FastHeartBeat extends DemoMsg

// Authentication messages
case class Authenticate(name: String) extends DemoMsg

// T24 messages
object GetBalance extends DemoMsg


// Main states of the demonstration
sealed trait InitState

object Initializing extends InitState

object Initialized extends InitState

object Started extends InitState

object Authenticated extends InitState

case class References(queue: scala.collection.immutable.HashMap[String, Option[ActorRef]])

/**
 * Actor used to do mediation between Nao and the client.
 * The initialization is based on a state machine,
 * this is due to the fact that the server needs to initialize a JNI connection with the robot and this initialization is taking time.
 * Notice that all the messages received before initialisation would be stach and replay once the robot is ready.
 */
class FSMIoTMediator extends Actor with FSM[InitState, References] with Stash with ActorLogging {
  //with ActorTracing {

  def listActorRef = {
    // Get the reference to the Nao actors
    HashMap.empty[String, Option[ActorRef]] + ((naoEvt, None)) + ((naoCmd, None)) + ((naoText, None)) + ((naoMemory, None)) + ((naoBehavior, None))
  }

  def identifyActors(id: String): Unit = {
    log.info(s"Send the identify message to $id")
    context.actorSelection(id) ! Identify(id)
  }

  // Send the Identity to all the references needed
  listActorRef.foreach { case (id, ref) => identifyActors(id)}

  // Set the initial state with the list of refs needed, note that at this point all the ActorRef should be set to None
  startWith(Initializing, References(listActorRef))

  when(Initializing) {
    case Event(ActorIdentity(id, ref@Some(_)), a@References(q)) =>
      log.info(s"Got the reference to $id !!")
      log.debug(s"The current missing remote reference is ${q.filter(_._2 == None)}")
      val uptQueue = q + ((id.toString, ref))
      if (uptQueue.values.exists(_ == None))
      // Some remote references are missing, stay in this state till everything initialized
        stay using a.copy(uptQueue)
      else
      // All the remote references has been resolved, move to the initialized state.
        goto(Initialized) using a.copy(uptQueue)
    case Event(ActorIdentity(id, None), a) =>
      log.error(s"Impossible to get the reference to $id")
      stay()

    case Event(InfoState, _) =>
      sender() ! "Initializing ..."
      stay()
    case Event(m@_, References(h)) =>
      stash()
      log.info(s"Message $m stached as still initializing")
      stay()
    //TODO: Manage to watch all the remote references.
  }

  when(Initialized) {
    case Event(m: txt.Say, References(h)) =>
      sendSay(m, h)
      stay()
    case Event(m: tech.SubscribeEvent, References(h)) =>
      log.info(s"Got the message $m to send to ${h(naoText)}")
      h(naoEvt).map(_ ! m)
      stay()
    case Event(m@tech.EventSubscribed(name, module, method), References(h)) =>
      log.info(s"Subscribed to $m")
      h(naoText).map(_ ! txt.Say("Je suis pret"))
      goto(Started)
    case Event(InfoState, _) =>
      sender() ! "Initialized, waiting to start ..."
      stay()

  }

  when(Started) {
    case Event(m: txt.Say, References(h)) =>
      sendSay(m, h)
      stay()
    case Event(NaoEvent(eventName, values, message), References(h)) =>
      log.info(s"received NaoEvent name: $eventName values: $values message: $message")
      // TODO: act according in case of nao event received.
      if (eventName == "FaceDetected") {
        goto(Authenticated)
      }
      stay()
    case Event(InfoState, _) =>
      sender() ! "Started"
      stay()
    case Event(m@_, References(h)) =>
      log.info(s"UNKNOWN MESSAGE: $m")
      stay()
  }

  when(Authenticated) {
    case Event(m: txt.Say, References(h)) =>
      sendSay(m, h)
      stay()
    case Event(ResetState, _) =>
      goto(Started)
    case Event(NaoEvent(eventName, values, message), References(h)) =>
      log.info(s"received NaoEvent name: $eventName values: $values message: $message")

      stay()
    case Event(GetBalance, References(h)) =>
      h(naoText).map(_ ! txt.Say(s"Bon, je vais demander ce qu'il te reste sur ton compte."))
      val record = sendPost("ENQUIRY.SELECT,,INPUTT/123456,ACCOUNT-LIST,@ID:EQ:=2000000062")
      // Split the answer on the double quote take the last occurence and remove quotes and comma
      val balance = getBalance(record)
      h(naoText).map(_ ! txt.Say(s"Chere Nicolas, il the reste $balance sur ton compte!"))
      stay()
    case Event(InfoState, _) =>
      sender() ! "Authenticated"
      stay()
    case Event(m@_, References(h)) =>
      log.info(s"UNKNOWN MESSAGE: $m")
      stay()
  }

  onTransition {
    case Initializing -> Initialized =>
      log.info("Transition to Initialized, unstash the messages ...")
      unstashAll()
    case Initialized -> Started =>
      log.info("Transition to Started")
    case Started -> Authenticated =>
      self ! GetBalance

  }

  def traceSay(msg: txt.Say)(send: => Unit) {
    //    trace.sample(msg, "NaoClient")
    //    trace.record(msg, "Send Saying event")
    send
    //    trace.finish(msg)
  }

  def sendSay(msg: txt.Say, ref: HashMap[String, Option[ActorRef]]) {
    log.info(s"Got the message $msg to send to ${ref(naoText)}")
    traceSay(msg) {
      ref(naoText).get ! msg
    }
  }

}

/**
 * Constants of the demo
 */
object FSMIoTMediator {

  val robotIP = "192.168.1.76"
  val robotPort = "2552"
  val remoteAkkaContext = s"akka.tcp://naoSystem@$robotIP:$robotPort"
  val naoEvt = s"$remoteAkkaContext/user/nao/evt"
  val naoCmd = s"$remoteAkkaContext/user/nao/cmd"
  val naoText = s"$remoteAkkaContext/user/nao/cmd/text"
  val naoMemory = s"$remoteAkkaContext/user/nao/cmd/memory"
  val naoBehavior = s"$remoteAkkaContext/user/nao/cmd/behavior"

  def sendPost(msg: String): String = {
    //TODO: refractor this java copy/paste method
    val client = new HttpClient()
    client.getParams.setParameter("http.useragent", "Test Client")
    val method = new PostMethod("http://localhost:8080/t24/enquiry")
    method.addParameter("q", msg)
    var br: BufferedReader = null
    try {
      val returnCode = client.executeMethod(method)
      method.getResponseBodyAsString()
    } catch {
      case e: Exception =>
        e.getMessage
    } finally {
      method.releaseConnection()
      if (br != null) try {
        br.close()
      } catch {
        case _: Throwable =>
      }

    }
  }

  def getBalance(record: String): String = record.split("\"\"").last.replaceAll("\"", "").trim.replace(",", "")
}
