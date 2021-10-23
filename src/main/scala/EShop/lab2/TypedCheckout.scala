package EShop.lab2

import akka.actor.Cancellable
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}

import scala.language.postfixOps
import scala.concurrent.duration._
import EShop.lab3.{OrderManager, Payment}

object TypedCheckout {
  def apply(): Behavior[Command] = new TypedCheckout(ActorSystem(TypedCartActor(), "CartActor")).start

  sealed trait Data
  case object Uninitialized                               extends Data
  case class SelectingDeliveryStarted(timer: Cancellable) extends Data
  case class ProcessingPaymentStarted(timer: Cancellable) extends Data

  sealed trait Command
  case object StartCheckout                                                                  extends Command
  case class SelectDeliveryMethod(method: String)                                            extends Command
  case object CancelCheckout                                                                 extends Command
  case object ExpireCheckout                                                                 extends Command
  case class SelectPayment(payment: String, orderManagerRef: ActorRef[OrderManager.Command]) extends Command
  case object ExpirePayment                                                                  extends Command
  case object ConfirmPaymentReceived                                                         extends Command

  sealed trait Event
  case object CheckOutClosed                           extends Event
  case class PaymentStarted(paymentRef: ActorRef[Any]) extends Event
}

class TypedCheckout(
  cartActor: ActorRef[TypedCartActor.Command]
) {
  import TypedCheckout._

  val checkoutTimerDuration: FiniteDuration = 10 seconds
  val paymentTimerDuration: FiniteDuration  = 10 seconds

  private def scheduleCheckoutTimer(context: ActorContext[TypedCheckout.Command]): Cancellable =
    context.scheduleOnce(checkoutTimerDuration, context.self, ExpireCheckout)
  private def schedulePaymentTimer(context: ActorContext[TypedCheckout.Command]): Cancellable =
    context.scheduleOnce(paymentTimerDuration, context.self, ExpirePayment)

  def start: Behavior[TypedCheckout.Command] = Behaviors.receive (
    (context, msg) =>
      msg match {
        case StartCheckout =>
          selectingDelivery(scheduleCheckoutTimer(context))
      }
  )

  def selectingDelivery(timer: Cancellable): Behavior[TypedCheckout.Command] = Behaviors.receive (
    (context, msg) =>
      msg match {
        case SelectDeliveryMethod(method) =>
          timer.cancel()
          println("Selected delivery method: " + method)
          selectingPaymentMethod(scheduleCheckoutTimer(context))

        case ExpireCheckout =>
          timer.cancel()
          println("Checkout expired")
          cancelled

        case CancelCheckout =>
          timer.cancel()
          println("Checkout cancelled")
          cancelled
      }
  )

  def selectingPaymentMethod(timer: Cancellable): Behavior[TypedCheckout.Command] = Behaviors.receive (
    (context, msg) =>
      msg match {
        case SelectPayment(method, orderManagerRef) =>
          timer.cancel()
          println("Selected payment method: " + method)
          val payment = context.spawn(new Payment(method, orderManagerRef, context.self).start, "Payment")
          orderManagerRef ! OrderManager.ConfirmPaymentStarted(payment)
          cartActor ! TypedCartActor.ConfirmCheckoutClosed
          processingPayment(schedulePaymentTimer(context))

        case ExpireCheckout =>
          timer.cancel()
          println("Checkout expired")
          cancelled

        case CancelCheckout =>
          timer.cancel()
          println("Checkout cancelled")
          cancelled
      }
  )

  def processingPayment(timer: Cancellable): Behavior[TypedCheckout.Command] = Behaviors.receiveMessage {
    case ConfirmPaymentReceived =>
      timer.cancel()
      closed

    case ExpirePayment =>
      timer.cancel()
      println("Payment expired")
      cancelled

    case CancelCheckout =>
      timer.cancel()
      println("Checkout cancelled")
      cancelled
  }

  def cancelled: Behavior[TypedCheckout.Command] = Behaviors.stopped

  def closed: Behavior[TypedCheckout.Command] = Behaviors.stopped

}
