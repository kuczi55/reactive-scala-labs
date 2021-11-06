package EShop.lab3

import EShop.lab2.{TypedCartActor, TypedCheckout}
import akka.actor.Cancellable
import akka.actor.testkit.typed.scaladsl.{BehaviorTestKit, ScalaTestWithActorTestKit}
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

class TypedCheckoutTest
  extends ScalaTestWithActorTestKit
  with AnyFlatSpecLike
  with BeforeAndAfterAll
  with Matchers
  with ScalaFutures {

  import TypedCheckout._

  override def afterAll: Unit =
    testKit.shutdownTestKit()

  it should "Send close confirmation to cart" in {
    val cartActorProbe = testKit.createTestProbe[TypedCartActor.Command]
    val orderManagerProbe = testKit.createTestProbe[Event]
    val orderManagerPaymentProbe = testKit.createTestProbe[Payment.Event]
    val probe = testKit.createTestProbe[String]()

    val checkoutActor = testKit.spawn(new TypedCheckout(cartActorProbe.ref) {
      override def selectingDelivery(timer: Cancellable): Behavior[TypedCheckout.Command] =
        Behaviors.setup(
          _ => {
            probe.ref ! "selectingDelivery"
            super.selectingDelivery(timer)
        })

      override def selectingPaymentMethod(timer: Cancellable): Behavior[TypedCheckout.Command] =
        Behaviors.setup(
          _ => {
            probe.ref ! "selectingPayment"
            super.selectingPaymentMethod(timer)
        })

      override def processingPayment(timer: Cancellable): Behavior[TypedCheckout.Command] =
        Behaviors.setup(
          _ => {
            probe.ref ! "processingPayment"
            super.processingPayment(timer)
        })

    }.start )

    checkoutActor ! StartCheckout
    checkoutActor ! SelectDeliveryMethod("inpost")
    probe.expectMessage("selectingDelivery")
    checkoutActor ! SelectPayment("paypal", orderManagerProbe.ref, orderManagerPaymentProbe.ref)
    probe.expectMessage("selectingPayment")
    checkoutActor ! ConfirmPaymentReceived
    probe.expectMessage("processingPayment")
    cartActorProbe.expectMessage(TypedCartActor.ConfirmCheckoutClosed)
  }

  it should "Not send close confirmation to cart" in {
    val cartActorProbe = testKit.createTestProbe[TypedCartActor.Command]
    val orderManagerProbe = testKit.createTestProbe[Event]
    val orderManagerPayment = testKit.createTestProbe[Payment.Event]
    val checkoutActor  = testKit.spawn { new TypedCheckout(cartActorProbe.ref).start}

    checkoutActor ! StartCheckout
    checkoutActor ! SelectDeliveryMethod("inpost")
    checkoutActor ! SelectPayment("paypal", orderManagerProbe.ref, orderManagerPayment.ref)
    cartActorProbe.expectNoMessage()
  }

}
