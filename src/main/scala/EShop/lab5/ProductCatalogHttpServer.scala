package EShop.lab5

import EShop.lab5.ProductCatalog.GetItems
import akka.actor.typed.{ActorRef, ActorSystem, Scheduler}
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import spray.json.{DefaultJsonProtocol, JsString, JsValue, JsonFormat, PrettyPrinter, RootJsonFormat}

import scala.concurrent._
import ExecutionContext.Implicits.global
import java.net.URI
import java.util.NoSuchElementException
import scala.concurrent.duration._
import scala.util.{Failure, Success}

trait ProductCatalogJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val printer: PrettyPrinter.type = PrettyPrinter

  implicit val uriFormat = new JsonFormat[java.net.URI] {
    override def write(obj: java.net.URI): spray.json.JsValue = JsString(obj.toString)
    override def read(json: JsValue): URI =
      json match {
        case JsString(url) => new URI(url)
        case _             => throw new RuntimeException("Parsing exception")
      }
  }

  implicit val itemJsonFormat: RootJsonFormat[ProductCatalog.Item] = jsonFormat5(ProductCatalog.Item)
  implicit val itemsJsonFormat: RootJsonFormat[ProductCatalog.Items] = jsonFormat1(ProductCatalog.Items)
}

object ProductCatalogHttpServerApp extends App {
  new ProductCatalogHttpServer().start(9000)
}

/** Just to demonstrate how one can build akka-http based server with JsonSupport */
class ProductCatalogHttpServer extends ProductCatalogJsonSupport {
  implicit val timeout: Timeout = 3.second

  val config: Config = ConfigFactory.load()

  implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](Behaviors.empty, "ProductCatalog")
  implicit val scheduler: Scheduler = system.scheduler

//  val productCatalogSystem: ActorSystem[Nothing] =
//    ActorSystem[Nothing](Behaviors.empty, "ProductCatalog", config.getConfig("productcatalog").withFallback(config))
//  productCatalogSystem.systemActorOf(ProductCatalog(new SearchService()), "productcatalog")

  // wait for the cluster to form up
  Thread.sleep(3000)

  def routes: Route = {
    pathPrefix("products") {
      parameters(Symbol("brand"), Symbol("keywords")) {
        (brand, keywords) =>
          get {
            val keywordsAsList = keywords.split(",").toList
            val listingFuture: Future[Receptionist.Listing] = system.receptionist.ask(
              (ref: ActorRef[Receptionist.Listing]) => Receptionist.find(ProductCatalog.ProductCatalogServiceKey, ref)
            )
            onSuccess(listingFuture) {
              case ProductCatalog.ProductCatalogServiceKey.Listing(listing) =>
                try {
                  val listings = listing
                  val productCatalog = listings.head
                  val future = productCatalog.ask(ref => GetItems(brand, keywordsAsList, ref)).mapTo[ProductCatalog.Items]
                  onSuccess(future) {
                    items: ProductCatalog.Items => complete(items)
                  }
                } catch {
                  case e: NoSuchElementException => failWith(e)
                }

            }
          }
      }
    }
  }

  def start(port: Int) = {
    val bindingFuture = Http().newServerAt("localhost", port).bind(routes)

    bindingFuture.onComplete {
      case Success(bound) =>
        system.log.info(s"Server online at http://${bound.localAddress.getHostString}:${bound.localAddress.getPort}/products")
        scala.io.StdIn.readLine
        bindingFuture.flatMap(_.unbind).onComplete(_ => system.terminate)
      case Failure(e) =>
        system.log.error(s"Server could not start!")
        e.printStackTrace()
        system.terminate()
    }

    Await.ready(system.whenTerminated, Duration.Inf)
  }

}
