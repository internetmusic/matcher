package com.wavesplatform.dex.load.ws

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ws.{InvalidUpgradeResponse, ValidUpgrade}
import cats.syntax.option._
import com.wavesplatform.dex.api.ws.entities.WsOrder
import com.wavesplatform.dex.api.ws.protocol._
import com.wavesplatform.dex.domain.account.Address
import com.wavesplatform.dex.domain.asset.AssetPair
import com.wavesplatform.dex.domain.utils.EitherExt2
import org.slf4j.LoggerFactory
import play.api.libs.json.Json

import scala.collection.mutable
import scala.concurrent.Future

class WsCollectChangesClient(apiUri: String, address: String, aus: String, obs: Seq[String])(implicit system: ActorSystem) {
  import system.dispatcher

  private val log = LoggerFactory.getLogger(s"WsApiClient[$address]")

  private val emptyWsAddresState: WsAddressChanges = WsAddressChanges(Address.fromString(address).explicitGet(), Map.empty, Seq.empty, 0L)
  @volatile private var accountUpdates             = emptyWsAddresState
  private val orderBookUpdates                     = mutable.AnyRefMap.empty[AssetPair, WsOrderBookChanges]
  @volatile private var gotPings                   = 0

  private val receive: Function[WsServerMessage, Option[WsClientMessage]] = {
    case x: WsPingOrPong        => gotPings += 1; x.some
    case x: WsInitial           => log.info(s"Connection id: ${x.connectionId}"); none
    case x: WsError             => log.error(s"Got error: $x"); throw new RuntimeException(s"Got $x")
    case diff: WsAddressChanges => accountUpdates = merge(accountUpdates, diff); none
    case diff: WsOrderBookChanges =>
      val updatedOb = orderBookUpdates.get(diff.assetPair) match {
        case Some(origOb) => merge(origOb, diff)
        case None         => diff
      }
      orderBookUpdates.put(diff.assetPair, updatedOb)
      none
  }

  private def merge(orig: WsAddressChanges, diff: WsAddressChanges): WsAddressChanges = WsAddressChanges(
    address = diff.address,
    balances = orig.balances ++ diff.balances,
    orders = diff.orders.foldLeft(orig.orders) {
      case (r, x) =>
        val index = r.indexWhere(_.id == x.id)
        if (index < 0) x +: r
        else r.updated(index, merge(r(index), x))
    },
    updateId = diff.updateId,
    timestamp = diff.timestamp
  )

  private def merge(orig: WsOrder, diff: WsOrder): WsOrder = WsOrder(
    id = orig.id,
    timestamp = diff.timestamp,
    amountAsset = orig.amountAsset.orElse(diff.amountAsset),
    priceAsset = orig.priceAsset.orElse(diff.priceAsset),
    side = orig.side.orElse(diff.side),
    isMarket = orig.isMarket.orElse(diff.isMarket),
    price = orig.price.orElse(diff.price),
    amount = orig.amount.orElse(diff.amount),
    fee = orig.fee.orElse(diff.fee),
    feeAsset = orig.feeAsset.orElse(diff.feeAsset),
    status = orig.status.orElse(diff.status),
    filledAmount = orig.filledAmount.orElse(diff.filledAmount),
    filledFee = orig.filledFee.orElse(diff.filledFee),
    avgWeighedPrice = orig.avgWeighedPrice.orElse(diff.avgWeighedPrice)
  )

  private def merge(orig: WsOrderBookChanges, diff: WsOrderBookChanges): WsOrderBookChanges = WsOrderBookChanges(
    assetPair = orig.assetPair,
    asks = orig.asks ++ diff.asks,
    bids = orig.bids ++ diff.bids,
    lastTrade = orig.lastTrade.orElse(diff.lastTrade),
    updateId = diff.updateId,
    settings = orig.settings.orElse(diff.settings), // TODO here merge required, but it wasn't used in these checks
    timestamp = diff.timestamp
  )

  private var client            = none[WsConnection]
  private val subscribeMessages = Json.parse(aus).as[WsAddressSubscribe] :: obs.map(Json.parse(_).as[WsOrderBookSubscribe]).toList

  def run(): Future[Unit] = {
    close()
    val newClient = new WsConnection(apiUri, receive)
    client = newClient.some
    newClient.connectionResponse.map {
      case _: ValidUpgrade           => subscribeMessages.foreach(newClient.send)
      case x: InvalidUpgradeResponse => throw new RuntimeException(s"Can't connect to WebSockets on $apiUri: ${x.response.status} ${x.cause}")
    }
  }

  def collectedAddressState: WsAddressChanges                 = accountUpdates
  def collectedOrderBooks: Map[AssetPair, WsOrderBookChanges] = orderBookUpdates.toMap
  def pingsNumber: Int                                        = gotPings

  def close(): Future[Unit] =
    client
      .fold(Future.successful(()))(_.close().map(_ => ()))
      .andThen {
        case _ =>
          accountUpdates = emptyWsAddresState
          orderBookUpdates.clear()
      }
}
