package ckite.states

import java.lang.Boolean
import java.util.concurrent.{ ConcurrentHashMap, TimeUnit }

import ckite._
import ckite.exception.LostLeadershipException
import ckite.rpc.LogEntry.{ Index, Term }
import ckite.rpc._
import ckite.stats.{ LeaderInfo, FollowerInfo }
import ckite.util.CKiteConversions.fromFunctionToRunnable
import ckite.util.ConcurrencySupport

import scala.collection.concurrent.TrieMap
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future, Promise }
import scala.util.{ Try, Failure, Success }

case class Leader(consensus: Consensus, membership: Membership, log: RLog, term: Term, leaderAnnouncer: LeaderAnnouncer) extends State(Some(membership.myId)) with ConcurrencySupport {

  // Y.V keep count
  var appendCount = 0;

  private val ReplicationTimeout = consensus.configuration.appendEntriesTimeout
  private val AppendEntriesTimeout = consensus.configuration.appendEntriesTimeout millis
  private val waitForLeaderTimeout = consensus.configuration.waitForLeaderTimeout millis
  private val scheduledHeartbeatsPool = scheduler("HeartbeatThread")
  private val followersStats = new ConcurrentHashMap[String, Long]()
  private val startTime = now()

  override def begin() = {
    if (term < consensus.term) {
      logger.debug(s"Can't be a Leader of term $term. Current term is ${consensus.term}")
      consensus.becomeFollower(consensus.term)
    } else {
      resetLastIndex()
      resetNextAndMatchIndexes()
      startBroadcasting()
      appendNoOp() andThen {
        case Success(_)      ⇒ announceLeadership()
        case Failure(reason) ⇒ logger.error("Failed to commit noop command", reason)
      }
    }
  }

  def startBroadcasting() {
    logger.debug("Start broadcasting...")
    scheduledHeartbeatsPool.scheduleAtFixedRate(() ⇒ {
      broadcast()
    }, 0, consensus.configuration.heartbeatsInterval, TimeUnit.MILLISECONDS)
  }

  private def broadcast(): Set[(RemoteMember, Future[AppendEntriesResponse])] = {
    logger.trace(s"Leader[$term] broadcasting AppendEntries")
    membership.remoteMembers map { member ⇒ (member, sendAppendEntries(member)) }
  }

  private def sendAppendEntries(member: RemoteMember): Future[AppendEntriesResponse] = {

    // Y.V Check if Leader is limp
    if ((s"${membership.myId}") == "192.168.1.10:9092")
      if (membership.mycount == 0)
        Thread.sleep(4000); // Sleep bootstrapped server

    membership.setcount(5)

    if ((s"${membership.myId}") == "192.168.1.11:9093") {
      var ideal_mLatency = 38457300;
      // mean latency of healthy leader
      var mLatency: Double = ideal_mLatency * 2;
      var r: Double = ideal_mLatency.toDouble / mLatency.toDouble
      appendCount = appendCount + 1;
      //println(s"appendCount = ${appendCount}")
      if (appendCount == 45) {
        if (r <= 0.5) {
          println("==============================================================")
          println(s"Y.V Limp Leader Detected, \t r = ${r} \t idealLat = ${ideal_mLatency} \t mLat = ${mLatency}")
          println("==============================================================")

          // Server is at least half as slow as a healthy server
          Thread.sleep(4000); // Sleep untill a new leader is elected

          // Force new leader election
          val currentTerm = consensus.term + 1
          receivedHigherTerm(currentTerm)

        }

      }
    }

    val request = createAppendEntriesFor(member)
    if (!request.entries.isEmpty) {
      logger.trace("Sending {} entries to {}", request.entries.size, member.id)
    }
    member.sendAppendEntries(request).map { response ⇒
      // Y.V  Leader Append Entries Starts, so set start time
      membership.setStart

      logger.trace(s"AppendEntries response ${response} from ${member.id}")
      if (response.term > term) {
        receivedHigherTerm(response.term)
      } else {
        receivedAppendEntriesResponse(member, request, response)
      }

      // Y.V Update latency set
      //latencySet += membership.myStop - membership.myStart

      response
    }.andThen {
      case Failure(reason) ⇒
        logger.trace("Error sending appendEntries {}", reason.getMessage())
        if (!request.entries.isEmpty) {
          member.markReplicationsNotInProgress(request.entries.map(_.index))
        }
    }
  }

  private def createAppendEntriesFor(member: RemoteMember) = toReplicateEntriesOf(member) match {
    case head :: list ⇒ replication(head, list)
    case Nil          ⇒ heartbeat()
  }

  private def receivedHigherTerm(higherTerm: Int) = {
    val currentTerm = consensus.term
    if (higherTerm > currentTerm) {
      logger.debug("Detected a term {} higher than current term {}. Step down", higherTerm, currentTerm)
      stepDown(higherTerm)
    }
  }

  private def replication(head: LogEntry, tail: List[LogEntry]) = {
    val entries = head :: tail
    log.getPreviousLogEntry(head) match {
      case Some(previous) ⇒ normalReplication(previous, entries)
      case None           ⇒ firstReplication(entries)
    }
  }

  private def normalReplication(previous: LogEntry, entries: List[LogEntry]) = {
    AppendEntries(term, membership.myId, log.commitIndex, previous.index, previous.term, entries)
  }

  private def firstReplication(entries: List[LogEntry]) = {
    AppendEntries(term, membership.myId, log.commitIndex, entries = entries)
  }

  private def heartbeat() = AppendEntries(term, membership.myId, log.commitIndex)

  private def toReplicateEntriesOf(member: RemoteMember): List[LogEntry] = {
    val index = member.nextLogIndex.longValue()
    val entries = for (
      entry ← log.entry(index) if (member.canReplicateIndex(index))
    ) yield entry
    List(entries).flatten
  }

  def stopBroadcasting() = {
    logger.debug("Stop broadcasting")
    scheduledHeartbeatsPool.shutdownNow()
  }

  private def announceLeadership() = {
    logger.info(s"Start being $this")
    leaderAnnouncer.announce(membership.myId)
  }

  private def appendNoOp() = {
    if (log.isEmpty) {
      logger.info("Log is empty. First Leader. Appending initial cluster configuration")
      onCommand[Boolean](NewConfiguration(Set(membership.myId))) //the initial configuration must go through the log
    } else {
      logger.debug("Append a NoOp as part of Leader initialization")
      onCommand[Unit](NoOp())
    }
  }

  private def resetLastIndex() = log.resetLastIndex()

  private def resetNextAndMatchIndexes() = {
    val nextIndex = log.lastIndex + 1
    membership.remoteMembers.foreach { member ⇒ member.setNextLogIndex(nextIndex); member.resetMatchIndex }
  }

  override def stop(stopTerm: Int) = {
    if (stopTerm > term) {
      stopBroadcasting()
      logger.debug("Stop being Leader")
    }
  }

  override def onCommand[T](command: Command): Future[T] = {
    command match {
      case write: WriteCommand[T] ⇒ onWriteCommand[T](write)
      case read: ReadCommand[T]   ⇒ onReadCommand[T](read)
    }
  }

  private def onWriteCommand[T](write: WriteCommand[T]): Future[T] = {
    log.append[T](term, write) flatMap { tuple ⇒
      val logEntry = tuple._1
      val valuePromise = tuple._2
      broadcast(logEntry)
      valuePromise.future
    }
  }

  private def broadcast(logEntry: LogEntry): Unit = {
    if (membership.hasRemoteMembers) {
      broadcast()
    } else {
      logger.debug("No member to broadcast")
      log.commit(logEntry.index)
    }
  }

  private def onReadCommand[T](command: ReadCommand[T]): Future[T] = onStillBeingLeader.flatMap(_ ⇒ log.execute(command))

  private def onStillBeingLeader = {
    logger.trace(s"$this checking if still being Leader...")
    if (membership.hasRemoteMembers) {
      val promise = Promise[Unit]()
      val term = consensus.term()

      val membersAck = TrieMap[String, Unit](membership.myId -> Unit)
      val memberFailures = TrieMap[String, Unit]()

      broadcast().foreach {
        case (member, futureResponse) ⇒
          futureResponse.andThen {
            case Success(response) ⇒ {
              if (consensus.term != term) lostLeadership(promise, "New term received")
              else {
                membersAck.put(member.id(), Unit)
                if (membership.reachQuorum(membersAck.keys.toSet)) {
                  logger.debug(s"$this ${membership.myId} is still the current Leader")
                  promise.trySuccess(())
                }
              }
            }
            case Failure(reason) ⇒ {
              memberFailures.put(member.id(), Unit)
              if (membership.reachSomeQuorum(memberFailures.keys.toSet)) {
                lostLeadership(promise, "Failed to reach quorum of members")
              }
            }
          }
      }
      promise.future
    } else Future.successful(())
  }

  private def lostLeadership(promise: Promise[Unit], reason: String) = {
    logger.debug(s"$this ${membership.myId} lost leadership. Reason: $reason")
    promise.tryFailure(LostLeadershipException(reason))
  }

  override def isLeader() = {
    Try {
      Await.result(onStillBeingLeader, waitForLeaderTimeout)
      true
    }.getOrElse(false)
  }

  override def onAppendEntries(appendEntries: AppendEntries): Future[AppendEntriesResponse] = {
    if (appendEntries.term < term) {
      rejectOldLeader(appendEntries)
    } else {
      stepDownAndPropagate(appendEntries)
    }
  }

  override def onRequestVote(requestVote: RequestVote): Future[RequestVoteResponse] = {
    if (requestVote.term <= term) {
      rejectVote(requestVote.memberId, s"being Leader in term $term")
    } else {
      stepDownAndPropagate(requestVote)
    }
  }

  override def onJointConfigurationCommitted(jointConfiguration: JointConfiguration) = {
    logger.debug(s"JointConfiguration is committed... will use and broadcast a NewConfiguration")
    onCommand[Boolean](NewConfiguration(jointConfiguration.newMembers))
  }

  override def onInstallSnapshot(installSnapshot: InstallSnapshot): Future[InstallSnapshotResponse] = {
    Future.successful(InstallSnapshotResponse(false))
  }

  private def receivedAppendEntriesResponse(member: RemoteMember, request: AppendEntries, response: AppendEntriesResponse) = {
    followersStats.put(member.id(), now())
    if (!request.entries.isEmpty) {
      updateNextLogIndex(member, request, response)
    }
    val nextIndex = member.nextLogIndex.intValue()
    if (isLogEntryInSnapshot(nextIndex)) {
      val wasEnabled = member.disableReplications()
      if (wasEnabled) {
        logger.debug(s"Next LogIndex #$nextIndex to be sent to ${member} is contained in a Snapshot. An InstallSnapshot will be sent.")
        sendInstallSnapshot(member)
      }
    }
  }

  private def updateNextLogIndex(member: RemoteMember, appendEntries: AppendEntries, appendEntriesResponse: AppendEntriesResponse) = {
    val lastIndexSent = appendEntries.entries.last.index
    if (appendEntriesResponse.success) {
      member.acknowledgeIndex(lastIndexSent)
      logger.debug(s"Member ${member} ack - index sent #$lastIndexSent - next index #${member.nextLogIndex}")
      tryToCommitEntries(lastIndexSent)
    } else {
      member.decrementNextLogIndex()
      if (!appendEntries.entries.isEmpty) {
        member.markReplicationsNotInProgress(appendEntries.entries.map(_.index))
      }
      logger.debug(s"Member ${member} reject - index sent #$lastIndexSent - next index is #${member.nextLogIndex}")
    }
  }

  private def tryToCommitEntries(lastEntrySent: Long) = {
    val currentCommitIndex = log.commitIndex
    (currentCommitIndex + 1) to lastEntrySent foreach { index ⇒
      if (reachQuorum(index)) {
        log.commit(index)
      }
    }
  }

  private def reachQuorum(index: Index) = membership.reachQuorum(membersHavingAtLeast(index) + membership.myId)

  private def membersHavingAtLeast(index: Long): Set[String] = {
    membership.remoteMembers.filter { remoteMember ⇒ remoteMember.matchIndex.longValue() >= index } map {
      _.id
    }
  }

  private def isLogEntryInSnapshot(logIndex: Int): Boolean = {
    log.isInSnapshot(logIndex)
  }

  def sendInstallSnapshot(member: RemoteMember) = {
    log.latestSnapshot map { snapshot ⇒
      val installSnapshot = InstallSnapshot(term, membership.myId, snapshot)
      logger.debug(s"Sending $installSnapshot to ${member}")
      member.sendInstallSnapshot(installSnapshot).map { response ⇒
        if (response.success) {
          logger.debug("Successful InstallSnapshot")
          member.acknowledgeIndex(snapshot.index)
          tryToCommitEntries(snapshot.index)
        } else {
          logger.debug("Failed InstallSnapshot")
        }
        member.enableReplications()
      }

    }
  }

  override def stats() = {
    val currentTime = now()
    val followers = followersStats.asScala.map {
      tuple ⇒
        val member = membership.get(tuple._1).get
        (tuple._1, FollowerInfo(lastAck(tuple._2, currentTime), member.matchIndex.intValue(), member.nextLogIndex.intValue()))
    }
    LeaderInfo(leaderUptime.toString, followers.toMap)
  }

  private def leaderUptime = (now() - startTime millis).toCoarsest

  private def lastAck(ackTime: Long, now: Long) = if (ackTime > 0) (now - ackTime millis).toString else "Never"

  private def now(): Long = System.currentTimeMillis()

  override def toString = s"Leader[$term]"

}