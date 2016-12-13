package ckite

import ckite.rpc.Command
import ckite.util.Logging

import scala.concurrent.Future

abstract class Member(binding: String) extends Logging {

  def id() = s"$binding"

  // Y.V test first
  var first: Boolean = false;
  def setfirst(value: Boolean) = { first = value }
  def myfirst() = first

  // Y.V Define and set start time
  var count: Int = 0;
  def mycount() = count
  def setcount(value: Int) = { count = value }

  // Y.V Define and set start time
  var start: Long = 0;
  def setStart() = { start = System.nanoTime() }
  def myStart() = start

  // Y.V Define and set stop time
  var stop: Long = 0;
  def setStop() = { stop = System.nanoTime() }
  def myStop() = stop

  def forwardCommand[T](command: Command): Future[T]

  override def toString() = id

}

