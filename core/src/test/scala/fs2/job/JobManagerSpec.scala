/*
 * Copyright 2014–2018 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fs2
package job

import scala.Predef.String

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Either, Left, Right}
import scala.{Int, List, Long, Unit}

import cats.Eq
import cats.effect.IO
import cats.effect.concurrent.Ref
import cats.instances.string._
import fs2.concurrent.SignallingRef
import org.specs2.mutable._

object JobManagerSpec extends Specification {
  implicit val cs = IO.contextShift(ExecutionContext.global)
  implicit val timer = IO.timer(ExecutionContext.global)

  def await(l: Long): Stream[IO, Unit] =
    Stream.sleep(l.seconds)

  def await1: Stream[IO, Unit] =
    Stream.sleep(100.milliseconds)

  def mkJobManager: Stream[IO, JobManager[IO, Int, String]] =
    JobManager[IO, Int, String]()

  "Job manager" should {
    "submit a job" in {
      val JobId = 1

      val stream = await(1).as(Right(1)) ++ Stream(2, 3, 4).map(Right(_)).covary[IO]
      val notification = Stream(Left("done")).covary[IO]

      val job = Job[IO, Int, String, Int](JobId, stream ++ notification)

      val (submitResult, ids, status) = (for {
        mgr <- mkJobManager
        submitResult <- Stream.eval(mgr.submit(job))
        _ <- await(1)
        ids <- Stream.eval(mgr.jobIds)
        status <- Stream.eval(mgr.status(JobId))
      } yield (submitResult, ids, status)).compile.lastOrError.unsafeRunSync

      submitResult must beTrue
      ids must_== List(JobId)
      status must beSome(Status.Running)
    }

    "executes a job to completion" in {
      val JobId = 42

      def jobStream(ref: Ref[IO, String]): Stream[IO, Either[String, Int]] =
        Stream.eval(ref.set("Started")).as(Right(1)) ++ await(2).as(Right(2)) ++ Stream.eval(ref.set("Finished")).as(Right(3))

      val (refAfterStart, refAfterRun) = (for {
        mgr <- mkJobManager
        ref <- Stream.eval(Ref[IO].of("Not started"))
        job = Job[IO, Int, String, Int](JobId, jobStream(ref))

        submitStatus <- Stream.eval(mgr.submit(job))
        _ <- await(1)
        refAfterStart <- Stream.eval(ref.get)
        _ <- await(3)
        refAfterRun <- Stream.eval(ref.get)
      } yield (refAfterStart, refAfterRun)).compile.lastOrError.unsafeRunSync

      refAfterStart mustEqual "Started"
      refAfterRun mustEqual "Finished"
    }

    "cancel a job" in {
      val JobId = 42

      def jobStream(ref: SignallingRef[IO, String]): Stream[IO, Either[String, Int]] =
        Stream.eval(ref.set("Started")).as(Right(1)) ++ await1.as(Right(2)) ++ Stream.eval(ref.set("Working")).as(Right(3)) ++ await1.as(Right(4)) ++ Stream.eval(ref.set("Finished")).as(Right(5))

      val (statusBeforeCancel, refBeforeCancel, refAfterCancel) = (for {
        mgr <- mkJobManager
        ref <- Stream.eval(SignallingRef[IO, String]("Not started"))
        job = Job(JobId, jobStream(ref))

        _ <- Stream.eval(mgr.submit(job))
        _ <- Stream.eval(latchGet(ref, "Started"))

        statusBeforeCancel <- Stream.eval(mgr.status(JobId))
        refBeforeCancel <- Stream.eval(ref.get)

        _ <- Stream.eval(latchGet(ref, "Working"))
        _ <- Stream.eval(mgr.cancel(JobId))
        _ <- await1

        refAfterCancel <- Stream.eval(ref.get)
      } yield (statusBeforeCancel, refBeforeCancel, refAfterCancel)).compile.lastOrError.timeout(1.second).unsafeRunSync


      statusBeforeCancel must beSome(Status.Running)
      refBeforeCancel mustEqual "Started"
      refAfterCancel mustEqual "Working"
    }

    "emits notifications" in {
      val JobId = 42

      val jobStream: Stream[IO, Either[String, Int]] =
        Stream(Right(1), Right(2), Left("50%"), Right(3), Right(4), Left("100%")).covary[IO]

      val ns = (for {
        mgr <- mkJobManager
        submitStatus <- Stream.eval(mgr.submit(Job(JobId, jobStream)))
        _ <- await(1)

        // folds keeps pulling and blocks, even after the stream is done emitting
        ns <- mgr.notifications.take(2).fold(List[String]()) {
          case (acc, elem) => acc :+ elem
        }
      } yield ns).compile.lastOrError.unsafeRunSync

      ns mustEqual List("50%", "100%")
    }

    "tapped jobs can be canceled" in {
      val JobId = 42

      def jobStream(ref: Ref[IO, String]): Stream[IO, Either[String, Int]] =
        await(1).as(Right(1)) ++ Stream.eval(ref.set("Started")).as(Right(2)) ++ await(1).as(Right(3)) ++ Stream.eval(ref.set("Finished")).as(Right(4))

      val results = (for {
        mgr <- mkJobManager
        ref <- Stream.eval(Ref[IO].of("Not started"))
        tappedStream = mgr.tap(Job(JobId, jobStream(ref))).fold(List[Int]()) {
          case (acc, elem) => acc :+ elem
        }
        // sequence tapped stream manually
        _ <- tappedStream.concurrently(await(2) ++ Stream.eval(mgr.cancel(JobId)))
        results <- Stream.eval(ref.get)
      } yield results).compile.lastOrError.unsafeRunSync

      results mustEqual "Started"
    }
  }

  // blocks until s.get === expected
  def latchGet(s: SignallingRef[IO, String], expected: String): IO[Unit] =
    s.discrete.filter(Eq[String].eqv(_, expected)).take(1).compile.drain
}
