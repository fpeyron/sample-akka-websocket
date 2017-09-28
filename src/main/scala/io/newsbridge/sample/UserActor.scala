package io.newsbridge.sample

import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, PoisonPill, Props}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Sink, Source}

object UserActor {

  sealed trait UserEvent

  case class Connected(outgoing: ActorRef, channel: String) extends UserEvent

  case class IncomingMessage(text: String) extends UserEvent

  case class OutgoingMessage(text: String) extends UserEvent

}

class UserActor(chatRoomActor: ActorRef)(implicit system: ActorSystem) extends Actor with ActorLogging {

  import UserActor._

  override def receive = {
    case Connected(outgoing, channel: String) =>
      context.become {
        chatRoomActor ! ChatRoomActor.Joined(channel)

        {
          case IncomingMessage(text) =>
            chatRoomActor ! ChatRoomActor.messageAdded(channel, text)

          case message: OutgoingMessage =>
            outgoing ! message
        }
      }
  }


  def newUser(channel: String): Flow[Message, Message, NotUsed] = {
    // new connection - new user actor
    val userActor = system.actorOf(Props(new UserActor(chatRoomActor)))

    val incomingMessages: Sink[Message, NotUsed] =
      Flow[Message].map {
        // transform websocket message to domain message
        case TextMessage.Strict(text) => UserActor.IncomingMessage(text)
      }.to(Sink.actorRef[UserActor.IncomingMessage](userActor, PoisonPill))

    val outgoingMessages: Source[Message, NotUsed] =
      Source.actorRef[UserActor.OutgoingMessage](10, OverflowStrategy.fail)
        .mapMaterializedValue { outActor =>
          // give the user actor a way to send messages out
          userActor ! UserActor.Connected(outActor, channel)
          NotUsed
        }.map(
        // transform domain message to web socket message
        (outMsg: UserActor.OutgoingMessage) => TextMessage(outMsg.text))

    // then combine both to a flow
    Flow.fromSinkAndSource(incomingMessages, outgoingMessages)
  }
}

