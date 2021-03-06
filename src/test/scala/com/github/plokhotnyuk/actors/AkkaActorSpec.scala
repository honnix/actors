package com.github.plokhotnyuk.actors

import akka.actor._
import java.util.concurrent.{ExecutorService, ThreadFactory, CountDownLatch}
import com.typesafe.config.ConfigFactory._
import akka.dispatch.{ExecutorServiceFactory, DispatcherPrerequisites, ExecutorServiceConfigurator}
import com.typesafe.config.Config
import com.github.plokhotnyuk.actors.BenchmarkSpec._

class AkkaActorSpec extends BenchmarkSpec {
  val config = load(parseString(
    """
      akka {
        daemonic = on
        actor {
          unstarted-push-timeout = 100s
          default-dispatcher {
            executor = "com.github.plokhotnyuk.actors.CustomExecutorServiceConfigurator"
            throughput = 1024
          }
        }
      }
    """))
  val actorSystem = ActorSystem("system", config)

  "Single-producer sending" in {
    val n = 40000000
    val l = new CountDownLatch(1)
    val a = tickActor(l, n)
    timed(n) {
      sendTicks(a, n)
      l.await()
    }
  }

  "Multi-producer sending" in {
    val n = 20000000
    val l = new CountDownLatch(1)
    val a = tickActor(l, n)
    timed(n) {
      for (j <- 1 to CPUs) fork {
        sendTicks(a, n / CPUs)
      }
      l.await()
    }
  }

  "Max throughput" in {
    val n = 40000000
    val l = new CountDownLatch(CPUs)
    val as = for (j <- 1 to CPUs) yield tickActor(l, n / CPUs)
    timed(n) {
      for (a <- as) fork {
        sendTicks(a, n / CPUs)
      }
      l.await()
    }
  }

  "Ping between actors" in {
    val n = 10000000
    val l = new CountDownLatch(2)
    val p1 = playerActor(l, n / 2)
    val p2 = playerActor(l, n / 2)
    timed(n) {
      p1.tell(Message(), p2)
      l.await()
    }
  }

  override def shutdown() {
    actorSystem.shutdown()
  }

  private def tickActor(l: CountDownLatch, n: Int): ActorRef =
    actorSystem.actorOf(Props(new Actor() {
      private var i = n

      def receive = {
        case _ =>
          i -= 1
          if (i == 0) {
            l.countDown()
            context.stop(self)
          }
      }
    }))

  private def sendTicks(a: ActorRef, n: Int) {
    val m = Message()
    var i = n
    while (i > 0) {
      a ! m
      i -= 1
    }
  }

  private def playerActor(l: CountDownLatch, n: Int): ActorRef =
    actorSystem.actorOf(Props(new Actor {
      private var i = n

      def receive = {
        case m =>
          sender ! m
          i -= 1
          if (i == 0) {
            l.countDown()
            context.stop(self)
          }
      }
    }))
}

class CustomExecutorServiceConfigurator(config: Config, prerequisites: DispatcherPrerequisites) extends ExecutorServiceConfigurator(config, prerequisites) {
  def createExecutorServiceFactory(id: String, threadFactory: ThreadFactory): ExecutorServiceFactory = new ExecutorServiceFactory {
    def createExecutorService: ExecutorService = lifoForkJoinPool(CPUs)
  }
}