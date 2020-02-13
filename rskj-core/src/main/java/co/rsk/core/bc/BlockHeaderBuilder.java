package co.rsk.core.bc;

import org.ethereum.core.BlockFactory;
import org.ethereum.core.BlockHeader;


public class BlockHeaderBuilder {


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
    private byte[] minimumGasPrice;
    private int uncleCount;

    public BlockHeader build() {
        return this.blockFactory.newHeader(
                this.parentHash,
                this.unclesHash,
                this.coinbase,
                this.logsBloom,
                this.difficulty,
                this.number,
                this.gasLimit,
                this.gasUsed,
                this.blockTimestamp,
                this.extraData,
                this.minimumGasPrice,
                this.uncleCount
        );
    }

    public BlockHeaderBuilder buildHeader(BlockFactory blockFactory) {
        this.blockFactory = blockFactory;
        return this;
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


    private BlockHeaderBuilder withMinimumGasPrice(byte[] minimumGasPrice) {
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
}
