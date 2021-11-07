package EShop.lab4

import EShop.lab2.{Cart, TypedCartActor, TypedCheckout}
import EShop.lab3.OrderManager
import akka.actor.Cancellable
import akka.actor.typed.{Behavior, PostStop}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.duration._

class PersistentCartActor {

  import EShop.lab2.TypedCartActor._

  val cartTimerDuration: FiniteDuration = 1.seconds

  private def scheduleTimer(context: ActorContext[Command], duration: FiniteDuration): Cancellable =
    context.scheduleOnce(duration, context.self, ExpireCart)

  private def getRemainingDuration(startTime: Instant): FiniteDuration =
    cartTimerDuration - ChronoUnit.MILLIS.between(startTime, Instant.now()).milliseconds

  def apply(persistenceId: PersistenceId): Behavior[Command] = Behaviors.setup { context =>
    EventSourcedBehavior[Command, Event, State](
      persistenceId,
      Empty,
      commandHandler(context),
      eventHandler(context)
    ).receiveSignal {
      case (state, PostStop) =>
        state.timerOpt.foreach(_.cancel)
    }
  }

  def commandHandler(context: ActorContext[Command]): (State, Command) => Effect[Event, State] = (state, command) => {
    state match {
      case Empty =>
        command match {
          case AddItem(item) =>
            Effect.persist(ItemAdded(item, Instant.now()))
          case GetItems(sender) =>
            sender ! Cart.empty
            Effect.none
          case msg =>
            context.log.warn("Unsupported message: " + msg)
            Effect.none
        }

      case NonEmpty(cart, _) =>
        command match {
          case AddItem(item) =>
            Effect.persist(ItemAdded(item, Instant.now()))
          case RemoveItem(item) if !cart.contains(item) =>
            context.log.warn("[RemoveItem] Item " + item + " not in cart!")
            Effect.none
          case RemoveItem(_) if cart.size == 1 =>
            Effect.persist(CartEmptied)
          case RemoveItem(item) =>
            Effect.persist(ItemRemoved(item, Instant.now()))
          case StartCheckout(orderManagerRef) =>
            val checkoutActor = context.spawn(new TypedCheckout(context.self).start, "Checkout")
            Effect.persist(CheckoutStarted(checkoutActor)).thenRun { _ =>
              checkoutActor ! TypedCheckout.StartCheckout
              orderManagerRef ! TypedCartActor.CheckoutStarted(checkoutActor)
            }
          case ExpireCart =>
            Effect.persist(CartExpired)
          case GetItems(sender) =>
            sender ! cart
            Effect.none
          case msg =>
            context.log.warn("Unsupported message: " + msg)
            Effect.none

      }

      case InCheckout(cart) =>
        command match {
          case ConfirmCheckoutCancelled =>
            Effect.persist(CheckoutCancelled(Instant.now()))
          case ConfirmCheckoutClosed    =>
            Effect.persist(CheckoutClosed)
          case GetItems(sender) =>
            sender ! cart
            Effect.none
          case msg =>
            context.log.warn("Unsupported message: " + msg)
            Effect.none
        }
    }
  }

  def eventHandler(context: ActorContext[Command]): (State, Event) => State = (state, event) => {
    val cart = state.cart
    def startTimer(startTime: Instant): Cancellable = scheduleTimer(context, getRemainingDuration(startTime))

    event match {
      case CheckoutStarted(_)           =>
        state.timerOpt.foreach(_.cancel())
        InCheckout(cart)
      case ItemAdded(item, startTime)   =>
        state.timerOpt.foreach(_.cancel())
        NonEmpty(cart.addItem(item), startTimer(startTime))
      case ItemRemoved(item, startTime) =>
        state.timerOpt.foreach(_.cancel())
        NonEmpty(cart.removeItem(item), startTimer(startTime))
      case CartEmptied | CartExpired    =>
        state.timerOpt.foreach(_.cancel())
        Empty
      case CheckoutClosed               =>
        Empty
      case CheckoutCancelled(startTime) =>
        NonEmpty(cart, startTimer(startTime))
    }
  }

}
