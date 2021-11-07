package EShop.lab4

import EShop.lab2
import EShop.lab2.{Cart, TypedCartActor, TypedCheckout}
import EShop.lab3.OrderManager
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import akka.persistence.typed.PersistenceId
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit.SerializationSettings

import scala.concurrent.duration._
import scala.util.Random

class PersistentCartActorTest
  extends ScalaTestWithActorTestKit(EventSourcedBehaviorTestKit.config)
  with AnyFlatSpecLike
  with BeforeAndAfterAll
  with BeforeAndAfterEach {

  override def afterAll: Unit = testKit.shutdownTestKit()

  import EShop.lab2.TypedCartActor._

  private val eventSourcedTestKit =
    EventSourcedBehaviorTestKit[Command, Event, State](
      system,
      new PersistentCartActor {
        override val cartTimerDuration: FiniteDuration = 1.second
      }.apply(generatePersistenceId),
      SerializationSettings.disabled
    )

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    eventSourcedTestKit.clear()
  }

  def generatePersistenceId: PersistenceId = PersistenceId.ofUniqueId(Random.alphanumeric.take(256).mkString)

  it should "change state after adding first item to the cart" in {
    val result = eventSourcedTestKit.runCommand(AddItem("Hamlet"))

    result.event.isInstanceOf[ItemAdded] shouldBe true
    result.state.isInstanceOf[NonEmpty] shouldBe true
  }

  it should "be empty after adding new item and removing it after that" in {
    val resultAdd = eventSourcedTestKit.runCommand(AddItem("Storm"))

    resultAdd.event.isInstanceOf[ItemAdded] shouldBe true
    resultAdd.state.isInstanceOf[NonEmpty] shouldBe true

    val resultRemove = eventSourcedTestKit.runCommand(RemoveItem("Storm"))

    resultRemove.event shouldBe CartEmptied
    resultRemove.state shouldBe Empty
  }

  it should "contain one item after adding new item and removing not existing one" in {
    val resultAdd = eventSourcedTestKit.runCommand(AddItem("Romeo & Juliet"))

    resultAdd.event.isInstanceOf[ItemAdded] shouldBe true
    resultAdd.state.isInstanceOf[NonEmpty] shouldBe true

    val resultRemove = eventSourcedTestKit.runCommand(RemoveItem("Macbeth"))

    resultRemove.hasNoEvents shouldBe true
    resultRemove.state.isInstanceOf[NonEmpty] shouldBe true
  }

  it should "change state to inCheckout from nonEmpty" in {
    val resultAdd = eventSourcedTestKit.runCommand(AddItem("Romeo & Juliet"))

    resultAdd.event.isInstanceOf[ItemAdded] shouldBe true
    resultAdd.state.isInstanceOf[NonEmpty] shouldBe true

    val resultStartCheckout =
      eventSourcedTestKit.runCommand(StartCheckout(testKit.createTestProbe[TypedCartActor.Event]().ref))

    resultStartCheckout.event.isInstanceOf[CheckoutStarted] shouldBe true
    resultStartCheckout.state.isInstanceOf[InCheckout] shouldBe true
  }

  it should "cancel checkout properly" in {
    val resultAdd = eventSourcedTestKit.runCommand(AddItem("Cymbelin"))

    resultAdd.event.isInstanceOf[ItemAdded] shouldBe true
    resultAdd.state.isInstanceOf[NonEmpty] shouldBe true

    val resultStartCheckout =
      eventSourcedTestKit.runCommand(StartCheckout(testKit.createTestProbe[TypedCartActor.Event]().ref))

    resultStartCheckout.event.isInstanceOf[CheckoutStarted] shouldBe true
    resultStartCheckout.state.isInstanceOf[InCheckout] shouldBe true

    val resultCancelCheckout =
      eventSourcedTestKit.runCommand(ConfirmCheckoutCancelled)

    resultCancelCheckout.event.isInstanceOf[CheckoutCancelled] shouldBe true
    resultCancelCheckout.state.isInstanceOf[NonEmpty] shouldBe true
  }

  it should "close checkout properly" in {
    val resultAdd = eventSourcedTestKit.runCommand(AddItem("Cymbelin"))

    resultAdd.event.isInstanceOf[ItemAdded] shouldBe true
    resultAdd.state.isInstanceOf[NonEmpty] shouldBe true

    val resultStartCheckout =
      eventSourcedTestKit.runCommand(StartCheckout(testKit.createTestProbe[TypedCartActor.Event]().ref))

    resultStartCheckout.event.isInstanceOf[CheckoutStarted] shouldBe true
    resultStartCheckout.state.isInstanceOf[InCheckout] shouldBe true

    val resultCloseCheckout =
      eventSourcedTestKit.runCommand(ConfirmCheckoutClosed)

    resultCloseCheckout.event shouldBe CheckoutClosed
    resultCloseCheckout.state shouldBe Empty
  }

  it should "not add items when in checkout" in {
    val resultAdd = eventSourcedTestKit.runCommand(AddItem("Cymbelin"))

    resultAdd.event.isInstanceOf[ItemAdded] shouldBe true
    resultAdd.state.isInstanceOf[NonEmpty] shouldBe true

    val resultStartCheckout =
      eventSourcedTestKit.runCommand(StartCheckout(testKit.createTestProbe[TypedCartActor.Event]().ref))

    resultStartCheckout.event.isInstanceOf[CheckoutStarted] shouldBe true
    resultStartCheckout.state.isInstanceOf[InCheckout] shouldBe true

    val resultAdd2 = eventSourcedTestKit.runCommand(AddItem("Henryk V"))

    resultAdd2.hasNoEvents shouldBe true
    resultAdd2.state.isInstanceOf[InCheckout] shouldBe true
  }

  it should "not change state to inCheckout from empty" in {
    val resultStartCheckout =
      eventSourcedTestKit.runCommand(StartCheckout(testKit.createTestProbe[TypedCartActor.Event]().ref))

    resultStartCheckout.hasNoEvents shouldBe true
    resultStartCheckout.state shouldBe Empty
  }

  it should "expire and back to empty state after given time" in {
    val resultAdd = eventSourcedTestKit.runCommand(AddItem("King Lear"))

    resultAdd.event.isInstanceOf[ItemAdded] shouldBe true
    resultAdd.state.isInstanceOf[NonEmpty] shouldBe true

    Thread.sleep(1500)

    val resultAdd2 = eventSourcedTestKit.runCommand(RemoveItem("King Lear"))

    resultAdd2.hasNoEvents shouldBe true
    resultAdd2.state shouldBe Empty
  }

  it should "contain added items after restart" in {
    val resultAdd = eventSourcedTestKit.runCommand(AddItem("laptop"))

    resultAdd.event.isInstanceOf[ItemAdded] shouldBe true
    resultAdd.state.isInstanceOf[NonEmpty] shouldBe true

    val resultAdd2 = eventSourcedTestKit.runCommand(AddItem("phone"))

    resultAdd2.event.isInstanceOf[ItemAdded] shouldBe true
    resultAdd2.state.isInstanceOf[NonEmpty] shouldBe true

    val resultAfterRestart = eventSourcedTestKit.restart()

    resultAfterRestart.state.isInstanceOf[NonEmpty] shouldBe true
    resultAfterRestart.state.cart shouldEqual Cart.empty.addItem("laptop").addItem("phone")
  }

  it should "contain added items before and added items after restart" in {
    val resultAdd = eventSourcedTestKit.runCommand(AddItem("laptop"))

    resultAdd.event.isInstanceOf[ItemAdded] shouldBe true
    resultAdd.state.isInstanceOf[NonEmpty] shouldBe true

    val resultAfterRestart = eventSourcedTestKit.restart()

    resultAfterRestart.state.isInstanceOf[NonEmpty] shouldBe true
    resultAfterRestart.state.cart shouldEqual Cart.empty.addItem("laptop")

    val resultAdd2 = eventSourcedTestKit.runCommand(AddItem("phone"))

    resultAdd2.event.isInstanceOf[ItemAdded] shouldBe true
    resultAdd2.state.isInstanceOf[NonEmpty] shouldBe true
    resultAdd2.state.cart shouldEqual Cart.empty.addItem("laptop").addItem("phone")
  }

  it should "ignore added item when state is checkout" in {
    val resultAdd = eventSourcedTestKit.runCommand(AddItem("laptop"))

    resultAdd.event.isInstanceOf[ItemAdded] shouldBe true
    resultAdd.state.isInstanceOf[NonEmpty] shouldBe true

    val resultStartCheckout =
      eventSourcedTestKit.runCommand(StartCheckout(testKit.createTestProbe[TypedCartActor.Event]().ref))

    resultStartCheckout.event.isInstanceOf[CheckoutStarted] shouldBe true
    resultStartCheckout.state.isInstanceOf[InCheckout] shouldBe true

    val resultAdd2 = eventSourcedTestKit.runCommand(AddItem("phone"))

    resultAdd2.hasNoEvents shouldBe true
    resultAdd2.state.isInstanceOf[InCheckout] shouldBe true

    val resultAfterRestart = eventSourcedTestKit.restart()

    resultAfterRestart.state.isInstanceOf[InCheckout] shouldBe true
    resultAfterRestart.state.cart shouldEqual Cart.empty.addItem("laptop")
  }

  it should "be empty after adding and removing item before restart" in {
    val resultAdd = eventSourcedTestKit.runCommand(AddItem("laptop"))

    resultAdd.event.isInstanceOf[ItemAdded] shouldBe true
    resultAdd.state.isInstanceOf[NonEmpty] shouldBe true

    val resultRemove = eventSourcedTestKit.runCommand(RemoveItem("laptop"))

    resultRemove.event shouldBe CartEmptied
    resultRemove.state shouldBe Empty

    val resultAfterRestart = eventSourcedTestKit.restart()

    resultAfterRestart.state shouldBe Empty
    resultAfterRestart.state.cart shouldEqual Cart.empty
  }

  it should "be empty after adding and removing item after restart" in {
    val resultAdd = eventSourcedTestKit.runCommand(AddItem("laptop"))

    resultAdd.event.isInstanceOf[ItemAdded] shouldBe true
    resultAdd.state.isInstanceOf[NonEmpty] shouldBe true

    val resultAfterRestart = eventSourcedTestKit.restart()

    resultAfterRestart.state.isInstanceOf[NonEmpty] shouldBe true
    resultAfterRestart.state.cart shouldEqual Cart.empty.addItem("laptop")

    val resultRemove = eventSourcedTestKit.runCommand(RemoveItem("laptop"))

    resultRemove.event shouldBe CartEmptied
    resultRemove.state shouldBe Empty

    val resultAfterRestart2 = eventSourcedTestKit.restart()

    resultAfterRestart2.state shouldBe Empty
    resultAfterRestart2.state.cart shouldEqual Cart.empty
  }

  it should "cancel checkout" in {
    val resultAdd = eventSourcedTestKit.runCommand(AddItem("laptop"))

    resultAdd.event.isInstanceOf[ItemAdded] shouldBe true
    resultAdd.state.isInstanceOf[NonEmpty] shouldBe true

    val resultStartCheckout =
      eventSourcedTestKit.runCommand(StartCheckout(testKit.createTestProbe[TypedCartActor.Event]().ref))

    resultStartCheckout.event.isInstanceOf[CheckoutStarted] shouldBe true
    resultStartCheckout.state.isInstanceOf[InCheckout] shouldBe true

    val resultAfterRestart = eventSourcedTestKit.restart()

    resultAfterRestart.state.isInstanceOf[InCheckout] shouldBe true
    resultAfterRestart.state.cart shouldEqual Cart.empty.addItem("laptop")

    val resultCancelCheckout =
      eventSourcedTestKit.runCommand(ConfirmCheckoutCancelled)

    resultCancelCheckout.event.isInstanceOf[CheckoutCancelled] shouldBe true
    resultCancelCheckout.state.isInstanceOf[NonEmpty] shouldBe true

    val resultAfterRestart2 = eventSourcedTestKit.restart()

    resultAfterRestart2.state.isInstanceOf[NonEmpty] shouldBe true
    resultAfterRestart2.state.cart shouldEqual Cart.empty.addItem("laptop")
  }

  it should "expire cart in NonEmpty" in {
    val resultAdd = eventSourcedTestKit.runCommand(AddItem("laptop"))

    resultAdd.event.isInstanceOf[ItemAdded] shouldBe true
    resultAdd.state.isInstanceOf[NonEmpty] shouldBe true

    Thread.sleep(550)

    val resultAfterRestart = eventSourcedTestKit.restart()

    resultAfterRestart.state.isInstanceOf[NonEmpty] shouldBe true
    resultAfterRestart.state.cart shouldEqual Cart.empty.addItem("laptop")

    Thread.sleep(500)

    val resultRemove = eventSourcedTestKit.runCommand(RemoveItem("laptop"))

    resultRemove.hasNoEvents shouldBe true
    resultRemove.state shouldBe Empty
    resultRemove.state.cart shouldEqual Cart.empty
  }

  it should "expire cart in Checkout" in {
    val resultAdd = eventSourcedTestKit.runCommand(AddItem("laptop"))

    resultAdd.event.isInstanceOf[ItemAdded] shouldBe true
    resultAdd.state.isInstanceOf[NonEmpty] shouldBe true

    val resultStartCheckout =
      eventSourcedTestKit.runCommand(StartCheckout(testKit.createTestProbe[TypedCartActor.Event]().ref))

    resultStartCheckout.event.isInstanceOf[CheckoutStarted] shouldBe true
    resultStartCheckout.state.isInstanceOf[InCheckout] shouldBe true

    Thread.sleep(550)

    val resultAfterRestart = eventSourcedTestKit.restart()

    resultAfterRestart.state.isInstanceOf[InCheckout] shouldBe true
    resultAfterRestart.state.cart shouldEqual Cart.empty.addItem("laptop")

    Thread.sleep(500)

    val resultCloseCheckout = eventSourcedTestKit.runCommand(ConfirmCheckoutClosed)

    resultCloseCheckout.state shouldBe Empty
    resultCloseCheckout.state.cart shouldEqual Cart.empty
  }
}
