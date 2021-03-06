package akka.stream

import akka.stream.scaladsl.{Flow, _}
import akka.testkit.AkkaSpec
import scala.collection.immutable
import scala.concurrent.{Await, Future}
import akka.NotUsed
import akka.stream.testkit.TestSubscriber.ManualProbe
import akka.stream.testkit.TestSubscriber
import scala.concurrent.duration._
class GraphDSLDocSpec extends AkkaSpec {

  implicit val ec = system.dispatcher

  implicit val materializer = ActorMaterializer()

  "Fan-out Test" in {
    //format: OFF
    //#simple-graph-dsl
    val g = RunnableGraph.fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
      import GraphDSL.Implicits._
      val in = Source(1 to 6)
      val out = Sink.foreach(println)

      val unzipWith: FanOutShape2[Int, Int, Int] = builder.add(UnzipWith[Int,Int,Int]((n: Int) => (n, n)))
      in ~> unzipWith.in
      unzipWith.out0  ~> out
      unzipWith.out1  ~> out

      val unzip = builder.add(Unzip[Int, String]())
      Source(List((1 , "a"), 2 → "b", 3 → "c")) ~> unzip.in
      unzip.out0 ~> out
      unzip.out1 ~> out

      val bcast = builder.add(Broadcast[Int](2)) //1个输入输出 2 次
      in ~> bcast.in
      bcast.out(0) ~> Sink.foreach(println)
      bcast.out(1) ~> Sink.foreach(println)

      //val merge2 = builder.add(Merge[Int](2))
      val balance = builder.add(Balance[Int](2)) //1个输入输出 1 次（选择其中一条路线）
      in ~> balance  ~> out
            balance  ~> out

      ClosedShape
    })
    g.run()
  }

  "Fan-In Test" in {
    //format: OFF
    //#simple-graph-dsl
    val g = RunnableGraph.fromGraph(GraphDSL.create() { implicit b: GraphDSL.Builder[NotUsed] =>
      import GraphDSL.Implicits._
      val source1 = Source(0 to 3)
      val source2 = Source(4 to 9)
      val out = Sink.foreach(println)


     /* val m1 = b.add(Merge[Int](2))
      source1 ~> m1.in(0)
      source2 ~> m1.in(1)
      m1.out ~> Flow[Int].map(_ + 1) ~> out //输出1 ~ 10*/

     /* val zip = b.add(Zip[Int, String]())
      Source(1 to 4) ~> zip.in0
      Source(List("A", "B", "C", "D", "E", "F")) ~> zip.in1
      zip.out ~> out // 输出 （1，A）...(5,D)*/

      /*val zipWith = b.add(ZipWith[Int, Int, Int]((_: Int) + (_: Int)))
      Source(1 to 4) ~> zipWith.in0
      Source(2 to 10) ~> zipWith.in1
      zipWith.out ~> out // 输出： 1 + 2， 3 + 4, ...., 4 + 5*/

     /* val numElements = 10
      val preferred = Source(Stream.fill(numElements)(1))
      val aux1 = Source(Stream.fill(numElements)(2))
      val aux2 = Source(Stream.fill(numElements)(3))
      val mergeF = b.add(MergePreferred[Int](2))
      mergeF.out.grouped(numElements) ~> out
      preferred ~> mergeF.preferred  //同时进入时，优先输出preferred中的数字
      aux1 ~> mergeF.in(0)
      aux2 ~> mergeF.in(1)*/
      /*
      输出：
      Vector(2, 3, 1, 1, 2, 3, 1, 1, 1, 1)
      Vector(1, 1, 1, 1, 2, 3, 2, 3, 2, 3)
      Vector(2, 3, 2, 3, 2, 3, 2, 3, 2, 3)
      */

     /* val s1 = Source(1 to 3)
      val s2 = Source(4 to 6)
      val s3 = Source(7 to 9)

      val priorities = Seq(100, 10, 1) // 当 3个元素都准备输出时，按照概率输出
      val mergeP = b.add(MergePrioritized[Int](priorities))
      val delayFirst = b.add(Flow[Int].initialDelay(50.millis))
      s1 ~> mergeP.in(0)
      s2 ~> mergeP.in(1)
      s3 ~> mergeP.in(2)
      mergeP.out  ~> delayFirst ~> out*/

      val concat1 = b add Concat[Int]()
      val concat2 = b add Concat[Int]()
      Source(List.empty[Int]) ~> concat1.in(0)
      Source(1 to 4) ~> concat1.in(1)

      concat1.out ~> concat2.in(0)
      Source(5 to 10) ~> concat2.in(1)

      concat2.out ~> out  //依次输出1 ~ 10

      ClosedShape
    })
    g.run()
    Thread.sleep(2000)
  }




  "build simple graph" in {
    //format: OFF
    //#simple-graph-dsl
    val g = RunnableGraph.fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
      import GraphDSL.Implicits._
      val in = Source(1 to 2)
      val out = Sink.ignore

      val bcast = builder.add(Broadcast[Int](2))
      val merge = builder.add(Merge[Int](2))

      val f1, f2, f3, f4 = Flow[Int].map(_ + 10)

      in ~> f1 ~> bcast ~> f2 ~> merge ~> f3 ~> out
      bcast ~> f4 ~> merge
      ClosedShape
    })
    //#simple-graph-dsl
    //format: ON

    //#simple-graph-run
    g.run()
    //#simple-graph-run
  }

  "flow connection errors" in {
    intercept[IllegalStateException] {
      //#simple-graph
      RunnableGraph.fromGraph(GraphDSL.create() { implicit builder ⇒
        import GraphDSL.Implicits._
        val source1 = Source(1 to 10)
        val source2 = Source(1 to 10)

        val zip = builder.add(Zip[Int, Int]())

        source1 ~> zip.in0
        source2 ~> zip.in1
        // unconnected zip.out (!) => "must have at least 1 outgoing edge"
        ClosedShape
      })
      //#simple-graph
    }.getMessage should include("ZipWith2.out")
  }

  "reusing a flow in a graph" in {
    //#graph-dsl-reusing-a-flow

    val topHeadSink = Sink.head[Int]
    val bottomHeadSink = Sink.head[Int]
    val sharedDoubler = Flow[Int].map(_ * 2)

    //#graph-dsl-reusing-a-flow

    // format: OFF
    val g =
    //#graph-dsl-reusing-a-flow
      RunnableGraph.fromGraph(GraphDSL.create(topHeadSink, bottomHeadSink)((_, _)) { implicit builder =>
        (topHS, bottomHS) =>
          import GraphDSL.Implicits._
          val broadcast = builder.add(Broadcast[Int](2))
          Source.single(1) ~> broadcast.in

          broadcast.out(0) ~> sharedDoubler ~> topHS.in
          broadcast.out(1) ~> sharedDoubler ~> bottomHS.in
          ClosedShape
      })
    //#graph-dsl-reusing-a-flow
    // format: ON
    val (topFuture, bottomFuture) = g.run()
    Await.result(topFuture, 300.millis) shouldEqual 2
    Await.result(bottomFuture, 300.millis) shouldEqual 2
  }
  "building a reusable component" in {

    //#graph-dsl-components-shape
    // A shape represents the input and output ports of a reusable
    // processing module
    case class PriorityWorkerPoolShape[In, Out](
                                                 jobsIn:         Inlet[In],
                                                 priorityJobsIn: Inlet[In],
                                                 resultsOut:     Outlet[Out]) extends Shape {

      // It is important to provide the list of all input and output
      // ports with a stable order. Duplicates are not allowed.
      override val inlets: immutable.Seq[Inlet[_]] =
      jobsIn :: priorityJobsIn :: Nil
      override val outlets: immutable.Seq[Outlet[_]] =
        resultsOut :: Nil

      // A Shape must be able to create a copy of itself. Basically
      // it means a new instance with copies of the ports
      override def deepCopy() = PriorityWorkerPoolShape(
        jobsIn.carbonCopy(),
        priorityJobsIn.carbonCopy(),
        resultsOut.carbonCopy())

    }
    //#graph-dsl-components-shape

    //#graph-dsl-components-create
    object PriorityWorkerPool {
      def apply[In, Out](worker:  Flow[In, Out, Any], workerCount: Int): Graph[PriorityWorkerPoolShape[In, Out], NotUsed] = {

        GraphDSL.create() { implicit b ⇒
          import GraphDSL.Implicits._

          val priorityMerge = b.add(MergePreferred[In](1))
          val balance = b.add(Balance[In](workerCount))
          val resultsMerge = b.add(Merge[Out](workerCount))

          // After merging priority and ordinary jobs, we feed them to the balancer
          priorityMerge ~> balance

          // Wire up each of the outputs of the balancer to a worker flow
          // then merge them back
          for (i ← 0 until workerCount)
            balance.out(i) ~> worker ~> resultsMerge.in(i)

          // We now expose the input ports of the priorityMerge and the output
          // of the resultsMerge as our PriorityWorkerPool ports
          // -- all neatly wrapped in our domain specific Shape
          PriorityWorkerPoolShape(
            jobsIn = priorityMerge.in(0),
            priorityJobsIn = priorityMerge.preferred,
            resultsOut = resultsMerge.out)
        }

      }

    }
    //#graph-dsl-components-create

    //def println(s: Any): Unit = ()

    //#graph-dsl-components-use
    val worker1 = Flow[String].map("step 1 " + _)
    val worker2 = Flow[String].map("step 2 " + _)

    RunnableGraph.fromGraph(GraphDSL.create() { implicit b ⇒
      import GraphDSL.Implicits._

      val priorityPool1 = b.add(PriorityWorkerPool(worker1, 4))
      //val priorityPool2 = b.add(PriorityWorkerPool(worker2, 2))

      Source(1 to 2).map("job: " + _) ~> priorityPool1.jobsIn
      Source(1 to 2).map("priority job: " + _) ~> priorityPool1.priorityJobsIn

      //priorityPool1.resultsOut ~> priorityPool2.jobsIn
      //Source(1 to 10).map("one-step, priority " + _) ~> priorityPool2.priorityJobsIn

     // priorityPool2.resultsOut ~> Sink.foreach(println)
      ClosedShape
    }).run()
    //#graph-dsl-components-use

    //#graph-dsl-components-shape2
    import FanInShape.{Init, Name}

    class PriorityWorkerPoolShape2[In, Out](_init: Init[Out] = Name("PriorityWorkerPool"))
      extends FanInShape[Out](_init) {
      protected override def construct(i: Init[Out]) = new PriorityWorkerPoolShape2(i)

      val jobsIn = newInlet[In]("jobsIn")
      val priorityJobsIn = newInlet[In]("priorityJobsIn")
      // Outlet[Out] with name "out" is automatically created
    }
    //#graph-dsl-components-shape2

  }

  "access to materialized value" in {
    //#graph-dsl-matvalue
    import GraphDSL.Implicits._
    val foldFlow: Flow[Int, Int, Future[Int]] = Flow.fromGraph(GraphDSL.create(Sink.fold[Int, Int](0)(_ + _)) { implicit builder ⇒ fold ⇒
      FlowShape(fold.in, builder.materializedValue.mapAsync(4)(identity).outlet)
    })
    //#graph-dsl-matvalue

    Await.result(Source(1 to 10).via(foldFlow).runWith(Sink.head), 3.seconds) should ===(55)

    //#graph-dsl-matvalue-cycle
    import GraphDSL.Implicits._
    // This cannot produce any value:
    val cyclicFold: Source[Int, Future[Int]] = Source.fromGraph(GraphDSL.create(Sink.fold[Int, Int](0)(_ + _)) { implicit builder ⇒ fold ⇒
      // - Fold cannot complete until its upstream mapAsync completes
      // - mapAsync cannot complete until the materialized Future produced by
      //   fold completes
      // As a result this Source will never emit anything, and its materialited
      // Future will never complete
      builder.materializedValue.mapAsync(4)(identity) ~> fold
      SourceShape(builder.materializedValue.mapAsync(4)(identity).outlet)
    })
    //#graph-dsl-matvalue-cycle
  }

}
