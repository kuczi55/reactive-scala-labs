package EShop.lab4

import EShop.lab2.TypedCartActor.CheckoutClosed
import EShop.lab2.{TypedCartActor, TypedCheckout}
import EShop.lab3.{OrderManager, Payment}
import akka.actor.Cancellable
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, PostStop}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.duration._

class PersistentCheckout {

  import EShop.lab2.TypedCheckout._

  val timerDuration: FiniteDuration = 1.seconds

  def scheduleTimer(context: ActorContext[Command], duration: FiniteDuration, command: Command): Cancellable =
    context.scheduleOnce(duration, context.self, command)

  private def getRemainingDuration(startTime: Instant): FiniteDuration =
    timerDuration - ChronoUnit.MILLIS.between(startTime, Instant.now()).milliseconds

  def apply(cartActor: ActorRef[TypedCartActor.Command], persistenceId: PersistenceId): Behavior[Command] =
    Behaviors.setup { context =>
      EventSourcedBehavior(
        persistenceId,
        WaitingForStart,
        commandHandler(context, cartActor),
        eventHandler(context)
      ).receiveSignal {
        case (state, PostStop) =>
          state.timerOpt.foreach(_.cancel)
      }
    }

  def commandHandler(
    context: ActorContext[Command],
    cartActor: ActorRef[TypedCartActor.Command]
  ): (State, Command) => Effect[Event, State] = (state, command) => {
    state match {
      case WaitingForStart =>
        command match {
          case StartCheckout =>
            Effect.persist(CheckoutStarted(Instant.now()))
          case msg =>
            context.log.warn("Unsupported message: " + msg)
            Effect.none
        }

      case SelectingDelivery(_) =>
        command match {
          case SelectDeliveryMethod(method) =>
            Effect.persist(DeliveryMethodSelected(method))
          case ExpireCheckout =>
            Effect.persist(CheckoutCancelled)
          case CancelCheckout =>
            Effect.persist(CheckoutCancelled)
          case msg =>
            context.log.warn("Unsupported message: " + msg)
            Effect.none
        }

      case SelectingPaymentMethod(_) =>
        command match {
          case SelectPayment(payment, orderManagerRef, orderManagerPaymentRef) =>
            val paymentActor =
              context.spawn(new Payment(payment, orderManagerPaymentRef, context.self).start, "Payment")
            Effect.persist(PaymentStarted(paymentActor, Instant.now()))
              .thenRun(_ => orderManagerRef ! TypedCheckout.PaymentStarted(paymentActor, Instant.now()))
          case ExpireCheckout =>
            Effect.persist(CheckoutCancelled)
          case CancelCheckout =>
            Effect.persist(CheckoutCancelled)
          case msg =>
            context.log.warn("Unsupported message: " + msg)
            Effect.none
        }

      case ProcessingPayment(_) =>
        command match {
          case ConfirmPaymentReceived =>
            Effect.persist(CheckOutClosed)
              .thenRun(_ => cartActor ! TypedCartActor.ConfirmCheckoutClosed)
          case ExpirePayment  =>
            Effect.persist(CheckoutCancelled)
          case CancelCheckout =>
            Effect.persist(CheckoutCancelled)
          case msg =>
            context.log.warn("Unsupported message: " + msg)
            Effect.none
        }

      case Cancelled =>
        Effect.none

      case Closed =>
        Effect.none
    }
  }

  def eventHandler(context: ActorContext[Command]): (State, Event) => State = (state, event) => {
    def startTimer(startTime: Instant, command: Command): Cancellable =
      scheduleTimer(context, getRemainingDuration(startTime), command)

    event match {
      case CheckoutStarted(startTime)           =>
        SelectingDelivery(startTimer(startTime, ExpireCheckout))
      case DeliveryMethodSelected(_) =>
        SelectingPaymentMethod(state.timerOpt.get)
      case PaymentStarted(_, startTime)         =>
        state.timerOpt.foreach(_.cancel)
        ProcessingPayment(startTimer(startTime, ExpirePayment))
      case CheckOutClosed            =>
        state.timerOpt.foreach(_.cancel)
        Closed
      case CheckoutCancelled         =>
        state.timerOpt.foreach(_.cancel)
        Cancelled
    }
  }
}
