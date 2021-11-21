package EShop.lab6

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.pubsub.Topic
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.config.ConfigFactory

import scala.concurrent.Await
import scala.concurrent.duration.Duration

object StatsActor {
  sealed trait Event
  case class GetStats(actorRef: ActorRef[Ack]) extends Event
  case class GotQuery(actorRef: String) extends Event

  sealed trait Ack
  case class Stats(stats: Map[String, Int]) extends Ack

  val StatsActorServiceKey: ServiceKey[Event] = ServiceKey[Event]("StatsActorKey")

  def apply(): Behavior[Event] = Behaviors.setup {
    context =>
      context.system.receptionist ! Receptionist.register(StatsActorServiceKey, context.self)

      val topic = context.spawn(Topic[GotQuery]("product-catalog-topic"),
                                                "ProductCatalogTopicStatsActor")
      topic ! Topic.Subscribe(context.self)

      val stats: Map[String, Int] = Map.empty
      working(stats)
  }

  def working(stats: Map[String, Int]): Behavior[Event] =
    Behaviors.receiveMessage {
      case GotQuery(actorRef) =>
        val queryAmount = stats.getOrElse(actorRef, 0) + 1
        val newStats = stats + (actorRef -> queryAmount)
        working(newStats)
      case GetStats(actorRef) =>
        actorRef ! Stats(stats)
        Behaviors.same
    }
}

object StatsActorNode extends App {
  private val config = ConfigFactory.load()

  val system = ActorSystem[Nothing](
    Behaviors.setup[Nothing] { ctx =>
      val statsActor = ctx.spawn(StatsActor(), "StatsActor")
      Behaviors.same
    },
    "ProductCatalogCluster",
    config
      .getConfig("stats-node")
      .withFallback(config)
  )

  Await.ready(system.whenTerminated, Duration.Inf)
}
