package com.github.sstone.amqp

import java.util.UUID._

import scala.jdk.CollectionConverters._
import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client._
import akka.actor._
import com.github.sstone.amqp.Amqp._

import scala.util.Try
import scala.util.Failure
import scala.util.Success
import akka.event.LoggingReceive

import scala.collection.mutable

object ChannelOwner {

  sealed trait State

  case object Disconnected extends State

  case object Connected extends State

  case class NotConnectedError(request: Request)

  case object ForwarderStopped

  def props(init: Seq[Request] = Seq.empty[Request], channelParams: Option[ChannelParameters] = None): Props = Props(new ChannelOwner(init, channelParams))

  private[amqp] class Forwarder(channel: Channel, private var channelId: String) extends Actor with ActorLogging {

    override def postStop(): Unit = {
      Try(if (channel.isOpen) channel.close())
        .recover { case e => log.error(e, "Channel closing failed:") }
      ()
    }

    override def unhandled(message: Any): Unit = log.warning(s"unhandled message $message")

    private def publishAndReturnUniqueKey(c: Channel, p: Publish): MessageUniqueKey = {
      val seqNo = c.getNextPublishSeqNo
      c.basicPublish(p.exchange, p.key, p.mandatory, p.immediate, p.properties.getOrElse(new AMQP.BasicProperties.Builder().build()), p.body)
      MessageUniqueKey(seqNo, channelId)
    }

    def receive = {
      case request@AddShutdownListener(listener) => {
        sender ! withChannel(channel, request)(c => c.addShutdownListener(new ShutdownListener {
          def shutdownCompleted(cause: ShutdownSignalException): Unit = {
            listener ! Shutdown(cause)
          }
        }))
      }
      case request@AddReturnListener(listener) => {
        sender ! withChannel(channel, request)(c => c.addReturnListener(new ReturnListener {
          def handleReturn(replyCode: Int, replyText: String, exchange: String, routingKey: String, properties: BasicProperties, body: Array[Byte]): Unit = {
            listener ! ReturnedMessage(replyCode, replyText, exchange, routingKey, properties, body)
          }
        }))
      }
      case request@Publish(_, _, _, _, _, _) => {
        log.debug("publishing %s".format(request))
        sender ! withChannel(channel, request)(c => publishAndReturnUniqueKey(c, request))
      }
      case request@Transaction(publish) => {
        sender ! withChannel(channel, request) {
          c => {
            c.txSelect()
            publish.foreach(p => c.basicPublish(p.exchange, p.key, p.mandatory, p.immediate, p.properties getOrElse new AMQP.BasicProperties.Builder().build(), p.body))
            c.txCommit()
          }
        }
      }
      case request@DeclareExchange(exchange) => {
        log.debug("declaring exchange {}", exchange)
        sender ! withChannel(channel, request)(c => declareExchange(c, exchange))
      }
      case request@DeleteExchange(exchange, ifUnused) => {
        log.debug("deleting exchange {} ifUnused {}", exchange, ifUnused)
        sender ! withChannel(channel, request)(c => c.exchangeDelete(exchange, ifUnused))
      }
      case request@DeclareQueue(queue) => {
        log.debug("declaring queue {}", queue)
        sender ! withChannel(channel, request)(c => declareQueue(c, queue))
      }
      case request@PurgeQueue(queue) => {
        log.debug("purging queue {}", queue)
        sender ! withChannel(channel, request)(c => c.queuePurge(queue))
      }
      case request@DeleteQueue(queue, ifUnused, ifEmpty) => {
        log.debug("deleting queue {} ifUnused {} ifEmpty {}", queue, ifUnused, ifEmpty)
        sender ! withChannel(channel, request)(c => c.queueDelete(queue, ifUnused, ifEmpty))
      }
      case request@QueueBind(queue, exchange, routingKey, args) => {
        log.debug("binding queue {} to key {} on exchange {}", queue, routingKey, exchange)
        sender ! withChannel(channel, request)(c => c.queueBind(queue, exchange, routingKey, args.asJava))
      }
      case request@QueueUnbind(queue, exchange, routingKey, args) => {
        log.debug("unbinding queue {} to key {} on exchange {}", queue, routingKey, exchange)
        sender ! withChannel(channel, request)(c => c.queueUnbind(queue, exchange, routingKey, args.asJava))
      }
      case request@Get(queue, autoAck) => {
        log.debug("getting from queue {} autoAck {}", queue, autoAck)
        sender ! withChannel(channel, request)(c => c.basicGet(queue, autoAck))
      }
      case request@Ack(deliveryTag) => {
        log.debug("acking %d on %s".format(deliveryTag, channel))
        sender ! withChannel(channel, request)(c => c.basicAck(deliveryTag, false))
      }
      case request@Reject(deliveryTag, requeue) => {
        log.debug("rejecting %d on %s".format(deliveryTag, channel))
        sender ! withChannel(channel, request)(c => c.basicReject(deliveryTag, requeue))
      }
      case request@CreateConsumer(listener) => {
        log.debug(s"creating new consumer for listener $listener")
        sender ! withChannel(channel, request)(_ => new DefaultConsumer(channel) {
          override def handleDelivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]): Unit = {
            listener ! com.github.sstone.amqp.Amqp.Delivery(consumerTag, envelope, properties, body)
          }
        })
      }
      case request: ConfirmSelect.type => {
        sender ! withChannel(channel, request)(c => c.confirmSelect())
      }
      case request@AddConfirmListener(listener) => {
        sender ! withChannel(channel, request)(c => c.addConfirmListener(new ConfirmListener {
          def handleAck(deliveryTag: Long, multiple: Boolean): Unit =
            listener ! HandleAck(deliveryTag, multiple, channelId, System.currentTimeMillis())

          def handleNack(deliveryTag: Long, multiple: Boolean): Unit =
            listener ! HandleNack(deliveryTag, multiple, channelId, System.currentTimeMillis())
        }))
      }
      case request@WaitForConfirms(timeout) => {
        sender ! withChannel(channel, request)(c => timeout match {
          case Some(value) => c.waitForConfirms(value)
          case None => c.waitForConfirms()
        })
      }
      case request@WaitForConfirmsOrDie(timeout) => {
        sender ! withChannel(channel, request)(c => timeout match {
          case Some(value) => c.waitForConfirmsOrDie(value)
          case None => c.waitForConfirmsOrDie()
        })
      }
    }
  }

  def withChannel[T](channel: Channel, request: Request)(f: Channel => T) = {
    Try(f(channel)) match {
      case Success(()) => {
        Ok(request)
      }
      case Success(result) => {
        Ok(request, Some(result))
      }
      case Failure(cause) => {
        Amqp.Error(request, cause)
      }
    }
  }
}

class ChannelOwner(init: Seq[Request] = Seq.empty[Request], channelParams: Option[ChannelParameters] = None) extends Actor with ActorLogging {

  import ChannelOwner._

  var requestLog: Vector[Request] = init.toVector
  val statusListeners = mutable.HashSet.empty[ActorRef]

  override def preStart() = {
    context.parent ! ConnectionOwner.CreateChannel
  }

  override def unhandled(message: Any): Unit = message match {
    case Terminated(actor) if statusListeners.contains(actor) => {
      context.unwatch(actor)
      val _ = statusListeners.remove(actor)
    }
    case _ => {
      log.warning(s"unhandled message $message")
      super.unhandled(message)
    }
  }

  def onChannel(channel: Channel, forwarder: ActorRef): Unit = {
    channelParams.foreach(p => channel.basicQos(p.qos))
  }

  def receive = disconnected

  def disconnected: Receive = LoggingReceive {
    case channel: Channel => {
      val forwarder = context.actorOf(Props(new Forwarder(channel, randomUUID().toString)), name = "forwarder")
      forwarder ! AddShutdownListener(self)
      forwarder ! AddReturnListener(self)
      onChannel(channel, forwarder)
      requestLog.map(r => self forward r)
      log.info(s"got channel $channel")
      statusListeners.map(a => a ! Connected)
      context.become(connected(channel, forwarder))
    }
    case Record(request: Request) => {
      requestLog :+= request
    }
    case AddStatusListener(actor) => addStatusListener(actor)

    case request: Request => {
      sender ! NotConnectedError(request)
    }

    case Shutdown(cause) if !cause.isInitiatedByApplication => {
      log.warning(s"Shutdown request in disconnected state: $cause")
    }
  }

  def connected(channel: Channel, forwarder: ActorRef): Receive = LoggingReceive {
    case Amqp.Ok(_, _) => ()
    case Record(request: Request) => {
      requestLog :+= request
      self forward request
    }
    case AddStatusListener(listener) => {
      addStatusListener(listener)
      listener ! Connected
    }
    case request: Request => {
      forwarder forward request
    }
    case Shutdown(cause) if !cause.isInitiatedByApplication => {
      log.error(cause, "shutdown")
      //context.stop is an async operator so we are waiting for termination before recreating channel
      context.watchWith(forwarder, ForwarderStopped)
      context.stop(forwarder)
    }

    case ForwarderStopped => {
      log.warning("Forwarder terminated - recreating channel")
      context.parent ! ConnectionOwner.CreateChannel
      statusListeners.map(a => a ! Disconnected)
      context.become(disconnected)
    }
  }

  private def addStatusListener(listener: ActorRef): Unit = {
    if (!statusListeners.contains(listener)) {
      context.watch(listener)
      val _ = statusListeners.add(listener)
    }
  }
}
