package EShop.lab3

import EShop.lab2.{Cart, TypedCartActor, TypedCheckout}
import akka.actor.testkit.typed.Effect.{Scheduled, Spawned}
import akka.actor.testkit.typed.scaladsl.{BehaviorTestKit, ScalaTestWithActorTestKit, TestInbox}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.DurationInt

class TypedCartTest
  extends ScalaTestWithActorTestKit
  with AnyFlatSpecLike
  with BeforeAndAfterAll
  with Matchers
  with ScalaFutures {

  override def afterAll: Unit =
    testKit.shutdownTestKit()

  import TypedCartActor._

  //use GetItems command which was added to make test easier
  it should "add item properly" in {
    val behaviorTestKit = BehaviorTestKit(new TypedCartActor().start)
    val inbox = TestInbox[Cart]()

    behaviorTestKit.run(AddItem("laptop"))
    behaviorTestKit.run(GetItems(inbox.ref))
    inbox.expectMessage(Cart.empty.addItem("laptop"))
  }

  it should "be empty after adding and removing the same item" in {
    val behaviorTestKit = BehaviorTestKit(new TypedCartActor().start)
    val inbox = TestInbox[Cart]()

    behaviorTestKit.run(AddItem("MacBook"))
    behaviorTestKit.run(RemoveItem("MacBook"))
    behaviorTestKit.run(GetItems(inbox.ref))
    inbox.expectMessage(Cart.empty)
  }

  it should "start checkout" in {
    val behaviorTestKit = BehaviorTestKit(new TypedCartActor().start)
    val inbox = TestInbox[OrderManager.Command]()

    behaviorTestKit.run(AddItem("keyboard"))
    behaviorTestKit.run(StartCheckout(inbox.ref))

    behaviorTestKit.expectEffect(Scheduled(15.seconds, behaviorTestKit.ref, TypedCartActor.ExpireCart))
    behaviorTestKit.expectEffectType[Spawned[TypedCheckout]]

    val childInbox = behaviorTestKit.childInbox[TypedCheckout.Command]("Checkout")
    childInbox.expectMessage(TypedCheckout.StartCheckout)
    inbox.expectMessage(_: OrderManager.ConfirmCheckoutStarted)
  }

  it should "async add item properly" in {
    val cartActor = testKit.spawn(new TypedCartActor().start)
    val probe = testKit.createTestProbe[Any]()

    cartActor ! AddItem("laptop")
    cartActor ! GetItems(probe.ref)
    probe.expectMessage(Cart.empty.addItem("laptop"))
  }

  it should "async be empty after adding and removing the same item" in {
    val cartActor = testKit.spawn(new TypedCartActor().start)
    val probe = testKit.createTestProbe[Any]()

    cartActor ! AddItem("MacBook")
    cartActor ! RemoveItem("MacBook")
    cartActor ! GetItems(probe.ref)
    probe.expectMessage(Cart.empty)
  }

  it should "async start checkout" in {
    val cartActor = testKit.spawn(new TypedCartActor().start)
    val probe = testKit.createTestProbe[Any]()

    cartActor ! AddItem("keyboard")
    cartActor ! StartCheckout(probe.ref)
    probe.expectMessageType[OrderManager.ConfirmCheckoutStarted]
  }
}
