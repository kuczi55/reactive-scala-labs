package EShop.lab6.it

import io.gatling.app.Gatling
import io.gatling.core.Predef.{Simulation, StringBody, jsonFile, rampUsers, scenario, _}
import io.gatling.core.config.GatlingPropertiesBuilder
import io.gatling.core.feeder.{BatchableFeederBuilder, FileBasedFeederBuilder}
import io.gatling.core.structure.ScenarioBuilder
import io.gatling.http.Predef.http
import io.gatling.http.protocol.HttpProtocolBuilder

import java.nio.file.Paths
import scala.concurrent.duration._

class ProductCatalogGatlingTest extends Simulation {

  val httpProtocol: HttpProtocolBuilder = http  //values here are adjusted to cluster_demo.sh script
    .baseUrls("http://localhost:9001", "http://localhost:9002", "http://localhost:9003")
    //.baseUrl("http://localhost:9000")
    //.acceptHeader("text/plain,text/html,application/json,application/xml;")
    .userAgentHeader("Mozilla/5.0 (Windows NT 5.1; rv:31.0) Gecko/20100101 Firefox/31.0")

  val feed: FileBasedFeederBuilder[Any]#F = jsonFile(classOf[ProductCatalogGatlingTest]
                                                .getResource("/data/search_params.json")
                                                .getPath.replaceFirst("/", "")).random

  val scn: ScenarioBuilder = scenario("SearchSimulation")
    .feed(feed)
    .exec(
      http("search")
        .get("/products?brand=${brand}&keywords=${keywords}")
    )
    .pause(4)

  setUp(
    scn.inject(
      incrementUsersPerSec(50)
        .times(10)
        .eachLevelLasting(12.seconds)
        .separatedByRampsLasting(5.seconds)
        .startingFrom(50)
    )
  ).protocols(httpProtocol)
}

object ApplicationRunner {

  def main(args: Array[String]): Unit = {

    val simClass: String = "EShop.lab6.it.ProductCatalogGatlingTest"

    val props = new GatlingPropertiesBuilder().
      simulationClass(simClass)

    Gatling.fromMap(props.build)
  }

}
