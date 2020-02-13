package co.rsk.core;

import org.ethereum.config.blockchain.upgrades.ActivationConfig;
import org.ethereum.config.blockchain.upgrades.ConsensusRule;
import org.ethereum.core.BlockFactory;
import org.ethereum.core.BlockHeader;
import org.ethereum.util.RLP;

public class BlockHeaderBuilder {
    private final ActivationConfig activationConfig;
    private byte[] coinbase;
    private byte[] difficulty;
    private int number;
    private int gasUsed;
    private long blockTimestamp;
    private BlockFactory blockFactory;
    private byte[] parentHash;
    private byte[] unclesHash;
    private byte[] logsBloom;
    private byte[] gasLimit;
    private byte[] extraData;
    private Coin minimumGasPrice;
    private int uncleCount;
    private byte[] stateRoot;
    private byte[] txTrieRoot;
    private byte[] receiptTrieRoot;
    private Coin paidFees;
    private byte[] bitcoinMergedMiningHeader;
    private byte[] bitcoinMergedMiningMerkleProof;
    private byte[] bitcoinMergedMiningCoinbaseTransaction;
    private byte[] miningForkDetectionData;
    private byte[] mergedMiningForkDetectionData;
    private volatile boolean sealed;

    public BlockHeaderBuilder(ActivationConfig activationConfig) {
        this.activationConfig = activationConfig;
    }

    public BlockHeader build() {
        boolean useRskip92Encoding = activationConfig.isActive(ConsensusRule.RSKIP92, number);
        boolean includeForkDetectionData = activationConfig.isActive(ConsensusRule.RSKIP110, number) &&
                mergedMiningForkDetectionData.length > 0;

        return new BlockHeader(
         parentHash, unclesHash, new RskAddress(coinbase),  stateRoot,
         txTrieRoot,  receiptTrieRoot,  logsBloom,  RLP.parseBlockDifficulty(difficulty),
         number,  gasLimit,  gasUsed,  blockTimestamp,  extraData,
         paidFees,  bitcoinMergedMiningHeader,  bitcoinMergedMiningMerkleProof,
         bitcoinMergedMiningCoinbaseTransaction,  mergedMiningForkDetectionData,
         minimumGasPrice,  uncleCount,  sealed,
         useRskip92Encoding,  includeForkDetectionData
        );
    }

    public BlockHeaderBuilder withCoinbase(byte[] coinbase) {
        this.coinbase = coinbase;
        return this;
    }


    public BlockHeaderBuilder withDifficulty(byte[] difficulty) {
        this.difficulty = difficulty;
        return this;
    }

    public BlockHeaderBuilder withNumber(int number) {
        this.number = number;
        return this;
    }

    public BlockHeaderBuilder withGasUsed(int gasUsed) {
        this.gasUsed = gasUsed;
        return this;
    }

    public BlockHeaderBuilder withBlockTimestamp(long blockTimestamp) {
        this.blockTimestamp = blockTimestamp;
        return this;
    }

    public BlockHeaderBuilder withUncleCount(int uncleCount) {
        this.uncleCount = uncleCount;
        return this;
    }

    private BlockHeaderBuilder withParentHash(byte[] parentHash) {
        this.parentHash = parentHash;
        return this;
    }


    private BlockHeaderBuilder withMinimumGasPrice(Coin minimumGasPrice) {
        this.minimumGasPrice = minimumGasPrice;
        return this;
    }


    private BlockHeaderBuilder withExtraData(byte[] extraData) {
        this.extraData = extraData;
        return this;
    }

    private BlockHeaderBuilder withGasLimit(byte[] gasLimit) {
        this.gasLimit = gasLimit;
        return this;
    }



    private BlockHeaderBuilder withLogsBloom(byte[] logsBloom) {
        this.logsBloom = logsBloom;
        return this;
    }

    private BlockHeaderBuilder withUnclesHash(byte[] unclesHash) {
        this.unclesHash = unclesHash;
        return this;
    }

    public BlockHeaderBuilder withReceiptTrieRoot(byte[] receiptTrieRoot) {
        this.receiptTrieRoot = receiptTrieRoot;
        return this;
    }

    public BlockHeaderBuilder withPaidFees(Coin paidFees) {
        this.paidFees = paidFees;
        return this;
    }

    public BlockHeaderBuilder withBitcoinMergedMiningHeader(byte[] bitcoinMergedMiningHeader) {
        this.bitcoinMergedMiningHeader = bitcoinMergedMiningHeader;
        return this;
    }

    public BlockHeaderBuilder withBitcoinMergedMiningMerkleProof(byte[] bitcoinMergedMiningMerkleProof) {
        this.bitcoinMergedMiningMerkleProof = bitcoinMergedMiningMerkleProof;
        return this;
    }

    public BlockHeaderBuilder withBitcoinMergedMiningCoinbaseTransaction(byte[] bitcoinMergedMiningCoinbaseTransaction) {
        this.bitcoinMergedMiningCoinbaseTransaction = bitcoinMergedMiningCoinbaseTransaction;
        return this;
    }

    public BlockHeaderBuilder withMiningForkDetectionData(byte[] miningForkDetectionData) {
        this.miningForkDetectionData = miningForkDetectionData;
        return this;
    }

    public BlockHeaderBuilder withSealed(boolean sealed) {
        this.sealed = sealed;
        return this;
    }


    public BlockHeaderBuilder withTxTrieRoot(byte[] txTrieRoot) {
        this.txTrieRoot = txTrieRoot;
        return this;
    }

    private BlockHeaderBuilder withTxTireRoot(byte[] txTrieRoot) {
        this.txTrieRoot = txTrieRoot;
        return this;
    }


    public BlockHeaderBuilder withStateRoot(byte[] stateRoot) {
        this.stateRoot = stateRoot;
        return this;
    }

    public BlockHeaderBuilder withMergedMiningForkDetectionData(byte[] mergedMiningForkDetectionData) {
        this.mergedMiningForkDetectionData = mergedMiningForkDetectionData;
        return this;
    }
}
