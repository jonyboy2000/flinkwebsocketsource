package net.vankaam.flink.websocket

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws._
import akka.stream.scaladsl._
import akka.stream.{ActorMaterializer, OverflowStrategy}
import com.typesafe.scalalogging.LazyLogging

import scala.async.Async.{async, await}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import java.util.concurrent.atomic.AtomicInteger


/**
  * Client that performs the polls for the web socket source function
  */
class WebSocketClient(url: String,objectName: String, callback: String => Unit) extends LazyLogging {
  implicit val system: ActorSystem = ActorSystem.create("WebSocketClient")
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  /*
    Queue used to push messages onto the web socket
   */
  private var queue: SourceQueueWithComplete[Message] = _


  @volatile private var expecting: AtomicInteger = new AtomicInteger(0)
  private var pollComplete:Promise[Boolean] = _
  private val closePromise = Promise[Unit]()


  private val onClose:Future[Unit] = closePromise.future.flatMap(_ => async {
    await(system.terminate())
    logger.info("Actor system for web socket client terminated")
    if(!pollComplete.isCompleted) {
      logger.info("Finishing poll complete because socket has closed")
      pollComplete.success(false)
    }
  })



  /**
    * forEach sink handling messages from the server
    */
  private val sink: Sink[Message, Future[Done]] = Sink.foreach {
    case message: TextMessage.Strict => onNextMessage(message.text)
    case message: TextMessage.Streamed =>
      message.textStream.runFold("")(_+_).onComplete(o => {
        if(o.isSuccess) {
          onNextMessage(o.get)
        } else {
          logger.error("Unexpected error while unfolding stream",o.failed)
        }
      })
    case _ =>
      logger.error("Unexpected message")
  }

  /**
    * Internal handler for a new message
    * @param message message to wait for
    */
  private def onNextMessage(message:String): Unit = {
        callback(message)
        val newValue = expecting.decrementAndGet()
        //If we received all messages the poll has finished
        if (newValue == 0) {
          logger.debug("Poll has completed")
          pollComplete.success(true)
        }
  }

  def onClosed:Future[Unit] = onClose


  /**
    * Performs a poll of the given offset and number of messages
    * @param offset How many messages to skip
    * @param nr Number of messages from the passed offset
    */
  def poll(offset: Long, nr: Int): Future[Boolean] = {
    if(expecting.get() != 0) {
      throw new Exception("Cannot poll while not yet completed")
    }
    pollComplete = Promise()
    expecting.set(10)

    queue.offer(TextMessage(s"$nr.$offset"))
    pollComplete.future
  }


  /**
    * Opens the web socket connection
    * @return a future when the connection has been opened
    */
  def open(): Future[Unit] = async {
    val webSocketFlow = Http().webSocketClientFlow(WebSocketRequest(url))

    val ((q, upgradeResponse),s) = Source.queue[Message](Int.MaxValue, OverflowStrategy.backpressure)
      .viaMat(webSocketFlow)(Keep.both)
      .toMat(sink)(Keep.both)
      .run()
    queue = q

    //When done, finish the close promise
    s.onComplete(_ => closePromise.success())

    val connected = upgradeResponse.map { upgrade =>
      if (upgrade.response.status != StatusCodes.SwitchingProtocols) {
        throw new RuntimeException(s"Connection failed: ${upgrade.response.status}")
      }
    }
    await(connected)

    //Initialize the server with the object
    queue.offer(TextMessage(objectName))
  }

  /**
    * Closes the web socket. After this you should still wait on the "onClosed" method to wait for the actual source to close
    */
  def close(): Unit = {
    if(queue != null) {
      queue.complete()
    }
  }


}


trait WebSocketClientFactory extends Serializable {
  /**
    * Construct a new web socket
    * @param url url to the web socket
    * @param objectName name of the object to request from the web socket
    * @param callback callback method for data received from the web socket
    * @return
    */
  def getSocket(url: String,objectName: String, callback: String => Unit): WebSocketClient
}

object WebSocketClientFactory extends WebSocketClientFactory  {
  override def getSocket(url: String, objectName: String, callback: String => Unit): WebSocketClient = new WebSocketClient(url,objectName,callback)
}