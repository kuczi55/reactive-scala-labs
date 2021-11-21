package EShop.lab6

import EShop.lab5.{ProductCatalog, SearchService}
import EShop.lab5.ProductCatalog.GetItems
import akka.Done
import akka.actor.typed.{ActorRef, ActorSystem, Scheduler}
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.{Behaviors, Routers}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import spray.json.{DefaultJsonProtocol, JsString, JsValue, JsonFormat, PrettyPrinter, RootJsonFormat}

import scala.concurrent._
import java.net.URI
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

  implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](Behaviors.empty, "ProductCatalogCluster")
  implicit val scheduler: Scheduler = system.scheduler
  implicit val executionContext: ExecutionContextExecutor = system.executionContext
  val productCatalogs: ActorRef[ProductCatalog.Query] = system.systemActorOf(
    Routers.pool(3)(ProductCatalog(new SearchService())),
    "productCatalogs"
  )

  // wait for the cluster to form up
  Thread.sleep(10000)

  def routes: Route = {
    pathPrefix("products") {
      parameters(Symbol("brand").optional, Symbol("keywords").optional) {
        (brand, keywords) =>
          get {
            val keywordsAsList = keywords.getOrElse("").split(",").toList
            val future = productCatalogs.ask(ref => GetItems(brand.getOrElse(""), keywordsAsList, ref)).mapTo[ProductCatalog.Items]
            onSuccess(future) {
              items: ProductCatalog.Items =>
                println(items)
                complete(items)
            }
          }
      }
    }
  }

  def start(port: Int): Future[Done] = {
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
