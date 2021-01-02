/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 * (derived from ethereumJ library, Copyright (c) 2016 <ether.camp>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty offinalization
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.ethereum.core;

import co.rsk.config.VmConfig;
import co.rsk.core.Coin;
import co.rsk.core.RskAddress;
import co.rsk.metrics.profilers.Metric;
import co.rsk.metrics.profilers.Profiler;
import co.rsk.metrics.profilers.ProfilerFactory;
import co.rsk.panic.PanicProcessor;
import co.rsk.remasc.RemascTransaction;
import co.rsk.rpc.modules.trace.ProgramSubtrace;

import org.ethereum.config.Constants;
import org.ethereum.config.blockchain.upgrades.ActivationConfig;
import org.ethereum.config.blockchain.upgrades.ConsensusRule;
import org.ethereum.db.BlockStore;
import org.ethereum.db.ReceiptStore;
import org.ethereum.vm.*;
import org.ethereum.vm.program.Program;
import org.ethereum.vm.program.ProgramResult;
import org.ethereum.vm.program.invoke.ProgramInvoke;
import org.ethereum.vm.program.invoke.ProgramInvokeFactory;
import org.ethereum.vm.program.invoke.TransferInvoke;
import org.ethereum.vm.trace.ProgramTrace;
import org.ethereum.vm.trace.ProgramTraceProcessor;
import org.ethereum.vm.trace.SummarizedProgramTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

import static co.rsk.util.ListArrayUtil.getLength;
import static co.rsk.util.ListArrayUtil.isEmpty;
import static org.ethereum.config.blockchain.upgrades.ConsensusRule.RSKIP174;
import static org.ethereum.util.BIUtil.*;
import static org.ethereum.util.ByteUtil.EMPTY_BYTE_ARRAY;

/**
 * @author Roman Mandeleil
 * @since 19.12.2014
 */
public class TransactionExecutor {

    private static final Logger logger = LoggerFactory.getLogger("execute");
    private static final Profiler profiler = ProfilerFactory.getInstance();
    private static final PanicProcessor panicProcessor = new PanicProcessor();

    private final Constants constants;
    private final ActivationConfig.ForBlock activations;
    private final Transaction tx;
    private final int txindex;
    private final Repository track;
    private final Repository cacheTrack; // Respository of reversible changes of the transaction
    private final RentTracker rentTracker;
    private final BlockStore blockStore;
    private final ReceiptStore receiptStore;
    private final BlockFactory blockFactory;
    private final VmConfig vmConfig;
    private final PrecompiledContracts precompiledContracts;
    private final boolean enableRemasc;
    private String executionError = "";
    private final long gasUsedInTheBlock;
    private Coin paidFees;

    private final ProgramInvokeFactory programInvokeFactory;
    private final RskAddress coinbase;

    private TransactionReceipt receipt;
    private ProgramResult result = new ProgramResult();
    private final Block executionBlock;
    private final boolean irisHardForkActivated = false; // TODO SDL: replace with activation check

    private VM vm;
    private Program program;
    private List<ProgramSubtrace> subtraces;

    private PrecompiledContracts.PrecompiledContract precompiledContract;

    private long mEndGas = 0;

    private boolean isRemascTx; //#to avoid storage rent related errors check if senderAddr == remasc addr
    private long mEndRentGas = 0;
    private long refTimeStamp; // reference timestamp to use to estimate storage rent due

    //#mish unlike execution gas, rent gas is only collected at EOT. Use this for tracking estimated rent
    private long estRentGas = 0;

    private long basicTxCost = 0;
    private List<LogInfo> logs = null;
    private final Set<DataWord> deletedAccounts;
    private SignatureCache signatureCache;

    private boolean localCall = false;

    public TransactionExecutor(
            Constants constants, ActivationConfig activationConfig, Transaction tx, int txindex, RskAddress coinbase,
            Repository track, BlockStore blockStore, ReceiptStore receiptStore, BlockFactory blockFactory,
            ProgramInvokeFactory programInvokeFactory, Block executionBlock, long gasUsedInTheBlock, VmConfig vmConfig,
            boolean remascEnabled, PrecompiledContracts precompiledContracts, Set<DataWord> deletedAccounts,
            SignatureCache signatureCache) {
        this.constants = constants;
        this.signatureCache = signatureCache;
        this.activations = activationConfig.forBlock(executionBlock.getNumber());
        this.tx = tx;
        this.txindex = txindex;
        this.coinbase = coinbase;
        this.track = track;
        this.rentTracker = new RentTracker(executionBlock.getTimestamp());
        this.cacheTrack = track.startTracking(rentTracker);
        this.blockStore = blockStore;
        this.receiptStore = receiptStore;
        this.blockFactory = blockFactory;
        this.programInvokeFactory = programInvokeFactory;
        this.executionBlock = executionBlock;
        this.gasUsedInTheBlock = gasUsedInTheBlock;
        this.vmConfig = vmConfig;
        this.precompiledContracts = precompiledContracts;
        this.enableRemasc = remascEnabled;
        this.deletedAccounts = new HashSet<>(deletedAccounts);
    }

    /**
     * Validates and executes the transaction
     *
     * @return true if the transaction is valid and executed, false if the transaction is invalid
     */
    public boolean executeTransaction() {
        //logger.info("start init");
        if (!this.init()) {
            return false;
        }
        //logger.info("start exec");
        this.execute();
        //logger.info("start go");
        this.go();
        //logger.info("start finalization");
        this.finalization();

        return true;
    }

    /**
     * Do all the basic validation, if the executor
     * will be ready to run the transaction at the end
     * set readyToExecute = true
     */
    private boolean init() {
        track.setRentTracker(rentTracker); // temporary rent tracker
        try {
            basicTxCost = tx.transactionCost(constants, activations);
            
            if (localCall) {
                return true;
            }

            /** replace with class instance version (below)
             if (tx.getSender() == RemascTransaction.REMASC_ADDRESS) {
             //System.out.println("\n\n\n remasc addr " + RemascTransaction.REMASC_ADDRESS);
             this.isRemascTx = true;
             }
             */
            if (tx instanceof RemascTransaction) {
                //System.out.println("\n\n\n remasc!\n\n");
                this.isRemascTx = true;
            }

            // #mish: In Transaction.class, these methods divide a tx gaslimit field
            // between two approximately equal budgets: a limit for execution gas and limit for rent
            long txGasLimit = GasCost.toGas(tx.getGasLimit());
            long txRentGasLimit = GasCost.toGas(tx.getRentGasLimit());

            // #mish: block level gas limit is based on execution only (rent is not included)
            long curBlockGasLimit = GasCost.toGas(executionBlock.getGasLimit());

            if (!gasIsValid(txGasLimit, curBlockGasLimit)) {
                return false;
            }

            if (!nonceIsValid()) {
                return false;
            }

            // Here we track rent cost.
            Coin senderBalance = track.getBalance(tx.getSender(), true);

            // Now we add the cost to increment the nonce without actually doing so.
            // We could also perform the nonce increment here, but this init is supposed
            // not to have side-effects. The drawback of simulating an increment
            // is that we assume the new nonce is exactly of the same sice of the previous nonce,
            // which is untrue every 256 increments.
            // Note that since the node timestamp is not modified, trackAccountUpdate()
            // should be called only ONCE. If called multiple times, then the rent
            // may be charged  multiple times.
            track.trackAccountUpdate(tx.getSender());

            Coin totalCost = tx.getValue();

            // TODO: Not clear here why this is conditional on no basic costs
            // It seems that REMASC is the exception.
            if (basicTxCost > 0) {
                // add gas cost only for priced transactions
                Coin txGasCost = tx.getGasPrice().multiply(BigInteger.valueOf(txGasLimit));
                //storage rent cost
                Coin txRentGasCost = tx.getGasPrice().multiply(BigInteger.valueOf(txRentGasLimit));
                totalCost = totalCost.add(txGasCost).add(txRentGasCost);
                long txAccountRentGas = rentTracker.getRentDue();
                rentTracker.clearRentDue();
                Coin accountAccessRent = tx.getGasPrice().multiply(BigInteger.valueOf(txAccountRentGas));
                totalCost = totalCost.add(accountAccessRent);
            }



            if (!isCovers(senderBalance, totalCost)) {

                logger.warn("Not enough cash: Require: {}, Sender cash: {}, tx {}", totalCost, senderBalance, tx.getHash());
                logger.warn("Transaction Data: {}", tx);
                logger.warn("Tx Included in the following block: {}", this.executionBlock);

                execError(String.format("Not enough cash: Require: %s, Sender cash: %s", totalCost, senderBalance));

                return false;
            }

            if (!transactionAddressesAreValid()) {
                return false;
            }

            return true;
        } finally {
            // Currently we have a temporary rent tracker.
            // We'll remove it now because we added the cost of nonce incrementation
            // manually in init()
            track.removeRentTracker();

        }
    }

    private boolean transactionAddressesAreValid() {
        // Prevent transactions with excessive address size
        byte[] receiveAddress = tx.getReceiveAddress().getBytes();
        if (receiveAddress != null && !Arrays.equals(receiveAddress, EMPTY_BYTE_ARRAY) && receiveAddress.length > Constants.getMaxAddressByteLength()) {
            logger.warn("Receiver address to long: size: {}, tx {}", receiveAddress.length, tx.getHash());
            logger.warn("Transaction Data: {}", tx);
            logger.warn("Tx Included in the following block: {}", this.executionBlock);

            return false;
        }

        if (!tx.acceptTransactionSignature(constants.getChainId())) {
            logger.warn("Transaction {} signature not accepted: {}", tx.getHash(), tx.getSignature());
            logger.warn("Transaction Data: {}", tx);
            logger.warn("Tx Included in the following block: {}", this.executionBlock);

            panicProcessor.panic("invalidsignature",
                    String.format("Transaction %s signature not accepted: %s",
                            tx.getHash(), tx.getSignature()));
            execError(String.format("Transaction signature not accepted: %s", tx.getSignature()));

            return false;
        }

        return true;
    }

    private boolean nonceIsValid() {
        // Here we don't track rent either. An invalid nonce means the block is invalid
        // we access the nonce "for tree".
        BigInteger reqNonce = track.getNonce(tx.getSender(signatureCache),false);
        BigInteger txNonce = toBI(tx.getNonce());

        if (isNotEqual(reqNonce, txNonce)) {
            if (logger.isWarnEnabled()) {
                logger.warn("Invalid nonce: sender {}, required: {} , tx.nonce: {}, tx {}", tx.getSender(), reqNonce, txNonce, tx.getHash());
                logger.warn("Transaction Data: {}", tx);
                logger.warn("Tx Included in the following block: {}", this.executionBlock.getShortDescr());
            }

            execError(String.format("Invalid nonce: required: %s , tx.nonce: %s", reqNonce, txNonce));
            return false;
        }

        return true;
    }

    private boolean gasIsValid(long txGasLimit, long curBlockGasLimit) {
        // if we've passed the curBlockGas limit we must stop exec
        // cumulativeGas being equal to GasCost.MAX_GAS is a border condition
        // which is used on some stress tests, but its far from being practical
        // as the current gas limit on blocks is 6.8M... several orders of magnitude
        // less than the theoretical max gas on blocks.
        long cumulativeGas = GasCost.add(txGasLimit, gasUsedInTheBlock);

        boolean cumulativeGasReached = cumulativeGas > curBlockGasLimit || cumulativeGas == GasCost.MAX_GAS;
        if (cumulativeGasReached) {
            execError(String.format("Too much gas used in this block: available in block: %s tx sent: %s",
                    curBlockGasLimit - txGasLimit,
                    txGasLimit));
            return false;
        }

        if (txGasLimit < basicTxCost) {
            execError(String.format("Not enough gas for transaction execution: tx needs: %s tx sent: %s", basicTxCost, txGasLimit));
            return false;
        }

        return true;
    }

    private void execute() {
        logger.trace("Execute transaction {} {}", toBI(tx.getNonce()), tx.getHash());

        // Here there is NO rent tracking on "track".

        if (!localCall) {
            // increment nonce, but do not collect rent
            track.increaseNonce(tx.getSender());

            long txGasLimit = GasCost.toGas(tx.getGasLimit());
            long txRentGasLimit = GasCost.toGas(tx.getRentGasLimit());
            //execution gas limit  gas2Coin
            Coin txGasCost = tx.getGasPrice().multiply(BigInteger.valueOf(txGasLimit));
            Coin txRentGasCost = tx.getGasPrice().multiply(BigInteger.valueOf(txRentGasLimit));

            // We make the sender pay for the transaction.
            // We have already collected rent for the sender account writes by incrementing
            // the nonce. There is no need to collect more rent here.
            // ....also collect rent, but since the nonce has been incremented, and rent
            // ....was already collected, no additional rent should be collected here.
            track.addBalance(tx.getSender(), txGasCost.add(txRentGasCost).negate());

            logger.trace("Paying: txGasCost: [{}],  gasPrice: [{}], gasLimit: [{}], txRentGasCost: [{}], rentGasLimit: [{}]",
                    txGasCost, tx.getGasPrice(), txGasLimit, txRentGasCost, txRentGasLimit);
        }

        if (tx.isContractCreation()) {
            create();
        } else {
            call();
        }
    }

    private boolean enoughGas(long txGasLimit, long requiredGas, long gasUsed) {
        if (!activations.isActive(ConsensusRule.RSKIP136)) {
            return txGasLimit >= requiredGas;
        }
        return txGasLimit >= gasUsed;
    }

    private void call() {
        logger.trace("Call transaction {} {}", toBI(tx.getNonce()), tx.getHash());

        RskAddress targetAddress = tx.getReceiveAddress();

        // DataWord(targetAddress)) can fail with exception:
        // java.lang.RuntimeException: Data word can't exceed 32 bytes:
        // if targetAddress size is greater than 32 bytes.
        // But init() will detect this earlier
        precompiledContract = precompiledContracts.getContractForAddress(activations, DataWord.valueOf(targetAddress.getBytes()));

        this.subtraces = new ArrayList<>();

        if (precompiledContract != null) {
            callToPrecompile(targetAddress);
        } else {
            // Now we work with the cacheTrack, not the track, but we;ve set a temporary
            // rent tracker on the track.
            byte[] code = track.getCode(targetAddress,true);
            // Code can be null
            if (isEmpty(code)) {
                mEndGas = GasCost.subtract(GasCost.toGas(tx.getGasLimit()), basicTxCost);
                result.spendGas(basicTxCost);
            } else {
                ProgramInvoke programInvoke =
                        programInvokeFactory.createProgramInvoke(tx, txindex, executionBlock, cacheTrack, blockStore);

                this.vm = new VM(vmConfig, precompiledContracts);
                this.program = new Program(vmConfig, precompiledContracts, blockFactory, activations, code, programInvoke, tx, deletedAccounts);
            }
        }

        if (result.getException() == null) {
            Coin endowment = tx.getValue();
            //#mish do not track rent for any logging!
            //logger.info("Sender Balance: before transfer (track) {}", track.getBalance(tx.getSender(),false));
            //logger.info("Sender Balance: before transfer cachetrack {}", cacheTrack.getBalance(tx.getSender(),false));
            cacheTrack.transfer(tx.getSender(), targetAddress, endowment); //#mish should track rent for targetAdress..
            //logger.info("Sender Balance: after transfer (track) {}", track.getBalance(tx.getSender(),false));
            //logger.info("Sender Balance: after transfer cachetrack {}", cacheTrack.getBalance(tx.getSender(),false));
        }
    }

    private void callToPrecompile(RskAddress targetAddress) {
        Metric metric = profiler.start(Profiler.PROFILING_TYPE.PRECOMPILED_CONTRACT_INIT);
        // We use the cacheTrack to be able to collect rent if necessary
        precompiledContract.init(tx, executionBlock, cacheTrack, blockStore, receiptStore, result.getLogInfoList());
        profiler.stop(metric);
        metric = profiler.start(Profiler.PROFILING_TYPE.PRECOMPILED_CONTRACT_EXECUTE);

        long requiredGas = precompiledContract.getGasForData(tx.getData());
        long txGasLimit = GasCost.toGas(tx.getGasLimit());
        long txRentGasLimit = GasCost.toGas(tx.getRentGasLimit());
        long gasUsed = GasCost.add(requiredGas, basicTxCost);
        if (!localCall && !enoughGas(txGasLimit, requiredGas, gasUsed)) {
            // no refund no endowment
            execError(String.format("Out of Gas calling precompiled contract at block %d " +
                            "for address 0x%s. required: %s, used: %s, left: %s ",
                    executionBlock.getNumber(), targetAddress.toString(), requiredGas, gasUsed, mEndGas));
            mEndGas = 0;

            if (!isRemascTx) {
                // #mish: if exec gas OOG, do not refund all rent Gas.. keep 25% as per RSKIP113
                mEndRentGas = 3 * txRentGasLimit / 4; //#mish: with pre compiles should all rent gas be refunded?
                // increase estimated rentgas
                estRentGas += txRentGasLimit / 4;
            }
            profiler.stop(metric);
            return;
        }

        mEndGas = activations.isActive(ConsensusRule.RSKIP136) ?
                GasCost.subtract(txGasLimit, gasUsed) :
                txGasLimit - gasUsed;

        // FIXME: save return for vm trace
        try {
            // Direct calls to precompiled contracts must consume rent, and they do
            // because they now use the cacheTrack
            byte[] out = precompiledContract.execute(tx.getData());
            this.subtraces = precompiledContract.getSubtraces();
            result.setHReturn(out);

            // Since we're accessing the trie for free here,
            // It's important that in Iris hard-forks these lines are
            // deactivated (until a new precompile contract is included, and in that
            // case only the check for that contract must be performed)
            if (!irisHardForkActivated) {
                fixPrecompileContractNode(targetAddress);
            } else {
                //if (targetAddress.equals(MyNewIrisContract))
                //    fixPrecompileContractNode(targetAddress);
            }
        } catch (RuntimeException e) {
            result.setException(e);
        }
        result.spendGas(gasUsed);
        profiler.stop(metric);
    }

    private void fixPrecompileContractNode(RskAddress targetAddress) {
        if (!track.isExist(targetAddress, true)) {
            track.createAccount(targetAddress);
            track.setupContract(targetAddress);
        } else if (!track.isContract(targetAddress, true)) {
            track.setupContract(targetAddress);
        }
    }

    private void create() {
        RskAddress newContractAddress = tx.getContractAddress();
        // Since storage rent is activated after RSKIP174, then the existence check
        // will collect rent on the newly created contract. If it doesn't exists, and no read or
        // write is performed, then there will be a penalization
        boolean carryOverBalance =activations.isActive(RSKIP174) && cacheTrack.isExist(newContractAddress,true );
        cacheTrack.createAccount(newContractAddress,carryOverBalance );

        if (isEmpty(tx.getData())) {
            mEndGas = GasCost.subtract(GasCost.toGas(tx.getGasLimit()), basicTxCost);
            // If there is no data, then the account is created, but without code nor
            // storage. It doesn't even call setupContract() to setup a storage root
        } else {
            cacheTrack.setupContract(newContractAddress);
            ProgramInvoke programInvoke = programInvokeFactory.createProgramInvoke(tx, txindex, executionBlock, cacheTrack, blockStore);

            this.vm = new VM(vmConfig, precompiledContracts);
            this.program = new Program(vmConfig, precompiledContracts, blockFactory, activations, tx.getData(), programInvoke, tx, deletedAccounts);

            // reset storage if the contract with the same address already exists
            // TCK test case only - normally this is near-impossible situation in the real network
            /* Storage keys not available anymore in a fast way
            ContractDetails contractDetails = program.getStorage().getContractDetails(newContractAddress);
            for (DataWord key : contractDetails.getStorageKeys()) {
                program.storageSave(key, DataWord.ZERO);
            }
            */
        }

        Coin endowment = tx.getValue();
        cacheTrack.transfer(tx.getSender(), newContractAddress, endowment);
    }

    private void execError(Throwable err) {
        logger.error("execError: ", err);
        executionError = err.getMessage();
    }

    private void execError(String err) {
        logger.trace(err);
        executionError = err;
    }

    private void go() {
        // TODO: transaction call for pre-compiled  contracts
        if (vm == null) {
            cacheTrack.commit();
            return;
        }

        logger.trace("Go transaction {} {}", toBI(tx.getNonce()), tx.getHash());

        //Set the deleted accounts in the block in the remote case there is a CREATE2 creating a deleted account

        Metric metric = profiler.start(Profiler.PROFILING_TYPE.VM_EXECUTE);
        try {

            // Charge basic cost of the transaction
            program.spendGas(tx.transactionCost(constants, activations), "TRANSACTION COST");

            vm.play(program);

            result = program.getResult();
            mEndGas = GasCost.subtract(GasCost.toGas(tx.getGasLimit()), program.getResult().getExecGasUsed());

            if (tx.isContractCreation() && !result.isRevert()) {
                createContract();
            }

            if (result.getException() != null || result.isRevert()) {
                result.clearFieldsOnException();
                cacheTrack.rollback();

                if (result.getException() != null) {
                    throw result.getException();
                } else {
                    execError("REVERT opcode executed");
                }
            }
        } catch (Exception e) {
            cacheTrack.rollback();
            mEndGas = 0;
            mEndRentGas = 0; // #mish todo should this be 0 or 75%?
            execError(e);
            profiler.stop(metric);
            return;
        }
        cacheTrack.commit();
        profiler.stop(metric);
    }

    private void createContract() {
        int createdContractSize = getLength(program.getResult().getHReturn());
        long returnDataGasValue = GasCost.multiply(GasCost.CREATE_DATA, createdContractSize);
        if (mEndGas < returnDataGasValue) {
            program.setRuntimeFailure(
                    Program.ExceptionHelper.notEnoughSpendingGas(
                            program,
                            "No gas to return just created contract",
                            returnDataGasValue));
            result = program.getResult();
            result.setHReturn(EMPTY_BYTE_ARRAY);
        } else if (createdContractSize > Constants.getMaxContractSize()) {
            program.setRuntimeFailure(
                    Program.ExceptionHelper.tooLargeContractSize(
                            program,
                            Constants.getMaxContractSize(),
                            createdContractSize));
            result = program.getResult();
            result.setHReturn(EMPTY_BYTE_ARRAY);
        } else {
            mEndGas = GasCost.subtract(mEndGas, returnDataGasValue);
            program.spendGas(returnDataGasValue, "CONTRACT DATA COST");
            cacheTrack.saveCode(tx.getContractAddress(), result.getHReturn());
        }
    }

    public TransactionReceipt getReceipt() {
        if (receipt == null) {
            receipt = new TransactionReceipt();
            long totalGasUsed = GasCost.add(gasUsedInTheBlock, getGasUsed());
            receipt.setCumulativeGas(totalGasUsed);
            receipt.setTransaction(tx);
            receipt.setLogInfoList(getVMLogs());
            receipt.setGasUsed(result.getExecGasUsed() + result.getRentGasUsed()); //#mish combined gas usage (exec + rent) (cos Wallets)
            receipt.setStatus(executionError.isEmpty() ? TransactionReceipt.SUCCESS_STATUS : TransactionReceipt.FAILED_STATUS);
            receipt.setExecGasUsed(result.getExecGasUsed()); // for testing
            receipt.setRentGasUsed(result.getRentGasUsed()); //for testing   
        }
        return receipt;
    }


    private void finalization() {
        // Collect rent gas computed here (but not internal TX) before finalization
        result.spendRentGas(estRentGas);
        // RSK if local call gas balances must not be changed
        if (localCall) {
            return;
        }

        logger.trace("Finalize transaction {} {}", toBI(tx.getNonce()), tx.getHash());

        cacheTrack.commit();

        //Transaction sender is stored in cache
        signatureCache.storeSender(tx);

        long txRentGasLimit = GasCost.toGas(tx.getRentGasLimit());

        mEndRentGas = activations.isActive(ConsensusRule.RSKIP136) ?
                GasCost.subtract(txRentGasLimit, result.getRentGasUsed()) :
                txRentGasLimit - result.getRentGasUsed();


        // Should include only LogInfo's that was added during not rejected transactions
        List<LogInfo> notRejectedLogInfos = result.getLogInfoList().stream()
                .filter(logInfo -> !logInfo.isRejected())
                .collect(Collectors.toList());

        TransactionExecutionSummary.Builder summaryBuilder = TransactionExecutionSummary.builderFor(tx)
                .gasLeftover(BigInteger.valueOf(mEndGas + mEndRentGas)) // #mish combine exec and rent gas left over
                .logs(notRejectedLogInfos)
                .result(result.getHReturn());

        // Accumulate refunds for suicides
        result.addFutureRefund(GasCost.multiply(result.getDeleteAccounts().size(), GasCost.SUICIDE_REFUND));
        long gasRefund = Math.min(result.getFutureRefund(), result.getExecGasUsed() / 2);

        mEndGas = activations.isActive(ConsensusRule.RSKIP136) ?
                GasCost.add(mEndGas, gasRefund) :
                mEndGas + gasRefund;

        summaryBuilder
                .combinedGasUsed(toBI(result.getExecGasUsed() + result.getRentGasUsed()))
                .gasRefund(toBI(gasRefund)) // #mish this is NOT gas left over.. that's separate
                .deletedAccounts(result.getDeleteAccounts())
                .internalTransactions(result.getInternalTransactions());

        if (result.getException() != null) {
            summaryBuilder.markAsFailed();
        }

        logger.trace("Building transaction execution summary");

        TransactionExecutionSummary summary = summaryBuilder.build();

        // Refund for gas leftover (see above builder, leftover includes rentGas as well)
        Coin reimburse = summary.getLeftover().add(summary.getRefund());
        track.addBalance(tx.getSender(),reimburse );
        logger.trace("Pay total refund to sender: [{}], refund val: [{}]", tx.getSender(), summary.getRefund());

        // Transfer fees to miner
        Coin summaryFee = summary.getFee();

        //TODO: REMOVE THIS WHEN THE LocalBLockTests starts working with REMASC
        if (enableRemasc) {
            logger.trace("Adding fee to remasc contract account");
            track.addBalance(PrecompiledContracts.REMASC_ADDR, summaryFee);
        } else {
            track.addBalance(coinbase, summaryFee);
        }

        this.paidFees = summaryFee;

        //#mish for testing
        logger.info("\nTX finalization " +
                "(is Remasc TX: " + isRemascTx + ")" +
                "\n\nExec GasLimit " + GasCost.toGas(tx.getGasLimit()) +
                "\nExec gas used " + result.getExecGasUsed() +
                "\nExec gas refund " + mEndGas +
                "\n\nRent GasLimit " + GasCost.toGas(tx.getRentGasLimit()) +
                "\nRent gas used " + result.getRentGasUsed() +
                "\nRent gas refund " + mEndRentGas +
                "\n\nTx fees (exec + rent) in Coin (using tx.gasPrice): " + paidFees +
                "\n\nNo. trie nodes with `read` rent timestamp: " + result.getAccessedNodes().size()+ "\n"
        );
        //System.out.println("\n\n" + tx); //#mish for testing                   

        logger.trace("Processing result");
        logs = notRejectedLogInfos;

        result.getCodeChanges().forEach((key, value) -> track.saveCode(new RskAddress(key), value));
        // Traverse list of suicides
        result.getDeleteAccounts().forEach(address -> track.delete(new RskAddress(address)));

        logger.trace("tx listener done");

        logger.trace("tx finalization done");
    }

    /**
     * This extracts the trace to an object in memory.
     * Refer to {@link org.ethereum.vm.VMUtils#saveProgramTraceFile} for a way to saving the trace to a file.
     */
    public void extractTrace(ProgramTraceProcessor programTraceProcessor) {
        if (program != null) {
            // TODO improve this settings; the trace should already have the values
            ProgramTrace trace = program.getTrace().result(result.getHReturn()).error(result.getException()).revert(result.isRevert());
            programTraceProcessor.processProgramTrace(trace, tx.getHash());
        } else {
            TransferInvoke invoke = new TransferInvoke(DataWord.valueOf(tx.getSender().getBytes()), DataWord.valueOf(tx.getReceiveAddress().getBytes()), 0L, GasCost.toGas(tx.getRentGasLimit()), DataWord.valueOf(tx.getValue().getBytes()));

            SummarizedProgramTrace trace = new SummarizedProgramTrace(invoke);

            if (this.subtraces != null) {
                for (ProgramSubtrace subtrace : this.subtraces) {
                    trace.addSubTrace(subtrace);
                }
            }

            programTraceProcessor.processProgramTrace(trace, tx.getHash());
        }
    }

    public TransactionExecutor setLocalCall(boolean localCall) {
        this.localCall = localCall;
        this.tx.setLocalCallTransaction(localCall);
        return this;
    }

    public List<LogInfo> getVMLogs() {
        return logs;
    }

    public ProgramResult getResult() {
        return result;
    }

    public long getGasUsed() {
        if (activations.isActive(ConsensusRule.RSKIP136)) {
            return GasCost.subtract(GasCost.toGas(tx.getGasLimit()), mEndGas);
        }
        return toBI(tx.getGasLimit()).subtract(toBI(mEndGas)).longValue();
    }


    public long getRentGasUsed() {
        return result.getRentGasUsed(); //#mish: the form is different from execgas, which is based on mEndGas. 
    }

    public Coin getPaidFees() {
        return paidFees;
    }

    /**
     * #mish Helper methods for storage rent
     */

    public long getRefTimeStamp() {
        return this.refTimeStamp;
    }

    public long getEstRentGas() {
        return this.estRentGas;
    }
}