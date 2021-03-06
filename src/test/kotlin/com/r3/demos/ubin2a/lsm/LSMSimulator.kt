package com.r3.demos.ubin2a.lsm

import com.r3.demos.ubin2a.base.CENTRAL_PARTY_X500
import com.r3.demos.ubin2a.base.REGULATOR_PARTY_X500
import com.r3.demos.ubin2a.base.SGD
import com.r3.demos.ubin2a.base.TemporaryKeyManager
import com.r3.demos.ubin2a.detect.*
import com.r3.demos.ubin2a.execute.*
import com.r3.demos.ubin2a.account.DeadlockNotificationFlow
import com.r3.demos.ubin2a.account.DeadlockService
import com.r3.demos.ubin2a.account.StartLSMFlow
import com.r3.demos.ubin2a.obligation.IssueObligation
import com.r3.demos.ubin2a.obligation.Obligation
import com.r3.demos.ubin2a.obligation.PersistentObligationQueue
import com.r3.demos.ubin2a.obligation.SettleObligation
import com.r3.demos.ubin2a.plan.PlanFlow
import com.r3.demos.ubin2a.pledge.ApprovePledge
import com.r3.demos.ubin2a.ubin2aTestHelpers
import com.r3.demos.ubin2a.ubin2aTestHelpers.createObligation
import com.r3.demos.ubin2a.ubin2aTestHelpers.printSortedObligations
import net.corda.core.flows.FlowException
import net.corda.core.utilities.getOrThrow
import net.corda.finance.contracts.getCashBalance
import net.corda.node.internal.StartedNode
import net.corda.testing.chooseIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.setCordappPackages
import net.corda.testing.unsetCordappPackages
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.*
import kotlin.test.assertFailsWith

/**
 * ScenarioSix: 5 participants - not all obligations are settled
 */
class LSMSimulator {
    lateinit var net: MockNetwork
    lateinit var bank1: StartedNode<MockNetwork.MockNode>
    lateinit var bank2: StartedNode<MockNetwork.MockNode>
    lateinit var bank3: StartedNode<MockNetwork.MockNode>
    lateinit var bank4: StartedNode<MockNetwork.MockNode>
    lateinit var bank5: StartedNode<MockNetwork.MockNode>
    lateinit var regulator: StartedNode<MockNetwork.MockNode>
    lateinit var centralBank: StartedNode<MockNetwork.MockNode>

    val sgd = Currency.getInstance("SGD")

    @Before
    fun setup() {
        setCordappPackages(
                "net.corda.finance",
                "com.r3.demos.ubin2a.obligation",
                "com.r3.demos.ubin2a.cash",
                "com.r3.demos.ubin2a.detect",
                "com.r3.demos.ubin2a.plan",
                "com.r3.demos.ubin2a.execute",
                "com.r3.demos.ubin2a.pledge"
        )
        net = MockNetwork(threadPerNode = true)
        val nodes = net.createSomeNodes(6)
        bank1 = nodes.partyNodes[0] // Mock company 2
        bank2 = nodes.partyNodes[1] // Mock company 3
        bank3 = nodes.partyNodes[2] // Mock company 4
        bank4 = nodes.partyNodes[3] // Mock company 5
        bank5 = nodes.partyNodes[4] // Mock company 6
        regulator = net.createPartyNode(nodes.mapNode.network.myAddress, REGULATOR_PARTY_X500) // Regulator
        centralBank = net.createPartyNode(nodes.mapNode.network.myAddress, CENTRAL_PARTY_X500) // Central Bank

        nodes.partyNodes.forEach { it.register() }
        centralBank.register()
        regulator.register()
    }

    @After
    fun tearDown() {
        net.stopNodes()
        unsetCordappPackages()
    }

    private fun StartedNode<MockNetwork.MockNode>.register() {
        val it = this
        it.registerInitiatedFlow(IssueObligation.Responder::class.java)
        it.registerInitiatedFlow(ReceiveScanRequest::class.java)
        it.registerInitiatedFlow(ReceiveScanAcknowledgement::class.java)
        it.registerInitiatedFlow(ReceiveScanResponse::class.java)
        it.registerInitiatedFlow(SettleObligation.Responder::class.java)
        it.registerInitiatedFlow(SendKeyFlow::class.java)
        it.registerInitiatedFlow(ReceiveNettingData::class.java)
        it.registerInitiatedFlow(ReceiveGatherStatesRequest::class.java)
        it.registerInitiatedFlow(ReceiveSignedTransactionFlow::class.java)
        it.registerInitiatedFlow(ReceiveFinalisedTransactionFlow::class.java)
        it.registerInitiatedFlow(ReceivePurgeRequest::class.java)
        it.registerInitiatedFlow(DeadlockNotificationFlow.Responder::class.java)
        it.database.transaction {
            it.internals.installCordaService(PersistentObligationQueue::class.java)
            it.internals.installCordaService(TemporaryKeyManager::class.java)
            it.internals.installCordaService(DeadlockService::class.java)
        }
    }

    private fun printCashBalances() {
        val bank1 = bank1.database.transaction { bank1.services.getCashBalance(sgd) }
        val bank2 = bank2.database.transaction { bank2.services.getCashBalance(sgd) }
        val bank3 = bank3.database.transaction { bank3.services.getCashBalance(sgd) }
        val bank4 = bank4.database.transaction { bank4.services.getCashBalance(sgd) }
        val bank5 = bank5.database.transaction { bank5.services.getCashBalance(sgd) }
        println("Bank1: $bank1, bank2: $bank2, bank3: $bank3, bank4: $bank4, bank5: $bank5")
    }

    /**
     * 5 total obligations (one simple cycle)
     * LSM runs: 1 runs
     * Expected: 0 obligations are settled (Deadlock)
     */
    @Test
    fun `LSM Simulator`() {
        println("----------------------")
        println("Starting LSM Simulator:")
        println("----------------------")
        val sgd = Currency.getInstance("SGD")
        printCashBalances()
        println()

        println("----------------------")
        println("Set up Starting Balance")
        println("----------------------")
        centralBank.services.startFlow(ApprovePledge.Initiator(SGD(640), bank1.services.myInfo.chooseIdentity())).resultFuture.getOrThrow()
        centralBank.services.startFlow(ApprovePledge.Initiator(SGD(560), bank2.services.myInfo.chooseIdentity())).resultFuture.getOrThrow()
        centralBank.services.startFlow(ApprovePledge.Initiator(SGD(650), bank3.services.myInfo.chooseIdentity())).resultFuture.getOrThrow()
        centralBank.services.startFlow(ApprovePledge.Initiator(SGD(660), bank4.services.myInfo.chooseIdentity())).resultFuture.getOrThrow()
        centralBank.services.startFlow(ApprovePledge.Initiator(SGD(550), bank5.services.myInfo.chooseIdentity())).resultFuture.getOrThrow()
        net.waitQuiescent()
        printCashBalances()
        println()

        println("----------------------")
        println("Set up obligations in a gridlock")
        println("----------------------")
        // Create obligation

        val fut1 = createObligation(bank3, bank1, SGD(745), 0).getOrThrow().tx.outputStates.single() as Obligation.State
        val fut2 = createObligation(bank4, bank2, SGD(989), 0).getOrThrow().tx.outputStates.single() as Obligation.State
        val fut3 = createObligation(bank1, bank2, SGD(658), 0).getOrThrow().tx.outputStates.single() as Obligation.State
        val fut4 = createObligation(bank3, bank2, SGD(903), 0).getOrThrow().tx.outputStates.single() as Obligation.State
        val fut5 = createObligation(bank2, bank3, SGD(701), 0).getOrThrow().tx.outputStates.single() as Obligation.State
        val fut6 = createObligation(bank1, bank3, SGD(827), 0).getOrThrow().tx.outputStates.single() as Obligation.State
        val fut7 = createObligation(bank2, bank5, SGD(566), 0).getOrThrow().tx.outputStates.single() as Obligation.State
        val fut8 = createObligation(bank1, bank5, SGD(931), 0).getOrThrow().tx.outputStates.single() as Obligation.State


//        val fut9 = createObligation(bank5, bank3, SGD(499049418),0).getOrThrow().tx.outputStates.single() as Obligation.State
//        val fut10 = createObligation(bank5, bank3, SGD(499468196),0).getOrThrow().tx.outputStates.single() as Obligation.State
//        val fut11 = createObligation(bank5, bank3, SGD(499920808),0).getOrThrow().tx.outputStates.single() as Obligation.State
//        val fut12 = createObligation(bank4, bank3, SGD(499713278),0).getOrThrow().tx.outputStates.single() as Obligation.State
//        val fut13 = createObligation(bank1, bank4, SGD(499328097),0).getOrThrow().tx.outputStates.single() as Obligation.State
//        val fut14 = createObligation(bank2, bank4, SGD(499796205),0).getOrThrow().tx.outputStates.single() as Obligation.State
//        val fut15 = createObligation(bank3, bank4, SGD(499963893),0).getOrThrow().tx.outputStates.single() as Obligation.State
//        val fut16 = createObligation(bank3, bank4, SGD(499660508),0).getOrThrow().tx.outputStates.single() as Obligation.State
//        val fut17 = createObligation(bank1, bank5, SGD(499358255),0).getOrThrow().tx.outputStates.single() as Obligation.State
//        val fut18 = createObligation(bank2, bank5, SGD(499691550),0).getOrThrow().tx.outputStates.single() as Obligation.State
//        val fut19 = createObligation(bank2, bank5, SGD(499824674),0).getOrThrow().tx.outputStates.single() as Obligation.State
//        val fut20 = createObligation(bank2, bank5, SGD(499840290),0).getOrThrow().tx.outputStates.single() as Obligation.State
        net.waitQuiescent()
        printCashBalances()


        println("--------------------------")
        println("Detect")
        println("--------------------------")
        val detect = bank1.services.startFlow(DetectFlow(sgd))
        val (obligations, limits) = detect.resultFuture.getOrThrow()
        net.waitQuiescent()
        printCashBalances()
        println()

        // Perform netting.
        println("--------------------")
        println("Calculating netting:")
        println("--------------------")
        
        val flow = PlanFlow(obligations, limits, sgd)
        val (paymentsToMake, resultantObligations) = bank1.services.startFlow(flow).resultFuture.getOrThrow()

        net.waitQuiescent()
        println("Payments to make:")
        println(paymentsToMake)
        println("Resultant obligations:")
        println(resultantObligations)

        val executeFlow = ExecuteFlow(obligations, resultantObligations, paymentsToMake)
        bank1.services.startFlow(executeFlow).resultFuture.getOrThrow()
        net.waitQuiescent()
        printCashBalances()

        println("bank1 Obligations")
        printSortedObligations(bank1)
        println("bank2 Obligations")
        printSortedObligations(bank2)
        println("bank3 Obligations")
        printSortedObligations(bank3)
        println("bank4 Obligations")
        printSortedObligations(bank4)
        println("bank5 Obligations")
        printSortedObligations(bank5)


//        // SECOND RUN
//        println("--------------------------")
//        println("Detect")
//        println("--------------------------")
//        val detect2 = bank1.services.startFlow(DetectFlow(sgd))
//        val (obligations2, limits2) = detect2.resultFuture.getOrThrow()
//        net.waitQuiescent()
//        printCashBalances()
//        println()
//
//        // Perform netting.
//        println("--------------------")
//        println("Calculating netting:")
//        println("--------------------")
//
//        val flow2 = PlanFlow(obligations2, limits2, sgd)
//        val (paymentsToMake2, resultantObligations2) = bank1.services.startFlow(flow2).resultFuture.getOrThrow()
//        net.waitQuiescent()
//        println("Payments to make:")
//        println(paymentsToMake2)
//        println("Resultant obligations:")
//        println(resultantObligations2)
//
//        val executeFlow2 = ExecuteFlow(obligations2, resultantObligations2, paymentsToMake2)
//        bank1.services.startFlow(executeFlow2).resultFuture.getOrThrow()
//        net.waitQuiescent()
//        printCashBalances()
    }

}