package io.hydrosphere.mist.worker

import java.util.concurrent.Executors.newFixedThreadPool
import akka.actor.{Actor, ActorLogging, Address, Props}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import io.hydrosphere.mist.Messages._
import io.hydrosphere.mist.contexts.{ContextBuilder, ContextWrapper}
import io.hydrosphere.mist.jobs.FullJobConfiguration
import io.hydrosphere.mist.jobs.runners.Runner
import io.hydrosphere.mist.{Constants, MistConfig}
import org.joda.time.DateTime
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService, Future, Promise}
import scala.util.{Failure, Random, Success}

class ContextNode(namespace: String) extends Actor with ActorLogging{

  implicit val executionContext: ExecutionContextExecutorService = ExecutionContext.fromExecutorService(newFixedThreadPool(MistConfig.Settings.threadNumber))

  private val cluster = Cluster(context.system)

  private val serverAddress = Random.shuffle[String, List](MistConfig.Akka.Worker.serverList).head + "/user/" + Constants.Actors.workerManagerName
  private val serverActor = cluster.system.actorSelection(serverAddress)

  val nodeAddress: Address = cluster.selfAddress

  lazy val contextWrapper: ContextWrapper = ContextBuilder.namedSparkContext(namespace)

  override def preStart(): Unit = {
    serverActor ! WorkerDidStart(namespace, cluster.selfAddress.toString)
    cluster.subscribe(self, InitialStateAsEvents, classOf[MemberEvent], classOf[UnreachableMember])
  }

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
  }

  lazy val jobDescriptions: ArrayBuffer[JobDescription] = ArrayBuffer.empty[JobDescription]

  type NamedActors = (JobDescription,  () => Unit)
  lazy val namedJobCancellations = ArrayBuffer.empty[NamedActors]

  override def receive: Receive = {

    case jobRequest: FullJobConfiguration =>
      log.info(s"[WORKER] received JobRequest: $jobRequest")
      val originalSender = sender

      lazy val runner = Runner(jobRequest, contextWrapper)

      def getUID: () => String = { () => {runner.id} }

      val jobDescription = new JobDescription( getUID,
        new DateTime().toString,
        jobRequest.namespace,
        jobRequest.externalId,
        jobRequest.route
      )

      def cancellable[T](f: Future[T])(cancellationCode: => Unit): (() => Unit, Future[T]) = {
        val p = Promise[T]
        val first = Future firstCompletedOf Seq(p.future, f)
        val cancellation: () => Unit = {
          () =>
            first onFailure { case _ => cancellationCode }
            p failure new Exception
        }
        (cancellation, first)
      }

      val runnerFuture: Future[Either[Map[String, Any], String]] = Future {
        if(MistConfig.Contexts.timeout(jobRequest.namespace).isFinite()) {
          serverActor ! AddJobToRecovery(runner.id, runner.configuration)
        }
        log.info(s"${jobRequest.namespace}#${runner.id} is running")

        runner.run()
      }(executionContext)

      val (cancel, cancellableRunnerFuture) = cancellable(runnerFuture) {
        jobDescriptions -= jobDescription
        if (MistConfig.Contexts.timeout(jobRequest.namespace).isFinite()) {
          serverActor ! RemoveJobFromRecovery(runner.id)
        }
        runner.stop()
        originalSender ! Right("Canceled")
      }

      jobDescriptions += jobDescription

      namedJobCancellations += ((jobDescription, cancel))

      cancellableRunnerFuture
        .recover {
          case e: Throwable => originalSender ! Right(e.toString)
        }(ExecutionContext.global)
        .andThen {
          case _ =>
            jobDescriptions -= jobDescription
            if (MistConfig.Contexts.timeout(jobRequest.namespace).isFinite()) {
              serverActor ! RemoveJobFromRecovery(runner.id)
            }
        }(ExecutionContext.global)
        .andThen {
          case Success(result: Either[Map[String, Any], String]) => originalSender ! result
          case Failure(error: Throwable) => originalSender ! Right(error.toString)
        }(ExecutionContext.global)

    case ListJobs =>
      val jobDescriptionsSerializable = jobDescriptions.map(
        job => new JobDescriptionSerializable(job.uid(),
          job.time, job.namespace,
          job.externalId,
          job.router)).toList

      sender ! jobDescriptionsSerializable

    case StopJob(message) =>
      val originalSender = sender
      val future: Future[List[String]] = Future {
        val stopResponse = ArrayBuffer.empty[String]
        if(message.contains(Constants.CLI.stopJobMsg)) {
          jobDescriptions.foreach {
            case jobDescription: JobDescription => {
              if(message.substring(Constants.CLI.stopJobMsg.length).contains(jobDescription.externalId.getOrElse("None"))
                || message.substring(Constants.CLI.stopJobMsg.length).contains(jobDescription.uid())) {
                stopResponse += s"Job ${jobDescription.externalId.getOrElse("")} ${jobDescription.uid()}" +
                  s" is scheduled for shutdown. It may take a while."
                namedJobCancellations
                  .filter(namedJobCancellation => namedJobCancellation._1.uid() == jobDescription.uid())
                  .foreach(namedJobCancellation => namedJobCancellation._2())
              }
            }
          }
        }
        stopResponse.toList
      }

      future onComplete {
        case Success(result: List[String]) => originalSender ! result
        case Failure(error: Throwable) => originalSender ! error
      }


    case MemberExited(member) =>
      if (member.address == cluster.selfAddress) {
        //noinspection ScalaDeprecation
        cluster.system.shutdown()
      }

    case MemberRemoved(member, _) =>
      if (member.address == cluster.selfAddress) {
        sys.exit(0)
      }
  }
}

object ContextNode {
  def props(namespace: String): Props = Props(classOf[ContextNode], namespace)
}
