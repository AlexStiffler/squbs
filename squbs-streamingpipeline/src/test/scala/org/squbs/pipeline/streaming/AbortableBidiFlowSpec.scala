/*
 * Copyright 2015 PayPal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.squbs.pipeline.streaming

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.{BidiShape, ActorMaterializer}
import akka.stream.scaladsl._
import akka.testkit.TestKit
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import org.scalatest.{Matchers, FlatSpecLike}
import org.squbs.pipeline.streaming.Timeouts._

import scala.concurrent.{Future, Await}

class AbortableBidiFlowSpec extends TestKit(ActorSystem("AbortableBidiFlowSpec", AbortableBidiFlowSpec.config))
  with FlatSpecLike with Matchers {

  implicit val am = ActorMaterializer()
  val pipelineExtension = PipelineExtension(system)
  val dummyEndpoint = Flow[RequestContext].map { rc =>
    rc.copy(response = Some(HttpResponse(entity = s"${rc.request.headers.sortBy(_.name).mkString(",")}")))
  }

  it should "run the entire flow" in {
    val pipelineFlow = pipelineExtension.getFlow((Some("dummyFlow2"), Some(true)))

    pipelineFlow should not be (None)
    val httpFlow = pipelineFlow.get.join(dummyEndpoint)
    val future = Source.single(RequestContext(HttpRequest(), 0)).runWith(httpFlow.toMat(Sink.head)(Keep.right))
    val rc = Await.result(future, awaitMax)

    rc.response should not be (None)
    val httpResponse = rc.response.get
    httpResponse.headers.sortBy(_.name) should equal(Seq(RawHeader("keyE", "valE"),
                                                         RawHeader("keyC2", "valC2"),
                                                         RawHeader("keyF", "valF"),
                                                         RawHeader("keyPreOutbound", "valPreOutbound"),
                                                         RawHeader("keyPostOutbound", "valPostOutbound")).sortBy(_.name))
    import system.dispatcher
    val actualEntity = Await.result(httpResponse.entity.dataBytes.runFold(ByteString(""))(_ ++ _) map(_.utf8String), awaitMax)
    val expectedEntity = Seq( RawHeader("keyA", "valA"),
                              RawHeader("keyB", "valB"),
                              RawHeader("keyC1", "valC1"),
                              RawHeader("keyD", "valD"),
                              RawHeader("keyPreInbound", "valPreInbound"),
                              RawHeader("keyPostInbound", "valPostInbound")).sortBy(_.name).mkString(",")
    actualEntity should equal(expectedEntity)
  }

  it should "bypass rest of the flow when HttpResponse is set in RequestContext" in {
    val pipelineFlow = pipelineExtension.getFlow((Some("dummyFlow2"), Some(true)))

    pipelineFlow should not be (None)
    val httpFlow = pipelineFlow.get.join(dummyEndpoint)
    val abortHeaders = RawHeader("abort", "dummyValue") :: Nil
    val future: Future[RequestContext] = Source.single(RequestContext(HttpRequest(headers = abortHeaders), 0)).runWith(httpFlow.toMat(Sink.head)(Keep.right))
    val rc = Await.result(future, awaitMax)

    rc.response should not be (None)
    val httpResponse = rc.response.get

    httpResponse.headers.sortBy(_.name) should equal(Seq( RawHeader("keyC2", "valC2"),
                                                          RawHeader("keyF", "valF"),
                                                          RawHeader("keyPreOutbound", "valPreOutbound")).sortBy(_.name))

    import system.dispatcher
    val actualEntity = Await.result(httpResponse.entity.dataBytes.runFold(ByteString(""))(_ ++ _) map(_.utf8String), awaitMax)
    val expectedEntity = Seq( RawHeader("abort", "dummyValue"),
                              RawHeader("keyA", "valA"),
                              RawHeader("keyB", "valB"),
                              RawHeader("keyC1", "valC1"),
                              RawHeader("keyPreInbound", "valPreInbound")).sortBy(_.name).mkString(",")
    actualEntity should equal(expectedEntity)
  }
}

object AbortableBidiFlowSpec {
  val config = ConfigFactory.parseString(
    s"""
       |dummyFlow2 {
       |  type = squbs.pipelineflow
       |  factory = org.squbs.pipeline.streaming.DummyFlow2
       |}
       |
       |preFlow {
       |  type = squbs.pipelineflow
       |  factory = org.squbs.pipeline.streaming.PreFlow
       |}
       |
       |postFlow {
       |  type = squbs.pipelineflow
       |  factory = org.squbs.pipeline.streaming.PostFlow
       |}
       |
       |squbs.pipeline.streaming.defaults {
       |  pre-flow =  preFlow
       |  post-flow = postFlow
       |}
    """.stripMargin
  )
}

class DummyFlow2 extends PipelineFlowFactory {

  override def create: PipelineFlow = {

    BidiFlow.fromGraph(GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._

      val stageA = b.add(Flow[RequestContext].map { rc => rc.addRequestHeaders(RawHeader("keyA", "valA")) })
      val stageB = b.add(Flow[RequestContext].map { rc => rc.addRequestHeaders(RawHeader("keyB", "valB")) })
      val stageC = b.add(dummyBidi abortable)
      val stageD = b.add(Flow[RequestContext].map { rc => rc.addRequestHeaders(RawHeader("keyD", "valD")) })
      val stageE = b.add(Flow[RequestContext].map { rc => rc.addResponseHeaders(RawHeader("keyE", "valE")) })
      val stageF = b.add(Flow[RequestContext].map { rc => rc.addResponseHeaders(RawHeader("keyF", "valF")) })

      stageA ~> stageB ~> stageC.in1
                          stageC.out1 ~> stageD
                          stageC.in2  <~ stageE
      stageF <~           stageC.out2

      BidiShape(stageA.in, stageD.out, stageE.in, stageF.out)
    })
  }

  val dummyAborterFlow = Flow[RequestContext].map { rc =>
    rc.request.headers.find(_.is("abort")) match {
      case Some(_) => rc.copy(response = Some(HttpResponse(entity = s"${rc.request.headers.sortBy(_.name).mkString(",")}")))
      case _ => rc
    }
  }

  val dummyBidi = BidiFlow.fromGraph(GraphDSL.create() { implicit b =>
    val requestFlow = b.add(Flow[RequestContext].map { rc => rc.addRequestHeaders(RawHeader("keyC1", "valC1")) }.via(dummyAborterFlow) )
    val responseFlow = b.add(Flow[RequestContext].map { rc => rc.addResponseHeaders(RawHeader("keyC2", "valC2")) } )
    BidiShape.fromFlows(requestFlow, responseFlow)
  })
}