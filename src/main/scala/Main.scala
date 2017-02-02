import java.io.{File, FileOutputStream, PrintWriter}
import java.util.concurrent.TimeUnit

import FFT.Complex
import org.apache.commons.math3.distribution.LogNormalDistribution
import org.apache.flink.api.scala._
import org.apache.flink.api.common.accumulators.{Accumulator, IntCounter, SimpleAccumulator}
import org.apache.flink.api.common.state.ValueStateDescriptor
import org.apache.flink.api.java.tuple.Tuple
import org.apache.flink.configuration.Configuration
import org.apache.flink.runtime.state.hybrid.MemoryFsStateBackend
import org.apache.flink.streaming.api.functions.AssignerWithPunctuatedWatermarks
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.apache.flink.streaming.api.scala.function.RichWindowFunction
import org.apache.flink.streaming.api.watermark.Watermark
import org.apache.flink.streaming.api.windowing.time.Time
import org.apache.flink.streaming.api.windowing.triggers.{Trigger, TriggerResult}
import org.apache.flink.streaming.api.windowing.triggers.Trigger.TriggerContext
import org.apache.flink.streaming.api.windowing.windows.TimeWindow
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.util.Collector


object Main {

//  val WindowDurationSec = 5
//  val WindowDurationMillis = TimeUnit.SECONDS.toMillis(WindowDurationSec)
//  val NumberOfPastWindows = 2

  def main(args: Array[String]): Unit = {

    if (args.length != 8) {
      System.err.println("Usage: Main <maxTuplesInMemory> <tuplesAfterSpillFactor> <tuplesToWatermarkThreshold> <complexity> " +
        "<windowDurationSec> <slideDurationSec> <numberOfPastWindows> <maximumWatermarks>")
      System.exit(1)
    }
    val (maxTuplesInMemory, tuplesAfterSpillFactor, tuplesWkThreshold, complexity, windowDurationSec, slideDurationSec,
    numberOfPastWindows, maximumWatermarks) = (args(0).toInt, args(1).toDouble, args(2).toLong, args(3).toInt,
      args(4).toInt, args(5).toInt, args(6).toInt, args(7).toInt)

//    val windowDurationMillis = TimeUnit.SECONDS.toMillis(windowDurationSec)
    val slideDurationMillis = TimeUnit.SECONDS.toMillis(slideDurationSec)

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    env.setStateBackend(new MemoryFsStateBackend(maxTuplesInMemory, tuplesAfterSpillFactor, 5))
    // env.setStateBackend(new FsStateBackend("hdfs://ginja-a1:9000/flink/checkpoints"));


//    def makeTuples(n: Int) = (1 to nransient).map((_, 1))
//    val stream = env.fromElements(makeTuples(1000000): _*)
//      .assignAscendingTimestamps(p => System.currentTimeMillis())


    val rawStream = env.socketTextStream("localhost", 9990)

    /*
    val throughputFName = s"throughput-${System.currentTimeMillis()}.txt"
    rawStream.map(_ => Tuple1(1)).keyBy(0).window(TumblingProcessingTimeWindows.of(Time.seconds(1))).sum(0)
      .map(tuple => {
        val pw = new PrintWriter(new FileOutputStream(new File(throughputFName), true), true)
        pw.println(s"${System.currentTimeMillis()},${tuple._1}")
      })
     // .writeAsCsv("records-per-second-" + System.currentTimeMillis() + ".csv", )
    */

    val punctuatedAssigner = new AssignerWithPunctuatedWatermarks[(String, Int, Long)] {
      // var watermarkEmitted = false

      var watermarkCount = 0

      // scale and shape (or mean and stddev) are 0 and 1 respectively
      val logNormalDist = new LogNormalDistribution()

//      @transient
//      var random: Random = null

      var windowMaxTS = -1l

      def sample(): Int = {
        val i = math.round(logNormalDist.sample()).toInt - 1
        if(i <= numberOfPastWindows) { if(i < 0) 0 else i } else sample()
      }

      override def extractTimestamp(element: (String, Int, Long), previousElementTimestamp: Long): Long = {
        // if(random == null) random = Random

        val windowIndex = sample()

        // val sample = random.nextInt(100)
        // val windowIndex = if(sample < 70) 0 else if(sample < 90) 1 else 2

        val ts = System.currentTimeMillis()
        if(windowMaxTS < 0) {
          windowMaxTS = ts - (ts % slideDurationMillis) + slideDurationMillis
          println(s"### setting window maxTS to: $windowMaxTS, ts: $ts")
        }
        ts - windowIndex * slideDurationMillis
      }

      override def checkAndGetNextWatermark(lastElement: (String, Int, Long), extractedTimestamp: Long): Watermark = {

        if(extractedTimestamp > windowMaxTS) {
          watermarkCount += 1
          if(watermarkCount == maximumWatermarks) System.exit(0)

          windowMaxTS = extractedTimestamp - (extractedTimestamp % slideDurationMillis) + slideDurationMillis
          println(s"### Emitting watermark at ts: $extractedTimestamp, windowsMaxTS: $windowMaxTS")
          new Watermark(extractedTimestamp)
        } else null

      }
    }


    val stream = rawStream.map(line => {
      val Array(p1, p2, p3) = line.split(" ")
      (p1, p2.toInt, p3.toLong)
    })
      .assignTimestampsAndWatermarks(punctuatedAssigner)
      // .assignAscendingTimestamps(p => System.currentTimeMillis())
      .map(tuple => (tuple._1, tuple._2))

    // The means that it will fire every 10 minutes (in processing time) until the end of the window (event time),
    // and then every 5 minutes (processing time) for late elements up to 20 minutes late. In addition, previous
    // elements are not discarded.
    val trigger = EventTimeTriggerWithEarlyAndLateFiring.create()
      .withEarlyFiringEvery(Time.seconds(1))
      .withLateFiringEvery(Time.seconds(2))
      .withAllowedLateness(Time.seconds(2))
      .accumulating()

    val fireFName = s"fire-${System.currentTimeMillis()}.txt"
    val fireAndPurgeFName = s"fire-and-purge-${System.currentTimeMillis()}.txt"

    val trigger3 = new Trigger[Any, TimeWindow] {
      val firedOnWatermarkDescriptor =
        new ValueStateDescriptor[java.lang.Boolean]("FIRED_ON_WATERMARK", classOf[java.lang.Boolean], false)

      override def onElement(t: Any, l: Long, w: TimeWindow, triggerContext: TriggerContext): TriggerResult = {
        triggerContext.registerEventTimeTimer(w.maxTimestamp())
        TriggerResult.CONTINUE
      }

      override def onProcessingTime(time: Long, timeWindow: TimeWindow, triggerContext: TriggerContext): TriggerResult
        = ???

      override def onEventTime(time: Long, timeWindow: TimeWindow, triggerContext: TriggerContext): TriggerResult = {
        // println(s"### onEventTime called (time: $time, window: $timeWindow)")

        if (time == timeWindow.maxTimestamp) {
          val firedOnWatermark = triggerContext.getPartitionedState(firedOnWatermarkDescriptor)
          if(firedOnWatermark.value()) {
            TriggerResult.CONTINUE
          } else {
            firedOnWatermark.update(true)
            val pw = new PrintWriter(new FileOutputStream(new File(fireFName), true), true)
            pw.println(System.currentTimeMillis())
            TriggerResult.FIRE
          }
        } else {
          val pw = new PrintWriter(new FileOutputStream(new File(fireAndPurgeFName), true), true)
          pw.println(System.currentTimeMillis())
          // println("FIRE AND PURGE!")
          TriggerResult.FIRE_AND_PURGE
        }
      }

//      override def clear(timeWindow: TimeWindow, triggerContext: Trigger.TriggerContext) = {
//        val windowCount = triggerContext.getPartitionedState(windowCountDescriptor)
//        windowCount.update(windowCount.value() + 1)
//        if(windowCount.value() > maximumWindows) System.exit(0)
//      }
    }

    val numRecords = new IntCounter()

    val accumulator = new SimpleAccumulator[(Int, Int)] {
      var value = (0, 0)

      override def getLocalValue: (Int, Int) = value

      override def resetLocal(): Unit = value = (0, 0)

      override def merge(other: Accumulator[(Int, Int), (Int, Int)]): Unit = ???

      override def add(value: (Int, Int)): Unit = this.value = (value._1, this.value._2 + value._2)
    }

    def myFunction = new RichWindowFunction[(Int, Int), (Int, Int), Tuple, TimeWindow] {
      override def open(parameters: Configuration)  {
        super.open(parameters)

        getRuntimeContext().addAccumulator("num-records", numRecords)
        getRuntimeContext().addAccumulator("accumulator",  accumulator)
      }

      override def apply(key: Tuple, window: TimeWindow, input: Iterable[(Int, Int)], out: Collector[(Int, Int)]): Unit
      = {
        val newInput = input.drop(numRecords.getLocalValue)
        numRecords.add(newInput.size)

        val resultInput = newInput.reduce((p1, p2) => (p1._1, p1._2 + p2._2))
        accumulator.add(resultInput)
        out.collect(accumulator.getLocalValue)
      }
    }


    def padder(data:List[Complex]) : List[Complex] = {
      def check(num:Int) : Boolean = if((num.&(num-1)) == 0) true else false
      def pad(i:Int) : Int = {
        check(i) match {
          case true => i
          case false => pad(i + 1)
        }
      }
      if(check(data.length) == true) data else data.padTo(pad(data.length), Complex(0))
    }

    val computeStartFName = s"compute-time-${System.currentTimeMillis()}.txt"

    def simpleFunction = (key: Tuple, timeWindow: TimeWindow, iterator: Iterable[(String, Int)],
                                 collector: Collector[(String, Int)]) => {
      val startTick = System.currentTimeMillis()
      // iterator shall never be called more than once
      val (str, sum, size) =
        iterator.map(t => (t._1, t._2, 1)).reduce((t1, t2) => (t1._1, t1._2 + t2._2, t1._3 + t2._3))

      collector.collect((str, sum / size))
      val endTick = System.currentTimeMillis()

      println("### Iterator: " + size + ", time: " + (endTick - startTick))

      val pw = new PrintWriter(new FileOutputStream(new File(computeStartFName), true), true)
      pw.println(s"${timeWindow.maxTimestamp()},$startTick,$endTick,${size}")
    }

    def fairlyComplexFunction = (key: Tuple, timeWindow: TimeWindow, iterator: Iterable[(String, Int)],
                                 collector: Collector[(String, Int)]) => {
      val startTick = System.currentTimeMillis()
      // iterator shall never be called more than once
      val result = iterator.map(p => {
        val startTick = System.nanoTime()
        while(System.nanoTime() - startTick <  360) {}
        p
      }).reduce((p1, p2) => (p1._1, p1._2 + p2._2))
      collector.collect(result)
      val endTick = System.currentTimeMillis()

      println("### Iterator: " + result._2 + ", time: " + (endTick - startTick))

      val pw = new PrintWriter(new FileOutputStream(new File(computeStartFName), true), true)
      pw.println(s"${timeWindow.maxTimestamp()},$startTick,$endTick,${result._2}")
    }

    def complexFunction = (key: Tuple, timeWindow: TimeWindow, iterator: Iterable[(String, Int)],
                                 collector: Collector[(String, Int)]) => {
      val startTick = System.currentTimeMillis()
      // iterator shall never be called more than once
      val (str, list) = iterator.foldLeft(("", List.empty[Complex]))((acc, p) => (p._1, acc._2 :+ Complex(p._2)))

      // add zero padding if list size not power of 2
      padder(list)
      FFT.fft(list).foreach(c => collector.collect((str, c.re.toInt)))
      val endTick = System.currentTimeMillis()

      println("### Iterator: " + list.size + ", time: " + (endTick - startTick))

      val pw = new PrintWriter(new FileOutputStream(new File(computeStartFName), true), true)
      pw.println(s"${timeWindow.maxTimestamp()},$startTick,$endTick,${list.size}")
    }


    def function = complexity match {
      case 0 => simpleFunction
      case 1 => fairlyComplexFunction
      case 2 => complexFunction
    }

    // thumbling window
    stream.keyBy(1).timeWindow(Time.of(windowDurationSec, TimeUnit.SECONDS))
      .allowedLateness(Time.of(TimeUnit.SECONDS.toNanos(windowDurationSec) * numberOfPastWindows - 1,
        TimeUnit.NANOSECONDS))
      .trigger(trigger3).apply(function)


    // sliding window
//    stream.keyBy(1)
//      .timeWindow(Time.of(windowDurationSec, TimeUnit.SECONDS), Time.of(slideDurationSec, TimeUnit.SECONDS))
//      .allowedLateness(Time.of(TimeUnit.SECONDS.toNanos(windowDurationSec) * numberOfPastWindows - 1,
//        TimeUnit.NANOSECONDS)).trigger(trigger3).apply(function);


    val result = env.execute()

//    val pw = new PrintWriter(new FileOutputStream(new File("runtime.txt"), true))
//    pw.println(result.getNetRuntime(TimeUnit.SECONDS))
//    pw.close()
  }
}