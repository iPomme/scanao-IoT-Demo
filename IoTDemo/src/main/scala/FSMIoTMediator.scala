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

import java.net.InetAddress
import java.util


import akka.actor._
import io.nao.iot.FSMIoTMediator._
import io.nao.scanao.msg.tech.NaoEvent
import io.nao.scanao.msg.{tech, txt}
import org.apache.commons.httpclient.HttpClient
import org.apache.commons.httpclient.methods.PostMethod

import scala.collection.immutable.HashMap
import scala.util.{Success, Try, Failure}

// Message for this demonstration
sealed trait DemoMsg

// Technical messages
object InfoState extends DemoMsg

object ResetState extends DemoMsg

object StopDemo extends DemoMsg

// Heart Beat messages
object SlowHeartBeat extends DemoMsg

object NormalHeartBeat extends DemoMsg

object FastHeartBeat extends DemoMsg

// Authentication messages
case class Authenticate(name: String) extends DemoMsg

case class Welcome(name: String) extends DemoMsg

// T24 messages
object GetBalance extends DemoMsg


// Main states of the demonstration
sealed trait InitState

object Initializing extends InitState

object Initialized extends InitState

object Started extends InitState

object Authenticated extends InitState

object NextCustomer extends InitState

case class References(queue: scala.collection.immutable.HashMap[String, Option[ActorRef]], user: String = "UNKNOWN")

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

  def genericState: StateFunction = {
    case Event(NaoEvent(eventName, values, message), References(h, u)) =>
      log.debug(s"received NaoEvent name: $eventName values: $values message: $message")
      stay()
    case Event(m: txt.Say, References(h, u)) =>
      sendSay(m, h)
      stay()
    case Event(m: tech.SubscribeEvent, References(h, u)) =>
      log.debug(s"Got the message $m to send to ${h(naoEvt)}")
      h(naoEvt).map(_ ! m)
      stay()
    case Event(m:tech.UnsubscribeEvent, References(h, u)) =>
      log.debug(s"Got the message $m to send to ${h(naoEvt)}")
      h(naoEvt).map(_ ! m)
      stay()
    case Event(InfoState, _) =>
      sender() ! "Not set by the state Function"
      stay()
  }

  def initializingState: StateFunction = {
    case Event(ActorIdentity(id, ref@Some(_)), a@References(q, u)) =>
      log.info(s"Got the reference to $id !!")
      lazy val missingFeature = q.filter(_._2 == None)
      log.debug(s"The current missing remote reference is $missingFeature")
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
    case Event(m@_, References(h, u)) =>
      stash()
      log.info(s"Message $m stached as still initializing")
      stay()

  }

  when(Initializing)(initializingState orElse genericState)


  def initializedSate: StateFunction = {
    case Event(m@tech.EventSubscribed(name, module, method), References(h, u)) =>
      log.info(s"Subscribed to $m")
      h(naoText).map(_ ! txt.Say("Je suis pret"))
      goto(Started)
    case Event(InfoState, _) =>
      sender() ! "Initialized, waiting to start ..."
      stay()

  }

  when(Initialized)(initializedSate orElse genericState)

  def NextCustomerSate: StateFunction = {
    case Event(m@tech.EventSubscribed(name, module, method), References(h, u)) =>
      log.info(s"Subscribed to $m")
      h(naoText).map(_ ! txt.Say("a votre service, quel va etre mon prochain client ?"))
      goto(Started)
    case Event(InfoState, _) =>
      sender() ! "NextCustomer"
      stay()

  }

  when(NextCustomer)(NextCustomerSate orElse genericState)

  def startedState: StateFunction = {
    case Event(NaoEvent(eventName, values, message), a@References(h, u)) =>
      //      log.info(s"received NaoEvent name: $eventName values(${values.getClass.getCanonicalName}}): $values message: $message")
      recoFace(eventName, values.asInstanceOf[util.ArrayList[Any]]) match {
        case None => stay()
        case Some(name) =>
          goto(Authenticated) using a.copy(user = name)
      }
    case Event(m@tech.EventSubscribed(name, module, method), References(h, u)) =>
      log.info(s"Subscribed to $m")
      h(naoText).map(_ ! txt.Say("a votre service, quel va etre mon prochain client ?"))
      stay()
    case Event(NaoEvent(eventName, values, message), a@References(h, u)) =>
      //      log.info(s"received NaoEvent name: $eventName values(${values.getClass.getCanonicalName}}): $values message: $message")
      recoFace(eventName, values.asInstanceOf[util.ArrayList[Any]]) match {
        case None => stay()
        case Some(name) =>
          log.info(s"Recognized $name")
          goto(Authenticated) using a.copy(user = name)
      }
    case Event(m@tech.EventSubscribed(name, module, method), References(h, u)) =>
      log.info(s"Subscribed to $m")
      h(naoText).map(_ ! txt.Say("a votre service, quel va etre mon prochain client ?"))
      stay()
    case Event(InfoState, _) =>
      sender() ! "Started"
      stay()
    case Event(StopDemo, References(h, u)) =>
      goto(Initialized)
    case Event(InfoState, _) =>
      sender() ! "Started"
      stay()
    case Event(StopDemo, References(h, u)) =>
      goto(Initialized)
  }

  when(Started)(startedState orElse genericState)


  def authState: StateFunction = {
    case Event(ResetState, a@References(h, u)) =>
      goto(Started) using a.copy(user = "UNKNOWN")
    case Event(GetBalance, a@References(h, u)) =>
      h(naoText).map(_ ! txt.Say(s"Bonjour $u bienvenue dans votre system bancaire, bon, je vais demander ce qu'il vous reste sur votre compte."))
      val record = sendPost(s"ENQUIRY.SELECT,,INPUTT/123456,ACCOUNT-LIST,@ID:EQ:=${getAccountNb(u)}")
      log.info(record)
      // Split the answer on the double quote take the last occurence and remove quotes
      val balance = getBalance(record)
      h(naoText).map(_ ! txt.Say(s"Voila ${u.split(" ").head}, il vous reste $balance"))
      goto(NextCustomer) using a.copy(user = "UNKNOWN")
    case Event(InfoState, _) =>
      sender() ! "Authenticated"
      stay()
    case Event(StopDemo, a@References(h, u)) =>
      goto(Initialized) using a.copy(user = "UNKNOWN")
  }

  when(Authenticated)(authState orElse genericState)

  whenUnhandled {
    case Event(m@_, References(h, u)) =>
      log.info(s"UNKNOWN MESSAGE: $m")
      stay()
  }

  onTransition {
    //Initializing
    case Initializing -> Initialized =>
      log.info("Transition to Initialized, unstash the messages ...")
      unstashAll()
    //Initialized
    case Initialized -> Started =>
      log.info("Transition to Started")
    //Started
    case Started -> Initialized =>
      log.info("Transition to Initialized")
    case Started -> Authenticated =>
      log.info("Transition to Authenticated")
      self ! tech.UnsubscribeEvent("FaceDetected", "SNEvents")
      self ! GetBalance
    //NextCustomer
    case NextCustomer -> Authenticated =>
      log.info("Transition to Authenticated")
      self ! tech.UnsubscribeEvent("FaceDetected", "SNEvents")
    //Authenticated
    case Authenticated -> NextCustomer =>
      log.info("Transition to NextCustomer")
      self ! tech.SubscribeEvent("FaceDetected", "SNEvents", "event", self)
    case Authenticated -> Initialized =>
      log.info("Transition to Initialized")
      self ! tech.SubscribeEvent("FaceDetected", "SNEvents", "event", self)
    case Authenticated -> Started =>
      log.info("Transition to Started")
      self ! tech.SubscribeEvent("FaceDetected", "SNEvents", "event", self)

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
      ref(naoText).map(_ ! msg)
    }
  }

  def recoFace(eventName: String, values: util.ArrayList[Any]): Option[String] = {
    val recoLevel = 0.6
    eventName match {
      case "FaceDetected" =>
        Try{
          val faceDetected = values.asInstanceOf[java.util.ArrayList[Any]].get(1).asInstanceOf[java.util.ArrayList[Any]]
          val faceInfo = faceDetected.get(0).asInstanceOf[java.util.ArrayList[Any]]
          val extraInfo = faceInfo.get(1).asInstanceOf[java.util.ArrayList[Any]]
          val score: Float = extraInfo.get(1).asInstanceOf[Float]
          val label = extraInfo.get(2).asInstanceOf[String]
          score match {
            case s if s >= recoLevel => Some(label)
            case s if s < recoLevel => None
          }
        } match {
          case Success(name) => name
          case Failure(_) => None
        }

    }
  }

  def getAccountNb(name: String): String = {
    name match {
      case "Nicolas Jorand" => "2000000062"
      case "Yuuta Jorand" => "2000000097"
      case "Natsumi Jorand" => "2000000078"
      case "Luisa Jorand" => "2000000208"
      case "Dany Dubray" => "2000000186"
      case _ => "2000000089"
    }
  }

}

/**
 * Constants of the demo
 */
object FSMIoTMediator {

  val robotIP = InetAddress.getByName("sonny.local").getHostAddress
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
    Try {
      val returnCode = client.executeMethod(method)
      method.getResponseBodyAsString()
    } match {
      case Failure(e: Exception) => e.getMessage
      case Success(msg) => {
        method.releaseConnection()
        msg
      }

    }
  }

  def getBalance(record: String): String = {
    val splitted = record.split("\"\t\"")
    val rawCurrency = splitted(1).replaceAll("\"", "").trim
    val rawBalance : String = splitted.lastOption.getOrElse("").replaceAll("\"", "").trim
    rawBalance match {
      case xs if xs startsWith ("-") => s"un montant négatif de ${xs.tail} $rawCurrency"
      case xs@_ => s"$xs $rawCurrency"
    }
  }
}
