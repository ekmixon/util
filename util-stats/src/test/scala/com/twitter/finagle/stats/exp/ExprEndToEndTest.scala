package com.twitter.finagle.stats.exp

import com.twitter.conversions.DurationOps._
import com.twitter.finagle.stats._
import com.twitter.util.{Stopwatch, Time, TimeControl}
import org.scalatest.FunSuite
import scala.util.control.NonFatal

class ExprEndToEndTest extends FunSuite {
  def mySlowQuery(ctl: TimeControl, succeed: Boolean): Unit = {
    ctl.advance(50.milliseconds)
    if (!succeed) {
      throw new Exception("boom!")
    }
  }

  test("Demonstrate an end to end example of using metrics") {
    val sr = new InMemoryStatsReceiver
    val successMb = CounterSchema(new MetricBuilder(name = Seq("success"), statsReceiver = sr))
    val failuresMb =
      CounterSchema(new MetricBuilder(name = Seq("failures"), statsReceiver = sr))
    val latencyMb = HistogramSchema(new MetricBuilder(name = Seq("latency"), statsReceiver = sr))
    val sum = Expression(successMb).plus(Expression(failuresMb))
    val successCounter = sr.counter(successMb)
    val failuresCounter = sr.counter(failuresMb)
    val latencyStat = sr.stat(latencyMb)
    val successRate = ExpressionSchema("success_rate", Expression(successMb).divide(sum))
      .withBounds(MonotoneThreshold(GreaterThan, 99.5, 99.75))
      .withUnit(Percentage)
      .withDescription("The success rate of the slow query")

    val latency = ExpressionSchema("latency", Expression(latencyMb))
      .withUnit(Milliseconds)
      .withDescription("The latency of the slow query")

    successRate.register()
    latency.register()

    def runTheQuery(succeed: Boolean): Unit = {
      Time.withCurrentTimeFrozen { ctl =>
        val elapsed = Stopwatch.start()
        try {
          mySlowQuery(ctl, succeed)
          successCounter.incr()
        } catch {
          case NonFatal(exn) =>
            failuresCounter.incr()
        } finally {
          latencyStat.add(elapsed().inMilliseconds)
        }
      }
    }
    runTheQuery(true)
    runTheQuery(false)

    assert(sr.expressions("success_rate") == successRate)
    assert(sr.expressions("latency") == latency)

    assert(sr.counters(Seq("success")) == 1)
    assert(sr.counters(Seq("failures")) == 1)
    assert(sr.stats(Seq("latency")) == Seq(50, 50))
  }
}