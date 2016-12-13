package ckite.states

import java.util.Random
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ ScheduledFuture, TimeUnit }

import ckite._
import ckite.rpc._
import ckite.util.CKiteConversions.fromFunctionToRunnable
import ckite.util.{ ConcurrencySupport, Logging }

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Try

// Y.V
import java.lang.Math._

case class Follower(consensus: Consensus, membership: Membership, log: RLog, term: Int, leaderAnnouncer: LeaderAnnouncer, vote: Option[String]) extends State(vote) with Logging {

  // Y.V keep count
  var appendCount: Int = 0;
  var workRate: Double = 0;

  private val electionTimeout = new ElectionTimeout(consensus, term)

  override def begin() = {
    resetElectionTimeout() //start the election timeout if no communication from the Leader
  }

  override def onAppendEntries(appendEntries: AppendEntries): Future[AppendEntriesResponse] = {
    // Y.V Check if Follower is limp
    //workRate += System.nanoTime() - membership.myStart
    // membership.setStart
    //appendCount = appendCount + 1
    //var traffic = workRate / appendCount
    var traffic: Double = 2500 / (100 * pow(10, 9))
    var ideal_mLatency = 26315991; // mean latency of healthy follower
    var mLatency: Double = ideal_mLatency * 6;
    var r: Double = pow(mLatency.toDouble, -1) / pow(ideal_mLatency.toDouble, -1)
    var criticalNum: Double = 1 - sqrt((r * (1 + r)) / (1 + pow(r, 2))) // EQ 15
    var mu: Double = pow(mLatency.toDouble, -1) + pow(ideal_mLatency.toDouble, -1)

    if ((s"${membership.myId}") == "192.168.1.11:9093") {
      //    println("==============================================================")
      //  println(s"Y.V Limp Follower Test, \t traffic = ${traffic}  <  ${mu * criticalNum}  with mu = ${mu} and CN = ${criticalNum} wrk= ${workRate} appE = ${appendCount}")
      //println("==============================================================")
      if (traffic < (mu * criticalNum)) {
        println("==============================================================")
        println(s"Y.V Limp Follower Detected, \t traffic = ${traffic} ")
        println("==============================================================")

        while (true) // Sleep forever to simulate failure
        {
          Thread.sleep(10000000);
          java.lang.System.exit(1)
        }
      }
    }

    appendEntries.term match {
      case leaderTerm if leaderTerm < term  ⇒ rejectOldLeader(appendEntries)
      case leaderTerm if leaderTerm > term  ⇒ stepDownAndPropagate(appendEntries)
      case leaderTerm if leaderTerm == term ⇒ receivedAppendEntriesFromLeader(appendEntries)
    }
  }

  override def onRequestVote(requestVote: RequestVote): Future[RequestVoteResponse] = {
    requestVote.term match {
      case requestTerm if requestTerm < term  ⇒ rejectOldCandidate(requestVote.memberId)
      case requestTerm if requestTerm > term  ⇒ stepDownAndPropagate(requestVote)
      case requestTerm if requestTerm == term ⇒ analyzeRequestVote(requestVote)
    }
  }

  private def receivedAppendEntriesFromLeader(appendEntries: AppendEntries): Future[AppendEntriesResponse] = {
    Try {
      resetElectionTimeout() //Leader is alive. God save the Leader!
      announceLeader(appendEntries.leaderId)
      append(appendEntries)
    }.recover {
      case reason: Exception ⇒ rejectAppendEntries(appendEntries, reason.getMessage)
    }.get
  }

  private def analyzeRequestVote(requestVote: RequestVote): Future[RequestVoteResponse] = {
    val couldGrantVote = checkGrantVotePolicy(requestVote)
    if (couldGrantVote) {
      if (tryGrantVoteTo(requestVote.memberId)) {
        logger.debug(s"Granting vote to ${requestVote.memberId} in term[${term}]")
        resetElectionTimeout()
        consensus.persistState()
        grantVote()
      } else {
        rejectVote(requestVote.memberId, s"already voted for ${votedFor.get()}")
      }
    } else {
      rejectVote(requestVote.memberId, s"not granted vote policy")
    }
  }

  private def tryGrantVoteTo(member: String): Boolean = {
    votedFor.compareAndSet(None, Some(member)) || votedFor.get().equals(Some(member))
  }

  override def onCommand[T](command: Command): Future[T] = leaderAnnouncer.onLeader(_.forwardCommand[T](command))

  def stepDownAndPropagate(installSnapshot: InstallSnapshot): Future[InstallSnapshotResponse] = {
    stepDown(installSnapshot.term)
    consensus.onInstallSnapshot(installSnapshot)
  }

  override def onInstallSnapshot(installSnapshot: InstallSnapshot): Future[InstallSnapshotResponse] = {
    installSnapshot.term match {
      case leaderTerm if leaderTerm < term  ⇒ Future.successful(InstallSnapshotResponse(REJECTED))
      case leaderTerm if leaderTerm > term  ⇒ stepDownAndPropagate(installSnapshot)
      case leaderTerm if leaderTerm == term ⇒ log.installSnapshot(installSnapshot.snapshot).map(_ ⇒ InstallSnapshotResponse(ACCEPTED))
    }
  }

  private def resetElectionTimeout() = electionTimeout restart

  private def append(appendEntries: AppendEntries): Future[AppendEntriesResponse] = {
    log.tryAppend(appendEntries) map { success ⇒
      AppendEntriesResponse(term, success)
    }
  }

  private def announceLeader(leaderId: String) {
    if (leaderAnnouncer.announce(leaderId)) {
      logger.info("Following {} in term[{}]", leaderId, term)
    }
  }

  private def checkGrantVotePolicy(requestVote: RequestVote) = {
    (hastNotVotedYet() || hasVotedFor(requestVote.memberId)) && isMuchUpToDate(requestVote)
  }

  def hasVotedFor(member: String): Boolean = vote.get == member

  def hastNotVotedYet(): Boolean = !votedFor.get().isDefined

  private def isMuchUpToDate(requestVote: RequestVote) = {
    val lastLogEntry = log.lastEntry
    lastLogEntry.isEmpty || (requestVote.lastLogTerm >= lastLogEntry.get.term && requestVote.lastLogIndex >= lastLogEntry.get.index)
  }

  override def stop(stopTerm: Int) = {
    if (stopTerm > term) {
      electionTimeout stop
    }
  }

  override val toString = s"Follower[$term]"

}

class ElectionTimeout(consensus: Consensus, term: Int) extends Logging {

  import ckite.states.ElectionTimeout._

  private val scheduledFuture = new AtomicReference[ScheduledFuture[_]]()

  def restart = {
    stop
    start
  }

  private def start = {
    val electionTimeout = randomTimeout
    logger.trace(s"New timeout is $electionTimeout ms")
    val task: Runnable = () ⇒ {
      logger.debug("Timeout reached! Time to elect a new leader")
      consensus.becomeCandidate(term + 1)
    }
    val future = electionTimeoutScheduler.schedule(task, electionTimeout, TimeUnit.MILLISECONDS)
    val previousFuture = scheduledFuture.getAndSet(future)
    cancel(previousFuture)
  }

  private def randomTimeout = {
    val conf = consensus.configuration
    val diff = conf.maxElectionTimeout - conf.minElectionTimeout
    conf.minElectionTimeout + random.nextInt(if (diff > 0) diff.toInt else 1)
  }

  def stop() = {
    val future = scheduledFuture.get()
    cancel(future)
  }

  private def cancel(future: java.util.concurrent.Future[_]) = if (future != null) future.cancel(false)

}

object ElectionTimeout extends ConcurrencySupport {
  private val random = new Random()
  private val electionTimeoutScheduler = scheduler(s"ElectionTimeout-worker")
}

trait NoElectionTimeout extends State {
  override def begin() = {}
}