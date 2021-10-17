package EShop.lab2

import EShop.lab2.TypedCartActor.{AddItem, ConfirmCheckoutCancelled, ConfirmCheckoutClosed, ListCart, RemoveItem, Stop}
import EShop.lab2.TypedCheckout.{CancelCheckout, ConfirmPaymentReceived, SelectDeliveryMethod, SelectPayment}
import akka.actor.typed.ActorSystem

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.io.StdIn.readLine
import scala.util.control.Breaks.{break, breakable}

object TypedEShopApp extends App {
  val mainActor = ActorSystem(TypedCartActor(), "mainActor")
  var counter = 0

  def showHelp(): Unit = {
    println("Available commands:\n" +
      "* add [item_name] - adds item to cart\n" +
      "* remove [item_name] - removes item from cart\n" +
      "* checkout - go to checkout\n" +
      "* help - displays this help\n" +
      "* exit")
  }

  println("Welcome in our e-Shop!")
  showHelp()

  breakable {
    while (true) {
      mainActor ! ListCart

      val input = readLine()

      if (input.startsWith("add")) {
        val split = input.split(" ")
        if (split.size == 1) {
          println("Please provide item to add to cart")
        }
        else if (split.size > 2) {
          println("Multiple items are not supported")
        }
        else {
          mainActor ! AddItem(split(1))
        }
      }

      else if (input.startsWith("remove")) {
        val split = input.split(" ")
        if (split.size == 1) {
          println("Please provide item to remove from cart")
        }
        else if (split.size > 2) {
          println("Multiple items are not supported")
        }
        else {
          mainActor ! RemoveItem(split(1))
        }
      }

      else if(input.toLowerCase().equals("checkout")) {
        mainActor ! TypedCartActor.StartCheckout
        val checkoutActor = ActorSystem(TypedCheckout(), "checkoutActor" + counter)
        counter += 1
        checkoutActor ! TypedCheckout.StartCheckout

        var deliveryMethod = ""

        breakable {
          while(true) {
            println("Choose delivery method or type '0' to cancel: ")
            println("1) courier")
            println("2) post")
            println("0) cancel")

            val option = readLine()
            if(option.equals("1")) {
              deliveryMethod = "courier"
              break
            }
            else if(option.equals("2")) {
              deliveryMethod = "post"
              break
            }
            else if(option.equals("0")) {
              deliveryMethod = "cancel"
              break
            }
            else {
              println("Unknown option")
            }
          }
        }

        if (deliveryMethod.toLowerCase().equals("cancel")) {
          checkoutActor ! CancelCheckout
          mainActor ! ConfirmCheckoutCancelled
        }
        else {
          checkoutActor ! SelectDeliveryMethod(deliveryMethod)

          var paymentMethod = ""

          breakable {
            while(true) {
              println("Choose payment method or type '0' to cancel: ")
              println("1) card")
              println("2) cash")
              println("0) cancel")

              val option = readLine()
              if(option.equals("1")) {
                paymentMethod = "card"
                break
              }
              else if(option.equals("2")) {
                paymentMethod = "cash"
                break
              }
              else if(option.equals("0")) {
                paymentMethod = "cancel"
                break
              }
              else {
                println("Unknown option")
              }
            }
          }

          if (paymentMethod.toLowerCase().equals("cancel")) {
            checkoutActor ! CancelCheckout
            mainActor ! ConfirmCheckoutCancelled
          }
          else {
            checkoutActor ! SelectPayment(paymentMethod)

            breakable {
              while(true) {
                println("1) pay")
                println("0) cancel")

                val option = readLine()
                if(option.equals("1")) {
                  checkoutActor ! ConfirmPaymentReceived
                  mainActor ! ConfirmCheckoutClosed
                  break
                }
                else if(option.equals("0")) {
                  checkoutActor ! CancelCheckout
                  mainActor ! ConfirmCheckoutCancelled
                  break
                }
                else {
                  println("Unknown option")
                }
              }
            }
          }
        }
      }

      else if(input.toLowerCase().equals("help")) {
        showHelp()
      }

      else if (input.toLowerCase().equals("exit")) {
        mainActor ! Stop
        break
      }

      else {
        println("Unsupported command")
      }
    }
  }

  mainActor.terminate()
  Await.result(mainActor.whenTerminated, Duration.Inf)
}
