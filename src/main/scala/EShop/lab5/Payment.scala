package EShop.lab5

import EShop.lab2.TypedCheckout
import EShop.lab3.OrderManager
import EShop.lab5.Payment.{PaymentRejected, WrappedPaymentServiceResponse}
import EShop.lab5.PaymentService.{PaymentClientError, PaymentServerError, PaymentSucceeded}
import akka.actor.typed.{ActorRef, Behavior, ChildFailed, RestartSupervisorStrategy, SupervisorStrategy, Terminated}
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.StreamTcpException

import scala.concurrent.duration._

object Payment {
  sealed trait Message
  case object DoPayment                                                       extends Message
  case class WrappedPaymentServiceResponse(response: PaymentService.Response) extends Message

  sealed trait Response
  case object PaymentRejected extends Response

  val restartStrategy: RestartSupervisorStrategy = SupervisorStrategy.restart.withLimit(maxNrOfRetries = 3, withinTimeRange = 1.second)

  def apply(
    method: String,
    orderManager: ActorRef[OrderManager.Command],
    checkout: ActorRef[TypedCheckout.Command]
  ): Behavior[Message] = {
    Behaviors.supervise[Message] {
      Behaviors
        .receive[Message](
          (context, msg) =>
            msg match {
              case DoPayment =>
                val paymentServiceAdapter: ActorRef[PaymentService.Response] = context.messageAdapter {
                  case PaymentService.PaymentSucceeded => WrappedPaymentServiceResponse(PaymentSucceeded)
                }
                val supervisedPaymentService = Behaviors.supervise(PaymentService(method, paymentServiceAdapter)).onFailure(restartStrategy)
                val paymentServiceActor = context.spawn(supervisedPaymentService, "paymentService")
                context.watch(paymentServiceActor)
                Behaviors.same
              case WrappedPaymentServiceResponse(PaymentSucceeded) =>
                orderManager ! OrderManager.ConfirmPaymentReceived
                checkout ! TypedCheckout.ConfirmPaymentReceived
                Behaviors.same
              case message =>
                context.log.warn("Unsupported message: {}", message)
                Behaviors.same
            }
        )
        .receiveSignal {
          case (context, Terminated(t)) =>
            context.log.warn("Job stopped: {}", t.path.name)
            notifyAboutRejection(orderManager, checkout)
            Behaviors.same
          case (context, ChildFailed(ref, cause)) =>
            context.log.warn("The child actor {} failed because {}", ref, cause.getMessage)
            notifyAboutRejection(orderManager, checkout)
            Behaviors.same
        }
    }.onFailure[IllegalStateException](SupervisorStrategy.restart)
  }

  // please use this one to notify when supervised actor was stoped
  private def notifyAboutRejection(
    orderManager: ActorRef[OrderManager.Command],
    checkout: ActorRef[TypedCheckout.Command]
  ): Unit = {
    orderManager ! OrderManager.PaymentRejected
    checkout ! TypedCheckout.PaymentRejected
  }

}
