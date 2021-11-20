package EShop.lab5

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}

import scala.util.{Failure, Success}

object PaymentService {

  sealed trait Response
  case object PaymentSucceeded extends Response

  case class PaymentClientError() extends Exception
  case class PaymentServerError() extends Exception

  // actor behavior which needs to be supervised
  // use akka.http.scaladsl.Http to make http based payment request
  // use getUri method to obtain url
  def apply(
    method: String,
    payment: ActorRef[Response]
  ): Behavior[HttpResponse] = Behaviors.setup { context =>
    val http = Http(context.system)
    val result = http.singleRequest(HttpRequest(uri = getURI(method)))

    context.pipeToSelf(result) {
      case Success(value) => value
      case Failure(e)     => throw e
    }

    implicit val system: ActorSystem[Nothing] = context.system

    Behaviors.receiveMessage {
      case HttpResponse(status,_ ,_ , _) =>
        status.intValue() match {
          case 200 =>
            payment ! PaymentSucceeded
            Behaviors.stopped
          case 400 | 404 =>
            throw new PaymentClientError
          case 500 | 408 | 418 =>
            throw new PaymentServerError
        }
      case message =>
        context.log.warn("Unsupported message: {}", message)
        Behaviors.same
    }
  }

  // remember running PymentServiceServer() before trying payu based payments
  private def getURI(method: String) = method match {
    case "payu"   => "http://127.0.0.1:9000"
    case "paypal" => s"http://httpbin.org/status/408"
    case "visa"   => s"http://httpbin.org/status/200"
    case _        => s"http://httpbin.org/status/404"
  }
}
