package EShop.lab3

import EShop.lab2.{TypedCartActor, TypedCheckout}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}

object OrderManager {

  sealed trait Command
  case class AddItem(id: String, sender: ActorRef[Ack])                                               extends Command
  case class RemoveItem(id: String, sender: ActorRef[Ack])                                            extends Command
  case class SelectDeliveryAndPaymentMethod(delivery: String, payment: String, sender: ActorRef[Ack]) extends Command
  case class Buy(sender: ActorRef[Ack])                                                               extends Command
  case class Pay(sender: ActorRef[Ack])                                                               extends Command
  case class ConfirmCheckoutStarted(checkoutRef: ActorRef[TypedCheckout.Command])                     extends Command
  case class ConfirmPaymentStarted(paymentRef: ActorRef[Payment.Command])                             extends Command
  case object ConfirmPaymentReceived                                                                  extends Command
  private final case class OrderManagerError(cause: Throwable)                                        extends Command

  sealed trait Ack
  case object Done extends Ack //trivial ACK

  def apply(): Behavior[OrderManager.Command] = Behaviors.setup { context =>
    new OrderManager(context).start
  }
}

class OrderManager(context: ActorContext[OrderManager.Command]) {

  import OrderManager._

  val cartEventMapper: ActorRef[TypedCartActor.Event] =
    context.messageAdapter {
      case TypedCartActor.CheckoutStarted(checkoutRef) => ConfirmCheckoutStarted(checkoutRef)
    }

  val checkoutEventMapper: ActorRef[TypedCheckout.Event] =
    context.messageAdapter {
      case TypedCheckout.PaymentStarted(paymentRef, _) => ConfirmPaymentStarted(paymentRef)
    }

  val paymentEventMapper: ActorRef[Payment.Event] =
    context.messageAdapter {
      case Payment.PaymentReceived => ConfirmPaymentReceived
    }

  def start: Behavior[OrderManager.Command] = uninitialized

  def uninitialized: Behavior[OrderManager.Command] =
    Behaviors.setup[OrderManager.Command](context => {
      val cartActor = context.spawn(new TypedCartActor().start, "CartActor")
      open(cartActor)
    })

  def open(cartActor: ActorRef[TypedCartActor.Command]): Behavior[OrderManager.Command] = Behaviors.receive(
    (context, msg) =>
      msg match {
        case AddItem(item, sender) =>
          cartActor ! TypedCartActor.AddItem(item)
          sender ! Done
          Behaviors.same

        case RemoveItem(id, sender) => {
          cartActor ! TypedCartActor.RemoveItem(id)
          sender ! Done
          Behaviors.same
        }

        case Buy(sender) =>
          inCheckout(cartActor, sender)

        case _ =>
          println("Something went wrong :(")
          Behaviors.stopped
    }
  )

  def inCheckout(
    cartActorRef: ActorRef[TypedCartActor.Command],
    senderRef: ActorRef[Ack]
  ): Behavior[OrderManager.Command] =
      Behaviors.setup {
        _ =>
        cartActorRef ! TypedCartActor.StartCheckout(cartEventMapper)
        Behaviors.receiveMessage {
          case ConfirmCheckoutStarted(checkoutRef) =>
            senderRef ! Done
            inCheckout(checkoutRef)

          case _ =>
            println("Something went wrong :(")
            Behaviors.stopped
        }
      }

  def inCheckout(checkoutActorRef: ActorRef[TypedCheckout.Command]): Behavior[OrderManager.Command] = Behaviors.receive(
    (context, msg) =>
      msg match {
        case SelectDeliveryAndPaymentMethod(delivery, payment, sender) =>
          checkoutActorRef ! TypedCheckout.SelectDeliveryMethod(delivery)
          checkoutActorRef ! TypedCheckout.SelectPayment(payment, checkoutEventMapper, paymentEventMapper)
          inPayment(sender)

        case _ =>
          println("Something went wrong :(")
          Behaviors.stopped
    }
  )

  def inPayment(senderRef: ActorRef[Ack]): Behavior[OrderManager.Command] = Behaviors.withStash(100)(
    buffer =>
      Behaviors.receiveMessage {
        case ConfirmPaymentStarted(paymentRef) =>
          senderRef ! Done
          buffer.unstashAll(inPayment(paymentRef, senderRef))

        case OrderManagerError(cause) =>
          println("Something went wrong, cause: " + cause)
          Behaviors.stopped

        case other =>
          buffer.stash(other)
          Behaviors.same
    }
  )

  def inPayment(
    paymentActorRef: ActorRef[Payment.Command],
    senderRef: ActorRef[Ack]
  ): Behavior[OrderManager.Command] = Behaviors.receiveMessage {
    case Pay(sender) =>
      paymentActorRef ! Payment.DoPayment
      senderRef ! Done
      inPayment(paymentActorRef, sender)

    case ConfirmPaymentReceived =>
      senderRef ! Done
      finished

    case _ =>
      println("Something went wrong :(")
      Behaviors.stopped
  }

  def finished: Behavior[OrderManager.Command] = Behaviors.stopped
}
