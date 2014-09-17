package campfire.socketio.benchmark

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Cancellable
import akka.actor.Props
import akka.io.IO
import akka.pattern.ask
import com.typesafe.config.ConfigFactory
import java.io.BufferedWriter
import java.io.FileNotFoundException
import java.io.FileWriter
import java.io.IOException
import org.apache.commons.math3.stat.descriptive.{ StatisticalSummary, DescriptiveStatistics }
import spray.client.pipelining._
import spray.http.{HttpHeader, FormData, HttpResponse, Uri}
import scala.collection.mutable
import scala.collection.JavaConversions._
import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom
import spray.can.Http
import campfire.socketio.benchmark.SocketIOTestClient.MessageArrived
import campfire.socketio.benchmark.SocketIOTestClient.OnClose
import campfire.socketio.benchmark.SocketIOTestClient.OnOpen


import spray.util._

import scala.util.{Failure, Success}

object SocketIOLoadDriver {
  val config = ConfigFactory.load().getConfig("spray.socketio.benchmark")
  val clientConfig = config.getConfig("client")

  val postTestReceptionTimeout = clientConfig.getInt("post-test-reception-timeout")
  val creatingBunchClientsTimeout = clientConfig.getInt("create-bunch-clients-timeout")
  val initailMessagesPerSecond = clientConfig.getInt("initail-messages-sent-per-second")
  val nSecondsToTestEachLoadState = clientConfig.getInt("seconds-to-test-each-load-state")
  val secondsBetweenRounds = clientConfig.getInt("seconds-between-rounds")
  val nMessageSentPerSecondRamp = clientConfig.getInt("messages-sent-per-second-ramp")
  val maxMessagesSentPerSecond = clientConfig.getInt("max-messages-sent-per-second")
  val isBroadcast = config.getBoolean("broadcast")

  val addresses = clientConfig.getStringList("addresses")

  val connect = addresses map (
    _ split (":") match {
      case Array(host: String, port: String) => (s"http://${host}:${port}/session",Http.Connect(host, port.toInt))
    })

  var concurrencyLevels = clientConfig.getIntList("concurrencyLevels")

  implicit val system = ActorSystem()

  case class RoundBegin(concurrentConnections: Int)
  case class StatsSummary(stats: mutable.Map[Double, StatisticalSummary])
  case object ReceivingTimeout
  case object CreatingTimeout

  def main(args: Array[String]) {
    run(args.map(_.toInt))
  }

  def run(concurrencies: Array[Int]) {
    var file: BufferedWriter = null
    try {
      file = new BufferedWriter(new FileWriter(System.currentTimeMillis + ".log"))
    } catch {
      case ex: FileNotFoundException => ex.printStackTrace
      case ex: IOException           => ex.printStackTrace
    }

    if (concurrencies.size > 0) {
      print("Using custom concurrency levels: ")
      concurrencyLevels = new java.util.ArrayList[Integer]()
      println
      var i = 0
      for (concurrency <- concurrencies) {
        concurrencyLevels(i) = concurrency
        i += 1
        print(concurrency + " ")
      }

      println
    }

    var i = 0
    while (i < concurrencyLevels.length) {
      val concurrentConnections = concurrencyLevels(i)

      val driver = system.actorOf(Props(new SocketIOLoadDriver), "socketioclients")

      val f = driver.ask(RoundBegin(concurrentConnections))(100.minutes).mapTo[StatsSummary]
      val summaryStats = Await.result(f, Duration.Inf).stats

      system.stop(driver)

      for ((messageRate, stats) <- summaryStats) {
        try {
          file.write("%d,%f,%d,%f,%f,%f,%f\n".format(
            concurrentConnections, messageRate, stats.getN,
            stats.getMin, stats.getMean, stats.getMax,
            stats.getStandardDeviation))
          println("Wrote results of run to disk.")
        } catch {
          case ex: IOException => ex.printStackTrace
        }
      }
      i += 1
    }

    try {
      file.close
    } catch {
      case ex: IOException => ex.printStackTrace
    }

  }

  final case class RoundContext(receivingTimeoutHandler: Option[Cancellable], statistics: mutable.Map[Double, StatisticalSummary], overallEffectiveRate: Double)
}

class SocketIOLoadDriver extends Actor with ActorLogging {
  import SocketIOLoadDriver._

  private var clients = List[ActorRef]()

  private var nConnections = 0

  private var nMessagesSentPerSecond = initailMessagesPerSecond

  private var isConnectionLost = false

  private var nConnectionsOpened = 0

  private var roundtripTimes: mutable.ArrayBuffer[Double] = _

  private var roundContext: RoundContext = _

  private var isTestRunning: Boolean = _

  private var commander: ActorRef = _

  private var t0 = System.currentTimeMillis

  private var nMessagesSent: Int = _
  private var isMessagesSent: Boolean = _

  private var nConnectionsCreated: Int = _

  var creatingTimeout: Option[Cancellable] = None

  def receive = {
    case RoundBegin(nConns) =>
      t0 = System.currentTimeMillis

      commander = sender()

      nConnections = nConns
      println("---------------- Concurrent connections " + nConnections + { if (isBroadcast) " (broadcast)" else " (single bounce)" } + " ----------------")

      createNextBunchClients()

    case OnOpen =>
      nConnectionsOpened += 1
      if (nConnectionsOpened == nConnections) {

        println("\nAll " + nConnections + " clients connected successfully in " + ((System.currentTimeMillis - t0) / 1000.0) + "s.")
        println("Woken up - time to start load test!\n")
        performLoadTest()

      } else if (nConnectionsOpened == nConnectionsCreated) {

        print(".. ")

        createNextBunchClients()
      }

    case OnClose =>
      if (isTestRunning) {
        isConnectionLost = true
        println("Failed - lost a connection. Shutting down.")
      }

    case MessageArrived(roundtripTime: Long) =>
      roundtripTimes += roundtripTime

      if (isMessagesSent && roundtripTimes.size >= nMessagesExpected) {
        roundContext match {
          case null =>
          case RoundContext(receivingTimeoutHandler, statistics, overallEffectiveRate) =>
            receivingTimeoutHandler foreach (_.cancel)
            goon(statistics, overallEffectiveRate)
        }
      } else {
        log.debug("Expected: " + nMessagesExpected + ", got: " + roundtripTimes.size)
      }

    case ReceivingTimeout =>
      if (isMessagesSent /* && roundtripTimes.size >= nMessagesExpected */ ) {
        roundContext match {
          case null =>
          case RoundContext(receivingTimeoutHandler, statistics, overallEffectiveRate) =>
            goon(statistics, overallEffectiveRate)
        }
        if (roundtripTimes.size >= nMessagesExpected) {
          // everything seems ok
        } else {
          println("Failed - not all messages received in " + postTestReceptionTimeout + "s")
          println("Expected: " + nMessagesExpected + ", got: " + roundtripTimes.size)
        }
      }

    case CreatingTimeout =>
      if (nConnectionsOpened < nConnectionsCreated) {
        println(s"\n connection opened ${nConnectionsOpened} is less than ${nConnectionsCreated}")
        nConnectionsOpened = nConnectionsCreated
        createNextBunchClients()
      }
  }

  private def createNextBunchClients() {
    creatingTimeout foreach (_.cancel)
    if (nConnectionsCreated < nConnections) {
      val nToCreate = math.min(nConnections - nConnectionsCreated, 100)
      nConnectionsCreated += nToCreate
      print(nConnectionsCreated)

      var i = 0
      while (i < nToCreate) {

        val connectAddress = connect(ThreadLocalRandom.current.nextInt(connect.size))
        val uri = Uri(connectAddress._1)
        import system.dispatcher
        val pipeline = sendReceive
        val responseFuture: Future[HttpResponse] = pipeline(Post(uri, FormData(Seq("userName"->"goldratio", "password"->"111111"))))
        responseFuture onComplete {
          case Success(response) =>
            val sessionId = response.headers.foldLeft("") {
              case (sessionId, HttpHeader("set-cookie", session)) =>
                session.substring(8)
              case head =>
                head._1
            }
            val client = system.actorOf(Props(new SocketIOTestClient(connectAddress._2, sessionId, self)))
            clients ::= client
            i += 1
            creatingTimeout = Some(system.scheduler.scheduleOnce(creatingBunchClientsTimeout.seconds, self, CreatingTimeout))
            print(".")
            shutdown()
          case Failure(error) =>
            shutdown()
        }
        //val client = system.actorOf(Props(new SocketIOTestClient(connect(ThreadLocalRandom.current.nextInt(connect.size)), "", self)))
      }
    }
  }

  def shutdown(): Unit = {
    IO(Http) ! Http.CloseAll
    //system.shutdown()
  }

  private def nMessagesExpected = if (isBroadcast) nMessagesSent * nConnections else nMessagesSent

  private def performLoadTest() {
    val statistics = new mutable.HashMap[Double, StatisticalSummary]()

    isTestRunning = true

    nMessagesSentPerSecond = initailMessagesPerSecond
    triggerMessages(statistics, 0.0)
  }

  private def triggerMessages(statistics: mutable.Map[Double, StatisticalSummary], _overallEffectiveRate: Double) {
    isMessagesSent = false

    var overallEffectiveRate = _overallEffectiveRate

    println(nConnections + " connections at sending rate " + nMessagesSentPerSecond + " msgs/s: ")

    roundtripTimes = new mutable.ArrayBuffer[Double](nSecondsToTestEachLoadState * nMessagesSentPerSecond * { if (isBroadcast) nConnections else 1 })

    val t0 = System.currentTimeMillis
    val expectedDutationPerSend = 1000.0 / nMessagesSentPerSecond
    nMessagesSent = 0
    var i = 0
    while (i < nSecondsToTestEachLoadState) {
      // usually we hope triggerChatMessages will be processed in extractly 1 second.
      val messageSendStartTime = System.currentTimeMillis
      val sendingRate = triggerChatMessages(nMessagesSentPerSecond)
      val duration = System.currentTimeMillis - messageSendStartTime
      val delta = expectedDutationPerSend - duration
      // TODO flow control here according to delta?
      nMessagesSent += nMessagesSentPerSecond
      overallEffectiveRate += sendingRate
      i += 1
    }
    isMessagesSent = true
    println(nMessagesSent + " msgs " + { if (isBroadcast) "broadcast" else "sent" } + " in " + (System.currentTimeMillis - t0) + "ms, expect to receive " + nMessagesExpected)

    overallEffectiveRate = overallEffectiveRate / nSecondsToTestEachLoadState
    //println("Rate: %.3f ".format(overallEffectiveRate))

    import system.dispatcher
    roundContext = RoundContext(
      Some(system.scheduler.scheduleOnce(postTestReceptionTimeout.seconds, self, ReceivingTimeout)),
      statistics, overallEffectiveRate)
  }

  private def goon(statistics: mutable.Map[Double, StatisticalSummary], overallEffectiveRate: Double) {
    statistics.put(overallEffectiveRate, processRoundtripStats)
    nMessagesSentPerSecond += nMessageSentPerSecondRamp

    if (!isConnectionLost && nMessagesSentPerSecond < maxMessagesSentPerSecond) {
      import system.dispatcher
      system.scheduler.scheduleOnce(secondsBetweenRounds.seconds) {
        triggerMessages(statistics, overallEffectiveRate)
      }
    } else {
      isTestRunning = false
      commander ! StatsSummary(statistics)
    }
  }

  /**
   * @return sending rate: nMessages / second
   */
  private def triggerChatMessages(totalMessages: Int): Double = {
    val t0 = System.currentTimeMillis

    var senders = clients
    var i = 0
    while (i < totalMessages) {
      senders.head ! SocketIOTestClient.SendTimestampedChat
      senders = senders.tail match {
        case Nil => clients
        case xs  => xs
      }
      i += 1
    }

    totalMessages / ((System.currentTimeMillis - t0) / 1000.0)
  }

  private def processRoundtripStats: StatisticalSummary = {
    val stats = new DescriptiveStatistics()

    roundtripTimes foreach stats.addValue
    println("     num      min    mean     max   stdev    rate")
    println("%8d   %6.0f  %6.0f  %6.0f  %6.0f   %5.0f\n".format(
      stats.getN, stats.getMin, stats.getMean, stats.getMax, stats.getStandardDeviation, stats.getN / (stats.getMean / 1000.0)))
    println("     50%     66%     75%     80%     90%     95%     98%     99%    100%")
    println("  %6.0f  %6.0f  %6.0f  %6.0f  %6.0f  %6.0f  %6.0f  %6.0f  %6.0f\n".format(
      stats.getPercentile(50), stats.getPercentile(66), stats.getPercentile(75), stats.getPercentile(80),
      stats.getPercentile(90), stats.getPercentile(95), stats.getPercentile(98), stats.getPercentile(99), stats.getPercentile(100)))

    stats
  }
}
