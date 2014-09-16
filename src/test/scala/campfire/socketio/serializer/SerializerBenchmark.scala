package campfire.socketio.serializer

/**
 * Created by goldratio on 9/16/14.
 */
import akka.util.ByteString
import campfire.socketio.benchmark.SimpleScalaBenchmark
import campfire.socketio.ConnectionActive.{SendEvent, OnFrame}
import com.google.caliper.Param
import akka.serialization.{ Serializer, JavaSerializer }
import akka.actor.{ ExtendedActorSystem, ActorSystem }
import com.google.caliper.runner.CaliperMain
import com.typesafe.config.ConfigFactory

class SerializerBenchmark extends SimpleScalaBenchmark {

  @Param(Array("10", "100"))
  val length: Int = 0

  var array: Array[Int] = _

  val system = ActorSystem("test", ConfigFactory.parseString("")).asInstanceOf[ExtendedActorSystem]
  val javaSerializer = new JavaSerializer(system)
  val commandSerializer = new CommandSerializer(system)

  override def setUp() {
    // set up all your benchmark data here
    array = new Array(length)
  }

  def run(serializer: Serializer, obj: AnyRef) = {
    val bytes = serializer.toBinary(obj)
    val result = serializer.fromBinary(bytes, Some(obj.getClass))
    result // always have your snippet return a value that cannot easily be "optimized away"
  }

  def timeOnFrameSerializer(reps: Int) = repeat(reps) {
    run(commandSerializer, OnFrame("123456", ByteString("hello world")))
  }

  def timeOnFrameJavaSerializer(reps: Int) = repeat(reps) {
    run(javaSerializer, OnFrame("123456", ByteString("hello world")))
  }

  def timeSendEventSerializer(reps: Int) = repeat(reps) {
    run(commandSerializer, SendEvent("123456", "string", Left("hello world")))
  }

  def timeSendEventJavaSerializer(reps: Int) = repeat(reps) {
    run(javaSerializer, SendEvent("123456", "string", Left("hello world")))
  }
}

object SerializerBenchmarkRunner {

  def main(args: Array[String]) {
    CaliperMain.main(classOf[SerializerBenchmark], args)
  }

}
