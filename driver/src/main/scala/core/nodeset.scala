/*
 * Copyright 2012-2013 Stephane Godbillon (@sgodbillon) and Zenexity
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactivemongo.core.nodeset

import akka.actor._
import java.net.InetSocketAddress
import java.util.concurrent.{ Executor, Executors }
import org.jboss.netty.buffer._
import org.jboss.netty.channel.{ Channels, Channel, ChannelPipeline }
import org.jboss.netty.channel.group._
import org.jboss.netty.channel.socket.nio._
import org.slf4j.{ Logger, LoggerFactory }
import reactivemongo.bson._
import reactivemongo.core.protocol._
import reactivemongo.core.protocol.ChannelState._
import reactivemongo.core.commands.{ Authenticate => AuthenticateCommand, _ }
import reactivemongo.core.protocol.NodeState._
import reactivemongo.utils.LazyLogger

case class MongoChannel(
    channel: Channel,
    state: ChannelState,
    loggedIn: Set[LoggedIn]) {
  import MongoChannel._

  lazy val usable = state match {
    case _: Usable => true
    case _         => false
  }

  def send(message: Request, writeConcern: Request) {
    logger.trace("connection " + channel.getId + " will send Request " + message + " followed by writeConcern " + writeConcern)
    channel.write(message)
    channel.write(writeConcern)
  }
  def send(message: Request) {
    logger.trace("connection " + channel.getId + " will send Request " + message)
    channel.write(message)
  }
}

object MongoChannel {
  private val logger = LazyLogger(LoggerFactory.getLogger("reactivemongo.core.nodeset.MongoChannel"))
  implicit def mongoChannelToChannel(mc: MongoChannel): Channel = mc.channel
}

case class PingInfo(
  ping: Long = 0,
  lastIsMasterTime: Long = 0,
  lastIsMasterId: Int = -1)

object PingInfo {
  val pingTimeout = 60 * 1000
}

case class Node(
    name: String,
    channels: IndexedSeq[MongoChannel],
    state: NodeState,
    mongoId: Option[Int],
    pingInfo: PingInfo = PingInfo())(implicit channelFactory: ChannelFactory) {
  lazy val (host: String, port: Int) = {
    val splitted = name.span(_ != ':')
    splitted._1 -> (try {
      splitted._2.drop(1).toInt
    } catch {
      case _: Throwable => 27017
    })
  }

  lazy val isQueryable: Boolean = (state == PRIMARY || state == SECONDARY) && queryable.size > 0

  lazy val queryable: IndexedSeq[MongoChannel] = channels.filter(_.usable == true)

  def updateChannelById(channelId: Int, transform: (MongoChannel) => MongoChannel): Node =
    copy(channels = channels.map(channel => if (channel.getId == channelId) transform(channel) else channel))

  def connect(): Unit = channels.foreach(channel => if (!channel.isConnected) channel.connect(new InetSocketAddress(host, port)))

  def disconnect(): Unit = channels.foreach(channel => if (channel.isConnected) channel.disconnect)

  def close(): Unit = channels.foreach(channel => if (channel.isOpen) channel.close)

  def createNeededChannels(receiver: ActorRef, upTo: Int): Node = {
    if (channels.size < upTo) {
      copy(channels = channels.++(for (i <- 0 until (upTo - channels.size)) yield MongoChannel(channelFactory.create(host, port, receiver), NotConnected, Set.empty)))
    } else this
  }

  def sendIsMaster(id: Int): Node = {
    queryable.headOption.map { channel =>
      channel.send(IsMaster().maker(id))
      // println(s"sent IsMaster #$id")
      if (pingInfo.lastIsMasterId == -1) {
        val up = this.copy(pingInfo = pingInfo.copy(lastIsMasterTime = System.currentTimeMillis(), lastIsMasterId = id))
        // println(s"updated pingInfo ${up.pingInfo}")
        up
      } else if (pingInfo.lastIsMasterId >= PingInfo.pingTimeout) {
        val up = this.copy(pingInfo = pingInfo.copy(lastIsMasterTime = System.currentTimeMillis(), lastIsMasterId = id, ping = Long.MaxValue))
        // println(s"updated pingInfo(timeout) ${up.pingInfo}")
        up
      } else {
        // println(s"ignore pingInfo update for #$id")
        this
      }
    }.getOrElse {
      // println(s"failed to send IsMaster (no queryable channel) #$id")
      this
    }
  }

  def isMasterReceived(id: Int): Node = {
    if (pingInfo.lastIsMasterId == id) {
      val updated = this.copy(pingInfo = pingInfo.copy(ping = System.currentTimeMillis() - pingInfo.lastIsMasterTime, lastIsMasterTime = 0, lastIsMasterId = -1))
      // println(s"received isMaster #$id, updated is ${updated.shortSummary}")
      updated
    } else {
      // println(s"ignore isMaster #$id")
      this
    }
  }

  def shortSummary: String = {
    s"Node[$name: $state (${queryable.size} queryable nodes), latency=${pingInfo.ping}]"
  }
}

object Node {
  def apply(name: String)(implicit channelFactory: ChannelFactory): Node = new Node(name, Vector.empty, NONE, None)
  def apply(name: String, state: NodeState)(implicit channelFactory: ChannelFactory): Node = new Node(name, Vector.empty, state, None)
}

case class NodeSet(
    name: Option[String],
    version: Option[Long],
    nodes: IndexedSeq[Node])(implicit channelFactory: ChannelFactory) {
  lazy val connected: IndexedSeq[Node] = nodes.filter(node => node.state != NOT_CONNECTED)

  lazy val queryable: QueryableNodeSet = QueryableNodeSet(this)

  lazy val primary: Option[Node] = nodes.find(_.state == PRIMARY)

  lazy val isReplicaSet: Boolean = name.isDefined

  lazy val isReachable = !queryable.subject.isEmpty

  def connectAll(): Unit = nodes.foreach(_.connect)

  def closeAll(): Unit = nodes.foreach(_.close)

  def findNodeByChannelId(channelId: Int): Option[Node] = nodes.find(_.channels.exists(_.getId == channelId))

  def findByChannelId(channelId: Int): Option[(Node, MongoChannel)] = nodes.flatMap(node => node.channels.map(node -> _)).find(_._2.getId == channelId)

  def updateByMongoId(mongoId: Int, transform: (Node) => Node): NodeSet = {
    new NodeSet(name, version, nodes.updated(mongoId, transform(nodes(mongoId))))
  }

  def updateByChannelId(channelId: Int, transform: (Node) => Node): NodeSet = {
    new NodeSet(name, version, nodes.map(node => if (node.channels.exists(_.getId == channelId)) transform(node) else node))
  }

  def updateAll(transform: (Node) => Node): NodeSet = {
    new NodeSet(name, version, nodes.map(transform))
  }

  def channels = nodes.flatMap(_.channels)

  def addNode(node: Node): NodeSet = {
    nodes.indexWhere(_.name == node.name) match {
      case -1 => this.copy(nodes = node +: nodes)
      case i => {
        val replaced = nodes(i)
        this.copy(nodes = nodes.updated(i, replaced.copy(state = if (node.state != NONE) node.state else replaced.state)))
      }
    }
  }

  def addNodes(nodes: Seq[Node]): NodeSet = {
    nodes.foldLeft(this)(_ addNode _)
  }

  def merge(nodeSet: NodeSet): NodeSet = {
    NodeSet(nodeSet.name, nodeSet.version, nodeSet.nodes.map { node =>
      nodes.find(_.name == node.name).map { oldNode =>
        node.copy(channels = oldNode.channels.union(node.channels).distinct)
      }.getOrElse(node)
    })
  }

  def createNeededChannels(receiver: ActorRef, upTo: Int): NodeSet = {
    copy(nodes = nodes.foldLeft(Vector.empty[Node]) { (nodes, node) =>
      nodes :+ node.createNeededChannels(receiver, upTo)
    })
  }

  def makeChannelGroup(): ChannelGroup = {
    val result = new DefaultChannelGroup
    for (node <- nodes) {
      for (channel <- node.channels)
        result.add(channel.channel)
    }
    result
  }

  def shortStatus = s"{{NodeSet $name ${nodes.map(_.shortSummary).mkString(" | ")} }}"
}

case class QueryableNodeSet(nodeSet: NodeSet) extends RoundRobiner(nodeSet.nodes.filter(_.isQueryable).map { node => NodeRoundRobiner(node) }) {
  def pickChannel: Option[Channel] = pick.flatMap(_.pick.map(_.channel))

  def getNodeRoundRobinerByChannelId(channelId: Int) = subject.find(_.node.channels.exists(_.getId == channelId))

  val primaryRoundRobiner: Option[NodeRoundRobiner] = subject.find(_.node.state == PRIMARY)
  val secondaryRoundRobiner = new RoundRobiner(subject.filter(_.node.state == SECONDARY))

  val nearest = {
    // println(s"QueryableNodeSet. Current status of nodeSet is ${nodeSet.shortStatus}, nearest node is ${subject.sortBy(_.node.pingInfo.ping).headOption.map(_.node.shortSummary)}")
    subject.sortBy(_.node.pingInfo.ping).headOption
  }

  import reactivemongo.api.ReadPreference
  import ReadPreference._

  def pickNodeRoundRobiner(preference: ReadPreference): Option[NodeRoundRobiner] = preference match {
    case Primary                => primaryRoundRobiner
    case PrimaryPrefered(tag)   => primaryRoundRobiner.orElse(secondaryRoundRobiner.pick)
    case Secondary(tag)         => secondaryRoundRobiner.pick
    case SecondaryPrefered(tag) => secondaryRoundRobiner.pick.orElse(primaryRoundRobiner)
    case Nearest(tag)           => nearest
  }

  def pickNodeAndChannel(readPreference: ReadPreference): Option[(Node, MongoChannel)] = pickNodeRoundRobiner(readPreference).flatMap { nrr =>
    nrr.pick.map { mc =>
      nrr.node -> mc
    }
  }

  def pickChannel(preference: ReadPreference): Option[Channel] = pickNodeRoundRobiner(preference).flatMap(_.pick).map(_.channel)
}

case class NodeRoundRobiner(node: Node) extends RoundRobiner(node.queryable)

class RoundRobiner[A](val subject: IndexedSeq[A], private var i: Int = 0) {
  private val length = subject.length

  if (i < 0) i = 0

  def pick: Option[A] = if (length > 0) {
    val result = Some(subject(i))
    i = if (i == length - 1) 0 else i + 1
    result
  } else None

  def pickWithFilter(filter: A => Boolean): Option[A] = pickWithFilter(filter, 0)

  @scala.annotation.tailrec
  private def pickWithFilter(filter: A => Boolean, tested: Int): Option[A] = if (length > 0 && tested < length) {
    val a = pick
    if (!a.isDefined)
      None
    else if (filter(a.get))
      a
    else pickWithFilter(filter, tested + 1)
  } else None
}

case class LoggedIn(db: String, user: String)

class ChannelFactory(bossExecutor: Executor = Executors.newCachedThreadPool, workerExecutor: Executor = Executors.newCachedThreadPool) {
  private val logger = LazyLogger(LoggerFactory.getLogger("reactivemongo.core.nodeset.ChannelFactory"))

  def create(host: String = "localhost", port: Int = 27017, receiver: ActorRef) = {
    val channel = makeChannel(receiver)
    logger.trace("created a new channel: " + channel)
    channel
  }

  val channelFactory = new NioClientSocketChannelFactory(bossExecutor, workerExecutor)

  private val bufferFactory = new HeapChannelBufferFactory(java.nio.ByteOrder.LITTLE_ENDIAN)

  private def makeOptions: java.util.HashMap[String, Object] = {
    val map = new java.util.HashMap[String, Object]()
    map.put("tcpNoDelay", true: java.lang.Boolean)
    map.put("bufferFactory", bufferFactory)
    map
  }

  private def makePipeline(receiver: ActorRef): ChannelPipeline = Channels.pipeline(new RequestEncoder(), new ResponseFrameDecoder(), new ResponseDecoder(), new MongoHandler(receiver))

  private def makeChannel(receiver: ActorRef): Channel = {
    val channel = channelFactory.newChannel(makePipeline(receiver))
    channel.getConfig.setOptions(makeOptions)
    channel
  }
}