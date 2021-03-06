/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package co.rsk.trie;

import co.rsk.bitcoinj.core.VarInt;
import co.rsk.core.types.ints.Uint16;
import co.rsk.core.types.ints.Uint24;
import co.rsk.core.types.ints.Uint8;
import co.rsk.crypto.Keccak256;
import co.rsk.metrics.profilers.Metric;
import co.rsk.metrics.profilers.Profiler;
import co.rsk.metrics.profilers.ProfilerFactory;
import org.ethereum.crypto.Keccak256Helper;
import org.ethereum.db.ByteArrayWrapper;
import org.ethereum.util.ByteUtil;
import org.ethereum.util.RLP;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.ethereum.util.ByteUtil.EMPTY_BYTE_ARRAY;

/**
 * A binary trie node.
 *
 * Each node has an optional associated value (a byte array)
 *
 * A node is referenced via a key (a byte array)
 *
 * A node can be serialized to/from a message (a byte array)
 *
 * A node has a hash (keccak256 of its serialization)
 *
 * A node is immutable: to add/change a value or key, a new node is created
 *
 * An empty node has no subnodes and a null value
 * 
 * ******************
 * #mish: Additional notes for storage rent implementation
 * ******************
 * Add a new field `long lastRentPaidTime` to store a node's rent paid timestamp. 
 * Defaults to "-1" for orchid or node version1, TX trie, receipts trie.. 
 * Also see public method put(k,v) -> putWithRent(k,v,-1)
 * 
 * Added a field for node version: default "-1". "0" is Orchid, "1" is RSKIP107, "2" is node with rent
 * Adding this new field is like adding a new type of `value`.. which means we need to modify put and getMethods
 * - existing put() method(s) replicated putWithRent() which can be used to update rent timestamp
 * 
 * Node version and encoding/hashes
 * - Trie encoding and decoding also changed to account for the additional long field
 * - Changing the encoding changes the hash as well
 * - In this experimental implementation, the pre-existing method are sometimes extended by adding a boolean 
 *          `containsRent` to indicate whether the method should or should not use the rent field.
 * - This approach implies a lot of code duplication/redundancy (intended for easier code review), can be eliminated later after tests  
 */
public class Trie {
    private static final int ARITY = 2;
    //#mish: was 44. Increase by 8 bytes for lastRentPaidTime (node version is part of flags so no increase)
    private static final int MAX_EMBEDDED_NODE_SIZE_IN_BYTES = 52;

    private static final Profiler profiler = ProfilerFactory.getInstance();
    private static final String INVALID_ARITY = "Invalid arity";

    private static final int MESSAGE_HEADER_LENGTH = 2 + Short.BYTES * 2;
    private static final String INVALID_VALUE_LENGTH = "Invalid value length";

    // all zeroed, default hash for empty nodes
    private static Keccak256 emptyHash = makeEmptyHash();

    // this node associated value, if any
    private byte[] value;

    private final NodeReference left;

    private final NodeReference right;

    // this node hash value
    private Keccak256 hash;

    // this node hash value as calculated before RSKIP 107
    // we need to cache it, otherwise TrieConverter is prohibitively slow.
    private Keccak256 hashOrchid;

    // temporary storage of encoding. Removed after save()
    private byte[] encoded;

    // valueLength enables lazy long value retrieval.
    // The length of the data is now stored. This allows EXTCODESIZE to
    // execute much faster without the need to actually retrieve the data.
    // if valueLength>32 and value==null this means the value has not been retrieved yet.
    // if valueLength==0, then there is no value AND no node.
    // This trie structure does not distinguish between empty arrays and nulls.
    // Thus, storing an empty byte array has the same effect as removing the node.
    private final Uint24 valueLength;

    // For lazy retrieval and also for cache.
    private Keccak256 valueHash;

    // the size of this node along with its children (in bytes)
    // note that we use a long because an int would allow only up to 4GB of state to be stored.
    // #mish: int is 32 bits. If this limit is applied to trie root node, 
    // then it limits state size (all children from root) to 2^32 ~ 4GB. 
    private VarInt childrenSize;

    // associated store, to store or retrieve nodes in the trie
    private TrieStore store;

    // shared Path
    private final TrieKeySlice sharedPath;

    // #mish. for storage rent  (RSKIP113): unix seconds since epoch Jan 1970
    // this field included in encoding for version 2 nodes
    private long lastRentPaidTime = -1L; // to avoid default value 0, also used for encoding, and flags
    
    /*node version is included in flags (bit position 6,7)
    * Default "-1" to avoid automatic 0 as default. 
    * Intended use: "1" for rskip107 without rent, "2" with storage rent and "0" for Orchid
    */
    private byte nodeVersion =  (byte) -1; //this does not correspond to any version

    // default constructor, no secure
    /* #mish in main sources this is used (only?) in blockHasheshelper 
     * Used in several tests though. Objects instantiated in this manner
     * will have node-version "-1", storage rent will not be encoded
     * */
    public Trie() {
        this(null);
    }

    public Trie(TrieStore store) {
        this(store, TrieKeySlice.empty(), null);
    }

    private Trie(TrieStore store, TrieKeySlice sharedPath, byte[] value) {
        this(store, sharedPath, value, NodeReference.empty(), NodeReference.empty(), getDataLength(value), null);
    }

    public Trie(TrieStore store, TrieKeySlice sharedPath, byte[] value, NodeReference left, NodeReference right, Uint24 valueLength, Keccak256 valueHash) {
        this(store, sharedPath, value, left, right, valueLength, valueHash, null);
    }

    //  #mish constructor, without storage rent, default to node version 1
    public Trie(TrieStore store, TrieKeySlice sharedPath, byte[] value, NodeReference left, NodeReference right, Uint24 valueLength, Keccak256 valueHash, VarInt childrenSize) {
        this(store, sharedPath, value, left, right, valueLength, valueHash, childrenSize, 0, (byte) 1);
        checkValueLength();
    }
    
    /** #mish constructor with storage rent and node version implicitly set to 1. 
     * If rent timestamp is passed, should be version 2. However, to resuse the 
     * same put method with or without rent, 
     *      - we allow timestamp to be 0L, and 
     *      - default to node version 1 (without rent)
     *  To mark a node as version 2, we must do so explicitly with the full constructor
     */
    private Trie(TrieStore store, TrieKeySlice sharedPath, byte[] value, NodeReference left, NodeReference right, Uint24 valueLength, Keccak256 valueHash, VarInt childrenSize, long lastRentPaidTime) {
        this.value = value;
        this.left = left;
        this.right = right;
        this.store = store;
        this.sharedPath = sharedPath;
        this.valueLength = valueLength;
        this.valueHash = valueHash;
        this.childrenSize = childrenSize;
        this.lastRentPaidTime = lastRentPaidTime;
        this.nodeVersion = (byte) 1;
        checkValueLength();
    }

    // #mish full constructor with storage rent and node version explicit
    private Trie(TrieStore store, TrieKeySlice sharedPath, byte[] value, NodeReference left, NodeReference right, Uint24 valueLength, Keccak256 valueHash, VarInt childrenSize, long lastRentPaidTime, byte nodeVersion) {
        this.value = value;
        this.left = left;
        this.right = right;
        this.store = store;
        this.sharedPath = sharedPath;
        this.valueLength = valueLength;
        this.valueHash = valueHash;
        this.childrenSize = childrenSize;
        this.lastRentPaidTime = lastRentPaidTime;
        this.nodeVersion = nodeVersion;
        checkValueLength();
    }

    /**
     * Deserialize a Trie, either using the original format or RSKIP 107 format, based on version flags.
     * The original trie wasted the first byte by encoding the arity, which was always 2. We use this marker to
     * recognize the old serialization format.
     */
    public static Trie fromMessage(byte[] message, TrieStore store) {
        Trie trie;
        Metric metric = profiler.start(Profiler.PROFILING_TYPE.BUILD_TRIE_FROM_MSG);
        if (message[0] == ARITY) {
            trie = fromMessageOrchid(message, store);
        } else {
            trie = fromMessageRskip107(ByteBuffer.wrap(message), store);//, true);
        }

        profiler.stop(metric);
        return trie;
    }

    /** #mish for future reference (node versioning) modify the return to get a Trie with a rent timestamp of "-1"
     * and a node version "0" for orchid. This does not change the encoding or hash for Orchid
     */
    private static Trie fromMessageOrchid(byte[] message, TrieStore store) {
        int current = 0;
        int arity = message[current];
        current += Byte.BYTES;

        if (arity != ARITY) {
            throw new IllegalArgumentException(INVALID_ARITY);
        }

        int flags = message[current];
        current += Byte.BYTES;

        boolean hasLongVal = (flags & 0x02) == 2;
        int bhashes = Uint16.decodeToInt(message, current);
        current += Uint16.BYTES;
        int lshared = Uint16.decodeToInt(message, current);
        current += Uint16.BYTES;

        TrieKeySlice sharedPath = TrieKeySlice.empty();
        int lencoded = PathEncoder.calculateEncodedLength(lshared);
        if (lencoded > 0) {
            if (message.length - current < lencoded) {
                throw new IllegalArgumentException(String.format(
                        "Left message is too short for encoded shared path expected:%d actual:%d total:%d",
                        lencoded, message.length - current, message.length));
            }
            sharedPath = TrieKeySlice.fromEncoded(message, current, lshared, lencoded);
            current += lencoded;
        }

        int keccakSize = Keccak256Helper.DEFAULT_SIZE_BYTES;
        NodeReference left = NodeReference.empty();
        NodeReference right = NodeReference.empty();

        int nhashes = 0;
        if ((bhashes & 0b01) != 0) {
            Keccak256 nodeHash = readHash(message, current);
            left = new NodeReference(store, null, nodeHash);
            current += keccakSize;
            nhashes++;
        }
        if ((bhashes & 0b10) != 0) {
            Keccak256 nodeHash = readHash(message, current);
            right = new NodeReference(store, null, nodeHash);
            current += keccakSize;
            nhashes++;
        }

        int offset = MESSAGE_HEADER_LENGTH + lencoded + nhashes * keccakSize;
        byte[] value;
        Uint24 lvalue;
        Keccak256 valueHash;

        if (hasLongVal) {
            valueHash = readHash(message, current);
            value = store.retrieveValue(valueHash.getBytes());
            lvalue = new Uint24(value.length);
        } else {
            int remaining = message.length - offset;
            if (remaining > 0) {
                if (message.length - current  < remaining) {
                    throw new IllegalArgumentException(String.format(
                            "Left message is too short for value expected:%d actual:%d total:%d",
                            remaining, message.length - current, message.length));
                }

                value = Arrays.copyOfRange(message, current, current + remaining);
                valueHash = null;
                lvalue = new Uint24(remaining);
            } else {
                value = null;
                valueHash = null;
                lvalue = Uint24.ZERO;
            }
        }
        // it doesn't need to clone value since it's retrieved from store or created from message
        // #mish: orchid unaffected by rent but add rent timestamp -1 and node version 0.
        return new Trie(store, sharedPath, value, left, right, lvalue, valueHash, null, -1L, (byte) 0);
    }

    /** #mish modify previous implementation with a boolean `containsRent`
     * to indicate whether rent timestamp is included and node version (from flags)
    */
    private static Trie fromMessageRskip107(ByteBuffer message, TrieStore store) {
        byte flags = message.get();
        // #mish if we reached here, then it cannot be Orchid node. Either version 1 (no rent) or 2 (rent)
        // #flag for version "2" "0b10" in bit position 6,7 of flags
        // also use (byte) on both sides or in neither.
        boolean containsRent = (byte) (flags & 0b10000000) == (byte) 0b10000000; 

        boolean hasLongVal = (flags & 0b00100000) == 0b00100000;
        boolean sharedPrefixPresent = (flags & 0b00010000) == 0b00010000;
        boolean leftNodePresent = (flags & 0b00001000) == 0b00001000;
        boolean rightNodePresent = (flags & 0b00000100) == 0b00000100;
        boolean leftNodeEmbedded = (flags & 0b00000010) == 0b00000010;
        boolean rightNodeEmbedded = (flags & 0b00000001) == 0b00000001;

        //#mish if rent timestamp present
        long lastRentPaidTime = 0L;
        if (containsRent) { lastRentPaidTime = message.getLong(); }

        TrieKeySlice sharedPath = SharedPathSerializer.deserialize(message, sharedPrefixPresent);

        NodeReference left = NodeReference.empty();
        NodeReference right = NodeReference.empty();
        if (leftNodePresent) {
            if (leftNodeEmbedded) {
                byte[] lengthBytes = new byte[Uint8.BYTES];
                message.get(lengthBytes); //read get (of required length) into lengthBytes
                Uint8 length = Uint8.decode(lengthBytes, 0);

                byte[] serializedNode = new byte[length.intValue()];
                message.get(serializedNode); //read serialized data for embedded node into the buffer serializedNode
                //make a recursive call. Also embedding is limited to one layer, so recursion is limited.
                Trie node = fromMessageRskip107(ByteBuffer.wrap(serializedNode), store);
                left = new NodeReference(store, node, null);
            } else { //not embedded, grab the hash and assign to the new left pointer 
                byte[] valueHash = new byte[Keccak256Helper.DEFAULT_SIZE_BYTES];
                message.get(valueHash);
                Keccak256 nodeHash = new Keccak256(valueHash);
                left = new NodeReference(store, null, nodeHash);
            }
        }

        if (rightNodePresent) {
            if (rightNodeEmbedded) {
                byte[] lengthBytes = new byte[Uint8.BYTES];
                message.get(lengthBytes);
                Uint8 length = Uint8.decode(lengthBytes, 0);

                byte[] serializedNode = new byte[length.intValue()];
                message.get(serializedNode);
                Trie node = fromMessageRskip107(ByteBuffer.wrap(serializedNode), store);
                right = new NodeReference(store, node, null);
            } else {
                byte[] valueHash = new byte[Keccak256Helper.DEFAULT_SIZE_BYTES];
                message.get(valueHash);
                Keccak256 nodeHash = new Keccak256(valueHash);
                right = new NodeReference(store, null, nodeHash);
            }
        }

        VarInt childrenSize = new VarInt(0);
        if (leftNodePresent || rightNodePresent) {
            childrenSize = readVarInt(message);     //reads without changing buffer position
        }

        byte[] value;
        Uint24 lvalue;
        Keccak256 valueHash;

        if (hasLongVal) {
            value = null;   //bcos it's long! grab the value hash and length only, to save on IO
            byte[] valueHashBytes = new byte[Keccak256Helper.DEFAULT_SIZE_BYTES]; // 256/8 = 32 (checked April 2020)
            message.get(valueHashBytes);
            valueHash = new Keccak256(valueHashBytes);
            byte[] lvalueBytes = new byte[Uint24.BYTES];
            message.get(lvalueBytes);
            lvalue = Uint24.decode(lvalueBytes, 0);
        } else {
            int remaining = message.remaining();
            if (remaining != 0) {
                value = new byte[remaining];
                message.get(value);
                valueHash = new Keccak256(Keccak256Helper.keccak256(value));
                lvalue = new Uint24(remaining);
            } else {
                value = null;
                valueHash = null;
                lvalue = Uint24.ZERO;
            }
        }

        if (message.hasRemaining()) {
            throw new IllegalArgumentException("The message had more data than expected");
        }
        if (containsRent) { 
            return new Trie(store, sharedPath, value, left, right, lvalue, valueHash, childrenSize, lastRentPaidTime, (byte) 2);
        } else { // rskip107 without rent timestamp, node version 1, lrpt -1
            return new Trie(store, sharedPath, value, left, right, lvalue, valueHash, childrenSize, -1L,(byte) 1);
        }

    }

    /**
     * getHash calculates and/or returns the hash associated with this node content
     *
     * the internal variable hash could contains the cached hash.
     *
     * This method is not synchronized because the result of it's execution
     *
     * disregarding the lazy initialization is idempotent. It's better to keep
     *
     * it out of synchronized.
     *
     * @return  a byte array with the node serialized to bytes
     */
    public Keccak256 getHash() {
        if (this.hash != null) {
            return this.hash.copy();
        }

        if (isEmptyTrie()) {
            return emptyHash.copy();
        }

        byte[] message = this.toMessage();

        this.hash = new Keccak256(Keccak256Helper.keccak256(message));

        return this.hash.copy();
    }

    // #mish added this version with boolean parameter to indicate if the encoding (and thus the hash)
    // should include rent timestamp. Otherwise, when testing, transaction or receipts trie roots won't match
    /*
    public Keccak256 getHash(boolean containsRent) {
        return this.getHash(); //#mish default is to include rent in encoding
    }
    */

    /**
     * The hash based on pre-RSKIP 107 serialization
     */
    public Keccak256 getHashOrchid(boolean isSecure) {
        if (this.hashOrchid != null) {
            return this.hashOrchid.copy();
        }

        if (isEmptyTrie()) {
            return emptyHash.copy();
        }

        byte[] message = this.toMessageOrchid(isSecure);

        this.hashOrchid = new Keccak256(Keccak256Helper.keccak256(message));

        return this.hashOrchid.copy();
    }

    /**
     * get returns the value associated with a key
     *
     * @param key the key associated with the value, a byte array (variable length)
     *
     * @return  the associated value, a byte array, or null if there is no associated value to the key
     */
    @Nullable
    public byte[] get(byte[] key) {
        Metric metric = profiler.start(Profiler.PROFILING_TYPE.TRIE_GET_VALUE_FROM_KEY);
        Trie node = find(key);
        if (node == null) {
            profiler.stop(metric);
            return null;
        }

        byte[] result = node.getValue();
        profiler.stop(metric);
        return result;
    }

    /**
     * get by string, utility method used from test methods
     *
     * @param key   a string, that is converted to a byte array
     * @return a byte array with the associated value
     */
    public byte[] get(String key) {
        return this.get(key.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * put key value association, returning a new NewTrie
     *
     * @param key   key to be updated or created, a byte array
     * @param value value to associated to the key, a byte array
     *
     * @return a new NewTrie node, the top node of the new tree having the
     * key-value association. The original node is immutable, a new tree
     * is build, adding some new nodes
     */
    public Trie put(byte[] key, byte[] value) {
        TrieKeySlice keySlice = TrieKeySlice.fromKey(key);
        //Trie trie = put(keySlice, value, false);
        //#mish use the same method for put with or without rent timestamp
        // calling with timestamp set to -1L will indicate nodeVersion 1 (no rent)
        Trie trie = putWithRent(keySlice, value, false, -1L);

        return trie == null ? new Trie(this.store) : trie;
    }

    public Trie put(ByteArrayWrapper key, byte[] value) {
        return put(key.getData(), value);
    }
    /**
     * put string key to value, the key is converted to byte array
     * utility method to be used from testing
     *
     * @param key   a string
     * @param value an associated value, a byte array
     *
     * @return  a new NewTrie, the top node of a new trie having the key
     * value association
     */
    public Trie put(String key, byte[] value) {
        return put(key.getBytes(StandardCharsets.UTF_8), value);
    }

    /**
     * delete update the key to null value
     *
     * @param key   a byte array
     *
     * @return the new top node of the trie with the association removed
     *
     */
    public Trie delete(byte[] key) {
        return put(key, null);
    }

    // This is O(1). The node with exact key "key" MUST exists.
    public Trie deleteRecursive(byte[] key) {
        TrieKeySlice keySlice = TrieKeySlice.fromKey(key);
        //Trie trie = put(keySlice, null, true);
        //#mish use same method as for put with rent timestamp, 
        // but use timestamp = -1L to indicare version 1 node 
        Trie trie = putWithRent(keySlice, null, true, -1L);

        return trie == null ? new Trie(this.store) : trie;
    }

    /**
     * delete string key, utility method to be used for testing
     *
     * @param key a string
     *
     * @return the new top node of the trie with the key removed
     */
    public Trie delete(String key) {
        return delete(key.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * toMessage serialize the node to bytes. Used to persist the node in a key-value store
     * like levelDB or redis.
     *
     * The serialization includes:
     * - arity: byte
     * - bits with present hashes: two bytes (example: 0x0203 says that the node has
     * hashes at index 0, 1, 9 (the other subnodes are null)
     * - present hashes: 32 bytes each
     * - associated value: remainder bytes (no bytes if null)
     *
     * @return a byte array with the serialized info
     */
    public byte[] toMessage() {
        if (encoded == null) {
            internalToMessage(); //#mish: default is to include storage rent field in encoding
        }

        return cloneArray(encoded);
    }

    public int getMessageLength() {
        if (encoded == null) {
            internalToMessage();
        }

        return encoded.length;
    }

    /**
     * Serialize the node to bytes with the pre-RSKIP 107 format
     */
    public byte[] toMessageOrchid(boolean isSecure) {
        Uint24 lvalue = this.valueLength;
        int lshared = this.sharedPath.length();
        int lencoded = PathEncoder.calculateEncodedLength(lshared);
        boolean hasLongVal = this.hasLongValue();
        Optional<Keccak256> leftHashOpt = this.left.getHashOrchid(isSecure);
        Optional<Keccak256> rightHashOpt = this.right.getHashOrchid(isSecure);

        int nnodes = 0;
        int bits = 0;
        if (leftHashOpt.isPresent()) {
            bits |= 0b01;
            nnodes++;
        }

        if (rightHashOpt.isPresent()) {
            bits |= 0b10;
            nnodes++;
        }

        ByteBuffer buffer = ByteBuffer.allocate(MESSAGE_HEADER_LENGTH  + (lshared > 0 ? lencoded: 0)
                + nnodes * Keccak256Helper.DEFAULT_SIZE_BYTES
                + (hasLongVal ? Keccak256Helper.DEFAULT_SIZE_BYTES : lvalue.intValue()));

        buffer.put((byte) ARITY);

        byte flags = 0;

        if (isSecure) {
            flags |= 1;
        }

        if (hasLongVal) {
            flags |= 2;
        }

        buffer.put(flags);
        buffer.putShort((short) bits);
        buffer.putShort((short) lshared);

        if (lshared > 0) {
            buffer.put(this.sharedPath.encode());
        }

        leftHashOpt.ifPresent(h -> buffer.put(h.getBytes()));

        rightHashOpt.ifPresent(h -> buffer.put(h.getBytes()));

        if (lvalue.compareTo(Uint24.ZERO) > 0) {
            if (hasLongVal) {
                buffer.put(this.getValueHash().getBytes());
            }
            else {
                buffer.put(this.getValue());
            }
        }

        return buffer.array();
    }

    // This method should only be called DURING save(). It should not be called in other places
    // because it will expand the node encoding in a memory cache that is ONLY removed after save()
    public boolean isEmbeddable() {
        return isTerminal() && getMessageLength() <= MAX_EMBEDDED_NODE_SIZE_IN_BYTES;
    }

    // key is the key with exactly collectKeyLen bytes.
    // in non-expanded form (binary)
    // special value Integer.MAX_VALUE means collect them all.
    private void collectKeys(Set<ByteArrayWrapper> set, TrieKeySlice key, int collectKeyLen) {
        if (collectKeyLen != Integer.MAX_VALUE && key.length() > collectKeyLen) {
            return;
        }

        boolean shouldCollect = collectKeyLen == Integer.MAX_VALUE || key.length() == collectKeyLen;
        if (valueLength.compareTo(Uint24.ZERO) > 0 && shouldCollect) {
            // convert bit string into byte[]
            set.add(new ByteArrayWrapper(key.encode()));
        }

        for (byte k = 0; k < ARITY; k++) {
            Trie node = this.retrieveNode(k);

            if (node == null) {
                continue;
            }

            TrieKeySlice nodeKey = key.rebuildSharedPath(k, node.getSharedPath());
            node.collectKeys(set, nodeKey, collectKeyLen);
        }
    }

    // Special value Integer.MAX_VALUE means collect them all.
    public Set<ByteArrayWrapper> collectKeys(int byteSize) {
        Set<ByteArrayWrapper> set = new HashSet<>();

        int bitSize;
        if (byteSize == Integer.MAX_VALUE) {
            bitSize = Integer.MAX_VALUE;
        } else {
            bitSize = byteSize * 8;
        }

        collectKeys(set, getSharedPath(), bitSize);
        return set;
    }

    /**
     * trieSize returns the number of nodes in trie
     *
     * @return the number of tries nodes, includes the current one
     */
    public int trieSize() {
        return 1 + this.left.getNode().map(Trie::trieSize).orElse(0)
                + this.right.getNode().map(Trie::trieSize).orElse(0);
    }

    
    @Nullable
    public Trie find(byte[] key) {
        return find(TrieKeySlice.fromKey(key));
    }

    @Nullable
    private Trie find(TrieKeySlice key) {
        if (sharedPath.length() > key.length()) {
            return null;
        }

        int commonPathLength = key.commonPath(sharedPath).length();
        if (commonPathLength < sharedPath.length()) {
            return null;
        }

        if (commonPathLength == key.length()) {
            return this;
        }

        Trie node = this.retrieveNode(key.get(commonPathLength));
        if (node == null) {
            return null;
        }

        return node.find(key.slice(commonPathLength + 1, key.length()));
    }
    
    private void internalToMessage() {
        //#mish node version
        boolean containsRent = this.nodeVersion == (byte) 2;

        Uint24 lvalue = this.valueLength;
        boolean hasLongVal = this.hasLongValue();

        SharedPathSerializer sharedPathSerializer = new SharedPathSerializer(this.sharedPath);
        VarInt childrenSize = getChildrenSize();

        int bufLength = 1 + // flags
                        sharedPathSerializer.serializedLength() +
                        this.left.serializedLength() +  //this method is from NodeReference.java (should only be used for save)
                        this.right.serializedLength() +
                        (this.isTerminal() ? 0 : childrenSize.getSizeInBytes()) +
                        (hasLongVal ? Keccak256Helper.DEFAULT_SIZE_BYTES + Uint24.BYTES : lvalue.intValue());
        
        if (containsRent) { bufLength += 8;} //increase length by 8 to accomodate (long) rent timestamp

        ByteBuffer buffer = ByteBuffer.allocate(bufLength);

        // node serialization version: 01 //see RSKIP107
        byte flags;
        if (containsRent) {
            flags = (byte) 0b10000000; //version 2 (with storage rent timestamp)
        } else {
            flags = (byte) 0b01000000; //version 1 (RSKIP107)
        }
        
        if (hasLongVal) {
            flags = (byte) (flags | 0b00100000);   //bit 5 long value
        }

        if (sharedPathSerializer.isPresent()) {     //bit 4 sharedprefix present
            flags = (byte) (flags | 0b00010000);
        }

        if (!this.left.isEmpty()) {
            flags = (byte) (flags | 0b00001000);    // bit 2,3 left/right child present
        }

        if (!this.right.isEmpty()) {
            flags = (byte) (flags | 0b00000100);    // bit 0,1 left/right embedded
        }

        if (this.left.isEmbeddable()) {
            flags = (byte) (flags | 0b00000010);
        }

        if (this.right.isEmbeddable()) {
            flags = (byte) (flags | 0b00000001);
        }

        buffer.put(flags);

        if (containsRent) { buffer.putLong(this.lastRentPaidTime); } //put rent timestamp into encoding

        sharedPathSerializer.serializeInto(buffer);

        this.left.serializeInto(buffer);    //method from NodeReference.java

        this.right.serializeInto(buffer);

        if (!this.isTerminal()) {
            buffer.put(childrenSize.encode());
        }

        //if longvalue, then just put the valuehash and valuelength. Else grab the value itself.
        if (hasLongVal) {
            buffer.put(this.getValueHash().getBytes());
            buffer.put(lvalue.encode());
        } else if (lvalue.compareTo(Uint24.ZERO) > 0) {
            buffer.put(this.getValue());
        }
        encoded = buffer.array();
    }


    private Trie retrieveNode(byte implicitByte) {
        return getNodeReference(implicitByte).getNode().orElse(null);
    }

    public NodeReference getNodeReference(byte implicitByte) {
        return implicitByte == 0 ? this.left : this.right;
    }

    public NodeReference getLeft() {
        return left;
    }

    public NodeReference getRight() {
        return right;
    }

    /**
     * put key with associated value, returning a new NewTrie
     *
     * @param key   key to be updated
     * @param value     associated value
     *
     * @return the new NewTrie containing the tree with the new key value association
     *
     */

    
    private static Uint24 getDataLength(byte[] value) {
        if (value == null) {
            return Uint24.ZERO;
        }

        return new Uint24(value.length);
    }



    /* #mish: version of put to update rent paid time: 
     * duplicated existing code and then track the value (do the same for rent)
     * could have used the same name 'put' with overloading. Introducing new name for emphasis.
     * also, for security, value must be passed explicitly, even if unchanged*/
    public Trie putWithRent(byte[] key, byte[] value, long newLastRentPaidTime) {
        TrieKeySlice keySlice = TrieKeySlice.fromKey(key);
        Trie trie = putWithRent(keySlice, value, false, newLastRentPaidTime);

        return trie == null ? new Trie(this.store) : trie;
    }

    //some tests use strings for keys
    public Trie putWithRent(String key, byte[] value, long newLastRentPaidTime) {
        return putWithRent(key.getBytes(StandardCharsets.UTF_8), value, newLastRentPaidTime);
    }

    private Trie putWithRent(TrieKeySlice key, byte[] value, boolean isRecursiveDelete, long newLastRentPaidTime) {
        // First of all, setting the value as an empty byte array is equivalent
        // to removing the key/value. This is because other parts of the trie make
        // this equivalent. Use always null to mark a node for deletion.
        if (value != null && value.length == 0) {
            value = null;
        }
        byte nodeVer;
        if (newLastRentPaidTime == -1L){ //#mish this is how it will be called from put() 
            nodeVer = (byte) 1; //node Version 1
        } else {
            nodeVer = (byte) 2; //node Version 2 // this can update node version (e.g. Orchid or version 1)
        }

        Trie trie = this.internalputWithRent(key, value, isRecursiveDelete, newLastRentPaidTime, nodeVer);

        // the following code coalesces nodes if needed for delete operation

        // it's null or it is not a delete operation
        if (trie == null || value != null) {
            return trie;
        }

        if (trie.isEmptyTrie()) {
            return null;
        }

        // only coalesce if node has only one child and no value
        // suppose there is a value
        if (trie.valueLength.compareTo(Uint24.ZERO) > 0) {
            return trie;
        }
        // no value.. still need to ensure only one child.
        Optional<Trie> leftOpt = trie.left.getNode();
        Optional<Trie> rightOpt = trie.right.getNode();
        // if both present, return
        if (leftOpt.isPresent() && rightOpt.isPresent()) {
            return trie;
        }
        // if both absent, return.
        if (!leftOpt.isPresent() && !rightOpt.isPresent()) {
            return trie;
        }
        // now we have a node with a value and exactly one child, proceed with combining/coalescing.
        Trie child;
        byte childImplicitByte;
        
         // leftOpt/rightOpt are optional lists of tries, so get() below is just usual list method
        if (leftOpt.isPresent()) {
            child = leftOpt.get();
            childImplicitByte = (byte) 0;
        } else { // has right node
            child = rightOpt.get();
            childImplicitByte = (byte) 1;
        }
        // reconstruct the shared path
        TrieKeySlice newSharedPath = trie.sharedPath.rebuildSharedPath(childImplicitByte, child.sharedPath);
        return new Trie(child.store, newSharedPath, child.value, child.left, child.right, child.valueLength,
                            child.valueHash, child.childrenSize, child.lastRentPaidTime, child.nodeVersion);
    }

    // #mish: duplicated and extended internalPut to update a node's rent timestamp and explicit nodeVersion
    private Trie internalputWithRent(TrieKeySlice key, byte[] value, boolean isRecursiveDelete, long newLastRentPaidTime, byte nodeVer) {
        // #mish find the common path between the given key and the current node's (top of the trie) sharedpath
        TrieKeySlice commonPath = key.commonPath(sharedPath);

        if (commonPath.length() < sharedPath.length()) {
            // when we are removing a key we know splitting is not necessary. the key wasn't found at this point.
            if (value == null) {
                return this;
            }
            // #mish Add rent paid timestamp for internal nodes 
            // first split the trie
            Trie splitTrie = this.split(commonPath);
            // then mark the timestamp of the node whose creation (put) is causing the split
            splitTrie.lastRentPaidTime = newLastRentPaidTime;
            if (newLastRentPaidTime == -1L){ //was a put
                splitTrie.nodeVersion = (byte)1;
            }else{
                splitTrie.nodeVersion = (byte)2;
            }    
            // then carry on with the put
            return splitTrie.putWithRent(key, value, isRecursiveDelete, newLastRentPaidTime);
        }

        if (sharedPath.length() >= key.length()) {
            // To compare values we need to retrieve the previous value
            // if not already done so. We could also compare by hash, to avoid retrieval
            // We do a small optimization here: if sizes are not equal, then values
            // obviously are not.
            if (this.valueLength.equals(getDataLength(value)) && Arrays.equals(this.getValue(), value)) {
                //values are equal..  if rent paid time is also unchanged, then return
                if (this.lastRentPaidTime == newLastRentPaidTime){
                    return this;
                }
            }

            if (isRecursiveDelete) {
                return new Trie(this.store, this.sharedPath, null);
            }

            if (isEmptyTrie(getDataLength(value), this.left, this.right)) {
                return null;
            }
        
            return new Trie(
                    this.store,
                    this.sharedPath,
                    cloneArray(value),
                    this.left,
                    this.right,
                    getDataLength(value),
                    null,
                    null,
                    newLastRentPaidTime,
                    nodeVer
            );
        }

        if (isEmptyTrie()) {
            return new Trie(this.store, key, cloneArray(value), NodeReference.empty(), NodeReference.empty(),
                             getDataLength(value), null, null, newLastRentPaidTime, nodeVer);
        }

        // this bit will be implicit and not present in a shared path
        byte pos = key.get(sharedPath.length());

        Trie node = retrieveNode(pos);
        if (node == null) {
            node = new Trie(this.store);
        }

        TrieKeySlice subKey = key.slice(sharedPath.length() + 1, key.length());
        Trie newNode = node.putWithRent(subKey, value, isRecursiveDelete, newLastRentPaidTime);

        // reference equality
        if (newNode == node) {
            return this;
        }

        NodeReference newNodeReference = new NodeReference(this.store, newNode, null);
        NodeReference newLeft;
        NodeReference newRight;
        if (pos == 0) {
            newLeft = newNodeReference;
            newRight = this.right;
        } else {
            newLeft = this.left;
            newRight = newNodeReference;
        }

        if (isEmptyTrie(this.valueLength, newLeft, newRight)) {
            return null;
        }
        return new Trie(this.store, this.sharedPath, this.value, newLeft, newRight, this.valueLength, this.valueHash, null,
                                this.lastRentPaidTime, this.nodeVersion);
    } 

    private Trie split(TrieKeySlice commonPath) {
        int commonPathLength = commonPath.length();
        TrieKeySlice newChildSharedPath = sharedPath.slice(commonPathLength + 1, sharedPath.length());
        Trie newChildTrie = new Trie(this.store, newChildSharedPath, this.value, this.left, this.right, this.valueLength,
         this.valueHash, null, this.lastRentPaidTime, this.nodeVersion);
        NodeReference newChildReference = new NodeReference(this.store, newChildTrie, null);

        // this bit will be implicit and not present in a shared path
        byte pos = sharedPath.get(commonPathLength);

        NodeReference newLeft;
        NodeReference newRight;
        if (pos == 0) {
            newLeft = newChildReference;
            newRight = NodeReference.empty();
        } else {
            newLeft = NodeReference.empty();
            newRight = newChildReference;
        }
        //#mish internal node (value is "null"), temporarily assign the rent timestamp and
        // node version of the node being split.. replace LATER with (after split is called) 
        // with timestamp of the node whose creation (i.e. put) caused this split
        return new Trie(this.store, commonPath, null, newLeft, newRight, Uint24.ZERO, null,  null,
                                                             this.lastRentPaidTime, this.nodeVersion);//TEMPORARY timestamp
    }

    public boolean isTerminal() {
        return this.left.isEmpty() && this.right.isEmpty();
    }

    public boolean isEmptyTrie() {
        return isEmptyTrie(this.valueLength, this.left, this.right);
    }

    /** CHeck if a node is internal (not terminal, and no value) 
     * #mish introduced for storage rent. SDL wants timestamps for internal nodes. 
     * These can be used as a reference timestamp for trie misses 
     * (e.g. computing IO penality for non-existent node). 
     * Tracking rent for internal nodes increases storage. 
     * Later, a varInt can be used along with delta compression algorithm
     * */ 
    public boolean isInternalTrieNode(){
        if (this.isTerminal()) {return false;} //by definition
         // value-containing nodes do not count as internal, e.g. storage root has value 0x01
        if (this.valueLength.compareTo(Uint24.ZERO) > 0) {
            return false;
        }
        return true;
    }

    /**
     * isEmptyTrie checks the existence of subnodes, subnodes hashes or value
     *
     * @param valueLength     length of current value
     * @param left      a reference to the left node
     * @param right     a reference to the right node
     *
     * @return true if no data
     */
    private static boolean isEmptyTrie(Uint24 valueLength, NodeReference left, NodeReference right) {
        if (valueLength.compareTo(Uint24.ZERO) > 0) {
            return false;
        }

        return left.isEmpty() && right.isEmpty();
    }

    public boolean hasLongValue() {
        return this.valueLength.compareTo(new Uint24(32)) > 0;
    }

    public Uint24 getValueLength() {
        return this.valueLength;
    }

    public Keccak256 getValueHash() {
        // For empty values (valueLength==0) we return the null hash because
        // in this trie empty arrays cannot be stored.
        if (valueHash == null && valueLength.compareTo(Uint24.ZERO) > 0) {
            valueHash = new Keccak256(Keccak256Helper.keccak256(getValue()));
        }

        return valueHash;
    }

    public byte[] getValue() {
        if (value == null && valueLength.compareTo(Uint24.ZERO) > 0) { // i.e. a long value
            value = retrieveLongValue(); // see triestoreImpl.save() long values saved with own hash as key.. not part of node serialization
            checkValueLengthAfterRetrieve();
        }

        return cloneArray(value);
    }

    /**
     * @return the tree size in bytes as specified in RSKIP107
     *
     * This method will EXPAND internal encoding caches without removing them afterwards.
     * It shouldn't be called from outside. It's still public for NodeReference call
     *
     */
    public VarInt getChildrenSize() {
        if (childrenSize == null) {
            if (isTerminal()) {
                childrenSize = new VarInt(0);
            } else {
                childrenSize = new VarInt(this.left.referenceSize() + this.right.referenceSize());
            }
        }

        return childrenSize;
    }

    private byte[] retrieveLongValue() { //long values are stored in trieStore with their own hash as the key (not part of node serialization)
        return store.retrieveValue(getValueHash().getBytes());
    }

    private void checkValueLengthAfterRetrieve() {
        // At this time value==null and value.length!=null is really bad.
        if (value == null && valueLength.compareTo(Uint24.ZERO) > 0) {
            // Serious DB inconsistency here
            throw new IllegalArgumentException(INVALID_VALUE_LENGTH);
        }

        checkValueLength();
    }

    private void checkValueLength() {
        if (value != null && value.length != valueLength.intValue()) {
            // Serious DB inconsistency here
            throw new IllegalArgumentException(INVALID_VALUE_LENGTH);
        }

        if (value == null && valueLength.compareTo(Uint24.ZERO) > 0 && valueHash == null) {
            // Serious DB inconsistency here
            throw new IllegalArgumentException(INVALID_VALUE_LENGTH);
        }
    }

    public TrieKeySlice getSharedPath() {
        return sharedPath;
    }

    public Iterator<IterationElement> getInOrderIterator() {
        return new InOrderIterator(this);
    }

    public Iterator<IterationElement> getPreOrderIterator() {
        return new PreOrderIterator(this);
    }

    private static byte[] cloneArray(byte[] array) {
        return array == null ? null : Arrays.copyOf(array, array.length);
    }

    public Iterator<IterationElement> getPostOrderIterator() {
        return new PostOrderIterator(this);
    }

    /**
     * makeEmptyHash creates the hash associated to empty nodes
     *
     * @return a hash with zeroed bytes
     */
    private static Keccak256 makeEmptyHash() {
        return new Keccak256(Keccak256Helper.keccak256(RLP.encodeElement(EMPTY_BYTE_ARRAY)));
    }

    private static Keccak256 readHash(byte[] bytes, int position) {
        int keccakSize = Keccak256Helper.DEFAULT_SIZE_BYTES;
        if (bytes.length - position < keccakSize) {
            throw new IllegalArgumentException(String.format(
                    "Left message is too short for hash expected:%d actual:%d total:%d",
                    keccakSize, bytes.length - position, bytes.length
            ));
        }

        return new Keccak256(Arrays.copyOfRange(bytes, position, position + keccakSize));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getHash());
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (other == null || this.getClass() != other.getClass()) {
            return false;
        }

        Trie otherTrie = (Trie) other;
        return getHash().equals(otherTrie.getHash());
    }

    //override the internal Java toString() method
    @Override
    public String toString() {
        String s = printParam("TRIE: ", "value", getValue());
        s = printParam(s, "hash0", left.getHash().orElse(null));
        s = printParam(s, "hash1", right.getHash().orElse(null));
        s = printParam(s, "hash", getHash());
        s = printParam(s, "valueHash", getValueHash());
        s = printParam(s, "encodedSharedPath", sharedPath.encode());
        s += "sharedPathLength: " + sharedPath.length() + "\n";
        return s;
    }

    private String printParam(String s, String name, byte[] param) {
        if (param != null) {
            s += name + ": " + ByteUtil.toHexString(param) + "\n";
        }
        return s;
    }

    private String printParam(String s, String name, Keccak256 param) {
        if (param != null) {
            s += name + ": " + param.toHexString() + "\n";
        }
        return s;
    }

    private static VarInt readVarInt(ByteBuffer message) {
        // read without touching the buffer position so when we read into bytes it contains the header
        int first = Byte.toUnsignedInt(message.get(message.position()));
        byte[] bytes;
        if (first < 253) {
            bytes = new byte[1];
        } else if (first == 253) {
            bytes = new byte[3];
        } else if (first == 254) {
            bytes = new byte[5];
        } else {
            bytes = new byte[9];
        }

        message.get(bytes);
        return new VarInt(bytes, 0);
    }

    /**
     * Returns the leftmost node that has not yet been visited that node is normally on top of the stack
     */
    private static class InOrderIterator implements Iterator<IterationElement> {

        private final Deque<IterationElement> visiting;

        public InOrderIterator(Trie root) {
            Objects.requireNonNull(root);
            TrieKeySlice traversedPath = root.getSharedPath();
            this.visiting = new LinkedList<>();
            // find the leftmost node, pushing all the intermediate nodes onto the visiting stack
            visiting.push(new IterationElement(traversedPath, root));
            pushLeftmostNode(traversedPath, root);
            // now the leftmost unvisited node is on top of the visiting stack
        }

        /**
         * return the leftmost node that has not yet been visited that node is normally on top of the stack
         */
        @Override
        @SuppressWarnings("squid:S2272") // NoSuchElementException is thrown by {@link Deque#pop()} when it's empty
        public IterationElement next() {
            IterationElement visitingElement = visiting.pop();
            Trie node = visitingElement.getNode();
            // if the node has a right child, its leftmost node is next
            Trie rightNode = node.retrieveNode((byte) 0x01);
            if (rightNode != null) {
                TrieKeySlice rightNodeKey = visitingElement.getNodeKey().rebuildSharedPath((byte) 0x01, rightNode.getSharedPath());
                visiting.push(new IterationElement(rightNodeKey, rightNode)); // push the right node
                // find the leftmost node of the right child
                pushLeftmostNode(rightNodeKey, rightNode);
                // note "node" has been replaced on the stack by its right child
            } // else: no right subtree, go back up the stack
            // next node on stack will be next returned
            return visitingElement;
        }

        @Override
        public boolean hasNext() {
            return !visiting.isEmpty(); // no next node left
        }

        /**
         * Find the leftmost node from this root, pushing all the intermediate nodes onto the visiting stack
         *
         * @param nodeKey
         * @param node the root of the subtree for which we are trying to reach the leftmost node
         */
        private void pushLeftmostNode(TrieKeySlice nodeKey, Trie node) {
            // find the leftmost node
            Trie leftNode = node.retrieveNode((byte) 0x00);
            if (leftNode != null) {
                TrieKeySlice leftNodeKey = nodeKey.rebuildSharedPath((byte) 0x00, leftNode.getSharedPath());
                visiting.push(new IterationElement(leftNodeKey, leftNode)); // push the left node
                pushLeftmostNode(leftNodeKey, leftNode); // recurse on next left node
            }
        }
    }

    private class PreOrderIterator implements Iterator<IterationElement> {

        private final Deque<IterationElement> visiting;

        public PreOrderIterator(Trie root) {
            Objects.requireNonNull(root);
            TrieKeySlice traversedPath = root.getSharedPath();
            this.visiting = new LinkedList<>();
            this.visiting.push(new IterationElement(traversedPath, root));
        }

        @Override
        @SuppressWarnings("squid:S2272") // NoSuchElementException is thrown by {@link Deque#pop()} when it's empty
        public IterationElement next() {
            IterationElement visitingElement = visiting.pop();
            Trie node = visitingElement.getNode();
            TrieKeySlice nodeKey = visitingElement.getNodeKey();
            // need to visit the left subtree first, then the right since a stack is a LIFO, push the right subtree first,
            // then the left
            Trie rightNode = node.retrieveNode((byte) 0x01);
            if (rightNode != null) {
                TrieKeySlice rightNodeKey = nodeKey.rebuildSharedPath((byte) 0x01, rightNode.getSharedPath());
                visiting.push(new IterationElement(rightNodeKey, rightNode));
            }
            Trie leftNode = node.retrieveNode((byte) 0x00);
            if (leftNode != null) {
                TrieKeySlice leftNodeKey = nodeKey.rebuildSharedPath((byte) 0x00, leftNode.getSharedPath());
                visiting.push(new IterationElement(leftNodeKey, leftNode));
            }
            // may not have pushed anything.  If so, we are at the end
            return visitingElement;
        }

        @Override
        public boolean hasNext() {
            return !visiting.isEmpty(); // no next node left
        }
    }

    private class PostOrderIterator implements Iterator<IterationElement> {

        private final Deque<IterationElement> visiting;
        private final Deque<Boolean> visitingRightChild;

        public PostOrderIterator(Trie root) {
            Objects.requireNonNull(root);
            TrieKeySlice traversedPath = root.getSharedPath();
            this.visiting = new LinkedList<>();
            this.visitingRightChild = new LinkedList<>();
            // find the leftmost node, pushing all the intermediate nodes onto the visiting stack
            visiting.push(new IterationElement(traversedPath, root));
            visitingRightChild.push(Boolean.FALSE);
            pushLeftmostNodeRecord(traversedPath, root);
            // the node on top of the visiting stack is the next one to be visited, unless it has a right subtree
        }

        @Override
        public boolean hasNext() {
            return !visiting.isEmpty(); // no next node left
        }

        @Override
        @SuppressWarnings("squid:S2272") // NoSuchElementException is thrown by {@link Deque#element()} when it's empty
        public IterationElement next() {
            IterationElement visitingElement = visiting.element();
            Trie node = visitingElement.getNode();
            Trie rightNode = node.retrieveNode((byte) 0x01);
            if (rightNode == null || visitingRightChild.peek()) { // no right subtree, or right subtree already visited
                // already visited right child, time to visit the node on top
                visiting.removeFirst(); // it was already picked
                visitingRightChild.removeFirst(); // it was already picked
                return visitingElement;
            } else { // now visit this node's right subtree
                // mark that we're visiting this element's right subtree
                visitingRightChild.removeFirst();
                visitingRightChild.push(Boolean.TRUE);

                TrieKeySlice rightNodeKey = visitingElement.getNodeKey().rebuildSharedPath((byte) 0x01, rightNode.getSharedPath());
                visiting.push(new IterationElement(rightNodeKey, rightNode)); // push the right node
                visitingRightChild.push(Boolean.FALSE); // we're visiting the left subtree of the right node

                // now push everything down to the leftmost node in the right subtree
                pushLeftmostNodeRecord(rightNodeKey, rightNode);
                return next(); // use recursive call to visit that node
            }
        }

        /**
         * Find the leftmost node from this root, pushing all the intermediate nodes onto the visiting stack
         * and also stating that each is a left child of its parent
         * @param nodeKey
         * @param node the root of the subtree for which we are trying to reach the leftmost node
         */
        private void pushLeftmostNodeRecord(TrieKeySlice nodeKey, Trie node) {
            // find the leftmost node
            Trie leftNode = node.retrieveNode((byte) 0x00);
            if (leftNode != null) {
                TrieKeySlice leftNodeKey = nodeKey.rebuildSharedPath((byte) 0x00, leftNode.getSharedPath());
                visiting.push(new IterationElement(leftNodeKey, leftNode)); // push the left node
                visitingRightChild.push(Boolean.FALSE); // record that it is on the left
                pushLeftmostNodeRecord(leftNodeKey, leftNode); // continue looping
            }
        }
    }

    public static class IterationElement {
        private final TrieKeySlice nodeKey;
        private final Trie node;

        public IterationElement(final TrieKeySlice nodeKey, final Trie node) {
            this.nodeKey = nodeKey;
            this.node = node;
        }

        public Trie getNode() {
            return node;
        }

        public final TrieKeySlice getNodeKey() {
            return nodeKey;
        }

        public String toString() {
            byte[] encodedFullKey = nodeKey.encode();
            StringBuilder ouput = new StringBuilder();
            for (byte b : encodedFullKey) {
                ouput.append( b == 0 ? '0': '1');
            }
            return ouput.toString();
        }
    }


    /*
     * convert sharedPath into an object (no relation to storage rent) 
     * fields: path itself and pathlength
     * methods: deserialize and serialise sharedpath from/toMessage() and some helpers 
    */
    private static class SharedPathSerializer {
        private final TrieKeySlice sharedPath;
        private final int lshared;

        private SharedPathSerializer(TrieKeySlice sharedPath) {
            this.sharedPath = sharedPath;
            this.lshared = this.sharedPath.length();
        }

        private int serializedLength() {
            if (!isPresent()) {
                return 0;
            }

            return lsharedSize() + PathEncoder.calculateEncodedLength(lshared);
        }

        public boolean isPresent() {
            return lshared > 0;
        }

        private void serializeInto(ByteBuffer buffer) {
            if (!isPresent()) {
                return;
            }

            if (1 <= lshared && lshared <= 32) {
                // first byte in [0..31]
                buffer.put((byte) (lshared - 1));
            } else if (160 <= lshared && lshared <= 382) {
                // first byte in [32..254]
                buffer.put((byte) (lshared - 128));
            } else {
                buffer.put((byte) 255);
                buffer.put(new VarInt(lshared).encode());
            }

            buffer.put(this.sharedPath.encode());
        }

        private int lsharedSize() {
            if (!isPresent()) {
                return 0;
            }

            if (1 <= lshared && lshared <= 32) {
                return 1;
            }

            if (160 <= lshared && lshared <= 382) {
                return 1;
            }

            return 1 + VarInt.sizeOf(lshared);
        }

        private static TrieKeySlice deserialize(ByteBuffer message, boolean sharedPrefixPresent) {
            if (!sharedPrefixPresent) {
                return TrieKeySlice.empty();
            }

            int lshared;
            // upgrade to int so we can compare positive values
            int lsharedFirstByte = Byte.toUnsignedInt(message.get());
            if (0 <= lsharedFirstByte && lsharedFirstByte <= 31) {
                // lshared in [1..32]
                lshared = lsharedFirstByte + 1;
            } else if (32 <= lsharedFirstByte && lsharedFirstByte <= 254) {
                // lshared in [160..382]
                lshared = lsharedFirstByte + 128;
            } else {
                lshared = (int) readVarInt(message).value;
            }

            int lencoded = PathEncoder.calculateEncodedLength(lshared);
            byte[] encodedKey = new byte[lencoded];
            message.get(encodedKey);
            return TrieKeySlice.fromEncoded(encodedKey, 0, lshared, lencoded);
        }
    }

    // Additional auxiliary methods for Merkle Proof

    @Nullable
    public List<Trie> getNodes(byte[] key) {
        return findNodes(key);
    }

    @Nullable
    public List<Trie> getNodes(String key) {
        return this.getNodes(key.getBytes(StandardCharsets.UTF_8));
    }

    @Nullable
    private List<Trie> findNodes(byte[] key) {
        return findNodes(TrieKeySlice.fromKey(key));
    }

    @Nullable
    private List<Trie> findNodes(TrieKeySlice key) {
        if (sharedPath.length() > key.length()) {
            return null;
        }

        int commonPathLength = key.commonPath(sharedPath).length();

        if (commonPathLength < sharedPath.length()) {
            return null;
        }

        if (commonPathLength == key.length()) {
            List<Trie> nodes = new ArrayList<>();
            nodes.add(this);
            return nodes;
        }
        // if commonPathLength < key.length
        Trie node = this.retrieveNode(key.get(commonPathLength));

        if (node == null) {
            return null;
        }
        // recursion with smaller slice (starts at cPL+1) 
        List<Trie> subnodes = node.findNodes(key.slice(commonPathLength + 1, key.length()));

        if (subnodes == null) {
            return null;
        }

        subnodes.add(this);

        return subnodes;
    }

    // #mish Additional method for storage rent. 
    
    // Time until which storage rent has been paid in Unix seconds since epoch Jan 1970
    // Make sense only after RSK start date. "-1" value used for pure trie::put(k,v) to avoid 0 default
    @Nullable
    public long getLastRentPaidTime() {
        return lastRentPaidTime;
    }

    // return node version number as integer
    public int getNodeVersionNumber() {
        return (int) this.nodeVersion;
    }

}