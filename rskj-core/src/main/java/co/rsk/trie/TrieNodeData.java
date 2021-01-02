package co.rsk.trie;

import co.rsk.bitcoinj.core.VarInt;
import co.rsk.core.types.ints.Uint24;
import co.rsk.crypto.Keccak256;

public interface TrieNodeData {

    public int getValueLength();

    // getValue() value CANNOT be null. If there is no data, then the TrieNodeData object must be null
    public byte[] getValue();
    public long getChildrenSize();

    public boolean isNew();

    // if isNew() then getLastRentPaidTime() should NOT be called
    public long getLastRentPaidTime();
    public Keccak256 getValueHash();
    
    /**#mish notes 
     * implemented in rskj-core/src/main/java/co/rsk/db/TrieNodeDataFromCache
     *  - there is has 2 fields, byte[] cachedata (encoded Trie) and timestamp
     * I added a node version field to trie. With getNodeVersion() method. Any use with this interface?
     *  - can be used for explicit checks when updating old nodes (version 1) without timestamps 
     */
}
