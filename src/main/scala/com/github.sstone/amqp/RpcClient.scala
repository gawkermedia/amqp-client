package com.github.sstone.amqp

import akka.actor.{Props, ActorRef}
import akka.event.LoggingReceive
import com.github.sstone.amqp.Amqp._
import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client.{Envelope, Channel, DefaultConsumer}

object RpcClient {

  case class Request(publish: List[Publish], numberOfResponses: Int = 1)

  object Request {
    def apply(publish: Publish) = new Request(List(publish), 1)
  }

  case class Response(deliveries: List[Delivery])

  case class Undelivered(msg: ReturnedMessage)

  def props(channelParams: Option[ChannelParameters] = None): Props = Props(new RpcClient(channelParams))

  private[amqp] case class RpcResult(destination: ActorRef, expected: Int, deliveries: scala.collection.mutable.ListBuffer[Delivery])

}

class RpcClient(channelParams: Option[ChannelParameters] = None) extends ChannelOwner(channelParams = channelParams) {

  import RpcClient._

  private var queue: String = ""
  private var consumer: Option[DefaultConsumer] = None
  private var counter: Int = 0
  private val correlationMap = scala.collection.mutable.Map.empty[String, RpcResult]

  override def onChannel(channel: Channel, forwarder: ActorRef): Unit = {
    super.onChannel(channel, forwarder)
    // create a private, exclusive reply queue; its name will be randomly generated by the broker
    queue = declareQueue(channel, QueueParameters("", passive = false, exclusive = true)).getQueue
    log.debug(s"setting consumer on private queue $queue")
    consumer = Some(new DefaultConsumer(channel) {
      override def handleDelivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]): Unit = {
        self ! Delivery(consumerTag, envelope, properties, body)
      }
    })
    channel.basicConsume(queue, false, consumer.get)
    correlationMap.clear()
  }

  override def disconnected: Receive = LoggingReceive ({
    case Request(_, _) => {
      log.warning(s"not connected, cannot send rpc request")
    }
  }: Receive) orElse super.disconnected

  override def connected(channel: Channel, forwarder: ActorRef): Receive = LoggingReceive({
    case Request(publish, numberOfResponses) => {
      counter = counter + 1
      log.debug(s"sending ${publish.size} messages, replyTo = $queue")
      publish.foreach(p => {
        val props = p.properties.getOrElse(new BasicProperties()).builder.correlationId(counter.toString).replyTo(queue).build()
        channel.basicPublish(p.exchange, p.key, p.mandatory, p.immediate, props, p.body)
      })
      if (numberOfResponses > 0) {
        correlationMap += (counter.toString -> RpcResult(sender, numberOfResponses, collection.mutable.ListBuffer.empty[Delivery]))
      }
    }
    case delivery@Delivery(_: String, envelope: Envelope, properties: BasicProperties, _: Array[Byte]) => {
      channel.basicAck(envelope.getDeliveryTag, false)
      correlationMap.get(properties.getCorrelationId) match {
        case Some(results) => {
          results.deliveries += delivery
          if (results.deliveries.length == results.expected) {
            results.destination ! Response(results.deliveries.toList)
            correlationMap -= properties.getCorrelationId
          }
        }
        case None => log.warning("unexpected message with correlation id " + properties.getCorrelationId)
      }
    }
    case msg@ReturnedMessage(_, _, _, _, properties, _) => {
      correlationMap.get(properties.getCorrelationId) match {
        case Some(results) => {
          results.destination ! RpcClient.Undelivered(msg)
          correlationMap -= properties.getCorrelationId
        }
        case None => log.warning("unexpected returned message with correlation id " + properties.getCorrelationId)
      }
    }
  }: Receive) orElse super.connected(channel, forwarder)
}
