package EShop.lab6

import EShop.lab5.ProductCatalog
import EShop.lab5.ProductCatalog.{GetItems, ProductCatalogServiceKey}
import EShop.lab6.StatsActor.GetStats
import akka.Done
import akka.actor.typed.receptionist.Receptionist
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
import java.util.NoSuchElementException
import scala.concurrent.duration._
import scala.util.{Failure, Success}

trait ProductCatalogClusterJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
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
  implicit val statsJsonFormat: RootJsonFormat[StatsActor.Stats] = jsonFormat1(StatsActor.Stats)
}

object ProductCatalogClusterHttpServerApp extends App {
  val productCatalogHttpServerInCluster = new ProductCatalogHttpServerInCluster()
  productCatalogHttpServerInCluster.start(args(0).toInt)
}

/** Just to demonstrate how one can build akka-http based server with JsonSupport */
class ProductCatalogHttpServerInCluster extends ProductCatalogClusterJsonSupport {
  implicit val timeout: Timeout = 3.second

  val config: Config = ConfigFactory.load()

  implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](
    Behaviors.empty,
    "ProductCatalogCluster",
    config
  )

  implicit val scheduler: Scheduler = system.scheduler
  implicit val executionContext: ExecutionContextExecutor = system.executionContext

  val productCatalogs: ActorRef[ProductCatalog.Query] = system.systemActorOf(
    Routers.group(ProductCatalogServiceKey),
    "productCatalogsRouter"
  )

  // wait for the cluster to form up
  Thread.sleep(10000)

  def routes: Route = {
    concat(
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
      },
      path("stats") {
        get {
          val listingFuture: Future[Receptionist.Listing] = system.receptionist.ask(
            (ref: ActorRef[Receptionist.Listing]) => Receptionist.find(StatsActor.StatsActorServiceKey, ref)
          )
          onSuccess(listingFuture) {
            case StatsActor.StatsActorServiceKey.Listing(listing) =>
              try {
                val statsActor = listing.head
                val future = statsActor.ask(ref => GetStats(ref)).mapTo[StatsActor.Stats]
                onSuccess(future) {
                  stats: StatsActor.Stats => complete(stats)
                }
              } catch {
                case e: NoSuchElementException => failWith(e)
              }
            case unknownListing =>
              failWith(new IllegalArgumentException("Got unknown listing" + unknownListing))
          }
        }
      }
    )
  }

  def start(port: Int): Future[Done] = {
    val bindingFuture = Http().newServerAt("localhost", port).bind(routes)

    bindingFuture
      .onComplete {
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