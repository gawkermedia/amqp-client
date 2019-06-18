package com.github.sstone.amqp.samples

import akka.actor.{Props, Actor, ActorSystem}
import com.github.sstone.amqp.{ChannelOwner, ConnectionOwner, Amqp}
import com.github.sstone.amqp.Amqp._
import com.rabbitmq.client.ConnectionFactory
import scala.concurrent.duration._

object PublisherConfirms extends App {

  implicit val system = ActorSystem("mySystem")

  case object PublishingFails
  case object PublishingWorks

  // create an AMQP connection
  val connFactory = new ConnectionFactory()
  connFactory.setUri("amqp://guest:guest@localhost/%2F")
  val conn = system.actorOf(ConnectionOwner.props(connFactory, 1 second))

  val producer = ConnectionOwner.createChildActor(conn, ChannelOwner.props())
  Amqp.waitForConnection(system, producer).await()

  class Foo extends Actor {

    producer ! ConfirmSelect
    producer ! AddReturnListener(self)
    producer ! AddConfirmListener(self)
    producer ! DeclareQueue(QueueParameters(name = "my_queue", passive = false, durable = false, autodelete = true))

    def receive = {
      case PublishingFails => {
        producer ! Publish("", "no_such_queue", "yo!".getBytes)
        producer ! Publish("", "no_such_queue", "yo!".getBytes)
        producer ! WaitForConfirms(None)
      }
      case PublishingWorks => {
        producer ! Publish("", "my_queue", "yo!".getBytes)
        producer ! Publish("", "my_queue", "yo!".getBytes)
        producer ! WaitForConfirms(None)
      }
      case msg => println(msg)
    }
  }

  val foo = system.actorOf(Props[Foo])
  foo ! PublishingFails
  Thread.sleep(1000)
  foo ! PublishingWorks
}
