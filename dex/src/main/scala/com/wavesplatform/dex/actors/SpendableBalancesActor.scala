package com.wavesplatform.dex.actors

import akka.actor.{Actor, ActorRef, Status}
import cats.instances.long.catsKernelStdGroupForLong
import cats.syntax.either._
import cats.syntax.group.catsSyntaxGroup
import com.wavesplatform.dex.actors.address.{AddressActor, AddressDirectoryActor}
import com.wavesplatform.dex.domain.account.Address
import com.wavesplatform.dex.domain.asset.Asset
import com.wavesplatform.dex.domain.utils.ScorexLogging
import com.wavesplatform.dex.error.{MatcherError, WavesNodeConnectionBroken}
import com.wavesplatform.dex.fp.MapImplicits.cleaningGroup
import com.wavesplatform.dex.grpc.integration.exceptions.WavesNodeConnectionLostException

import scala.concurrent.Future
import scala.util.{Failure, Success}

class SpendableBalancesActor(spendableBalances: (Address, Set[Asset]) => Future[Map[Asset, Long]],
                             allAssetsSpendableBalances: Address => Future[Map[Asset, Long]],
                             addressDirectory: ActorRef)
    extends Actor
    with ScorexLogging {

  import context.dispatcher

  type AddressState = Map[Asset, Long]
  type State        = Map[Address, AddressState]

  /** Keeps states of all addresses by all available assets */
  var fullState: State = Map.empty

  /** Keeps balance changes of addresses for which there is no full state yet (addresses that were not requested for snapshot) */
  var incompleteStateChanges: State = Map.empty

  def receive: Receive = {

    case SpendableBalancesActor.Query.GetState(address, assets) =>
      val maybeAddressState            = fullState.get(address).orElse(incompleteStateChanges get address)
      val assetsMaybeBalances          = assets.map(asset => asset -> maybeAddressState.flatMap(_ get asset)).toMap
      val (knownAssets, unknownAssets) = assetsMaybeBalances.partition { case (_, balance) => balance.isDefined }

      lazy val knownPreparedState = knownAssets.collect { case (a, Some(b)) => a -> b }

      if (unknownAssets.isEmpty) sender ! SpendableBalancesActor.Reply.GetState(knownPreparedState)
      else {
        val requestSender = sender
        spendableBalances(address, unknownAssets.keySet)
          .map(stateFromNode => SpendableBalancesActor.NodeBalanceRequestRoundtrip(address, knownAssets.keySet, stateFromNode))
          .andThen {
            case Success(r) => self.tell(r, requestSender)
            case Failure(ex) =>
              log.error("Could not receive spendable balance from Waves Node", ex)
              requestSender ! Status.Failure(WavesNodeConnectionLostException("Could not receive spendable balance from Waves Node", ex))
          }
      }

    case SpendableBalancesActor.NodeBalanceRequestRoundtrip(address, knownAssets, stateFromNode) =>
      if (!fullState.contains(address)) {
        incompleteStateChanges = incompleteStateChanges.updated(address, stateFromNode ++ incompleteStateChanges.getOrElse(address, Map.empty))
      }

      val assets = stateFromNode.keySet ++ knownAssets
      val result = fullState.getOrElse(address, incompleteStateChanges(address)).filterKeys(assets)

      sender ! SpendableBalancesActor.Reply.GetState(result)

    case SpendableBalancesActor.Query.GetSnapshot(address) =>
      val requestSender = sender
      fullState.get(address) match {
        case Some(state) => requestSender ! SpendableBalancesActor.Reply.GetSnapshot(state.asRight)
        case None =>
          allAssetsSpendableBalances(address).onComplete {
            case Success(balance) => self.tell(SpendableBalancesActor.Command.SetState(address, balance), requestSender)
            case Failure(ex) =>
              log.error("Could not receive address spendable balance snapshot from Waves Node", ex)
              requestSender ! SpendableBalancesActor.Reply.GetSnapshot(WavesNodeConnectionBroken.asLeft)
          }
      }

    case SpendableBalancesActor.Command.SetState(address, state) =>
      val addressState = state ++ incompleteStateChanges.getOrElse(address, Map.empty)
      fullState += address -> addressState
      incompleteStateChanges -= address
      sender ! SpendableBalancesActor.Reply.GetSnapshot(addressState.asRight)

    case SpendableBalancesActor.Command.UpdateStates(changes) =>
      changes.foreach {
        case (address, stateUpdate) =>
          val knownBalance      = fullState.get(address) orElse incompleteStateChanges.get(address) getOrElse Map.empty
          val (clean, forAudit) = if (knownBalance.isEmpty) (stateUpdate, stateUpdate) else getCleanAndForAuditChanges(stateUpdate, knownBalance)

          if (fullState contains address) fullState = fullState.updated(address, knownBalance ++ clean)
          else incompleteStateChanges = incompleteStateChanges.updated(address, knownBalance ++ clean)

          addressDirectory ! AddressDirectoryActor.Envelope(address, AddressActor.Message.BalanceChanged(clean, forAudit))
      }

    // Subtract is called when there is a web socket connection and thus we have `fullState` for this address
    case SpendableBalancesActor.Command.Subtract(address, balance) => fullState = fullState.updated(address, fullState(address) |-| balance)
  }

  /**
    * Splits balance state update into clean (unknown so far) changes and
    * changes for audit (decreasing compared to known balance, they can lead to orders cancelling)
    */
  private def getCleanAndForAuditChanges(stateUpdate: AddressState, knownBalance: AddressState): (AddressState, AddressState) =
    stateUpdate.foldLeft { (Map.empty[Asset, Long], Map.empty[Asset, Long]) } {
      case ((ac, dc), balanceChanges @ (asset, update)) =>
        knownBalance.get(asset).fold { (ac + balanceChanges, dc + balanceChanges) } { existedBalance =>
          if (update == existedBalance) (ac, dc) else (ac + balanceChanges, if (update < existedBalance) dc + balanceChanges else dc)
        }
    }
}

object SpendableBalancesActor {

  trait Command
  object Command {
    final case class SetState(address: Address, state: Map[Asset, Long])   extends Command
    final case class Subtract(address: Address, balance: Map[Asset, Long]) extends Command
    final case class UpdateStates(changes: Map[Address, Map[Asset, Long]]) extends Command
  }

  trait Query
  object Query {
    final case class GetState(address: Address, assets: Set[Asset]) extends Query
    final case class GetSnapshot(address: Address)                  extends Query
  }

  trait Reply
  object Reply {
    final case class GetState(state: Map[Asset, Long])                          extends Reply
    final case class GetSnapshot(state: Either[MatcherError, Map[Asset, Long]]) extends Reply
  }

  final case class NodeBalanceRequestRoundtrip(address: Address, knownAssets: Set[Asset], stateFromNode: Map[Asset, Long])
}
