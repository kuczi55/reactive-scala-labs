package EShop.lab6

import EShop.lab5.{ProductCatalog, SearchService}
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.config.ConfigFactory

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.Try

object ProductCatalogClusterNodeApp extends App {
  private val config = ConfigFactory.load()
  private val productCatalogNodeCount = 3

  val system = ActorSystem[Nothing](
    Behaviors.setup[Nothing] { ctx =>
      val productCatalogNodes = for (i <- 1 to productCatalogNodeCount)
                                yield ctx.spawn(ProductCatalog(new SearchService()), s"productcatalog$i")
      Behaviors.same
    },
    "ProductCatalogCluster",
    config
      .getConfig(Try(args(0)).getOrElse(""))
      .withFallback(config)
  )

  Await.ready(system.whenTerminated, Duration.Inf)
}
