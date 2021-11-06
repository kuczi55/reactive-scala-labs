package EShop.lab2

import akka.actor.{Actor, ActorRef, Cancellable, Props, Timers}
import akka.event.{Logging, LoggingReceive}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

object CartActor {

  sealed trait Command
  case class AddItem(item: Any)        extends Command
  case class RemoveItem(item: Any)     extends Command
  case object ExpireCart               extends Command
  case object StartCheckout            extends Command
  case object ConfirmCheckoutCancelled extends Command
  case object ConfirmCheckoutClosed    extends Command

  sealed trait Event
  case class CheckoutStarted(checkoutRef: ActorRef) extends Event

  def props = Props(new CartActor())
}

class CartActor extends Actor with Timers{

  import CartActor._

  private val scheduler = context.system.scheduler
  private val log       = Logging(context.system, this)
  val cartTimerDuration: FiniteDuration = 15 seconds

  private def scheduleTimer: Cancellable = scheduler.scheduleOnce(cartTimerDuration, self, ExpireCart)

  def receive: Receive = empty

  def empty: Receive = LoggingReceive {
    case AddItem(item) =>
      log.debug("Adding " + item)
      context become nonEmpty(Cart.empty.addItem(item), scheduleTimer)

    case "list" =>
      println("Cart is empty!")

    case "stop" =>
      log.debug("Stopping...")
      context become stop
  }

  def nonEmpty(cart: Cart, timer: Cancellable): Receive = LoggingReceive {
    case AddItem(item) =>
      timer.cancel()
      log.debug("Adding " + item)
      context become nonEmpty(cart.addItem(item), scheduleTimer)

    case RemoveItem(item) if cart.contains(item) && cart.size == 1 =>
      timer.cancel()
      log.debug("Removing " + item)
      context become empty

    case RemoveItem(item) if cart.contains(item) =>
      timer.cancel()
      log.debug("Removing " + item)
      context become nonEmpty(cart.removeItem(item), scheduleTimer)

    case ExpireCart =>
      timer.cancel()
      println("Cart expired")
      context become empty

    case StartCheckout =>
      timer.cancel()
      log.debug("Starting checkout...")
      context become inCheckout(cart)

    case "list" =>
      println("Items in cart: " + cart + "\n")

    case "stop" =>
      log.debug("Stopping...")
      context become stop
  }

  def inCheckout(cart: Cart): Receive = LoggingReceive {
    case ConfirmCheckoutClosed =>
      println("Checkout was closed")
      context become empty

    case ConfirmCheckoutCancelled =>
      println("Checkout was cancelled")
      context become nonEmpty(cart, scheduleTimer)

    case "list" =>
      println("Items in cart: " + cart + "\n")

    case "stop" =>
      log.debug("Stopping...")
      context become stop
  }

  def stop: Receive = LoggingReceive {
    case _ => context.stop(self)
  }

}
